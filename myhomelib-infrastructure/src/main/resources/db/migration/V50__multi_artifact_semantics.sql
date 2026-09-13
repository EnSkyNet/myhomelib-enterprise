-- 7.2 foundation: make book_artifacts the durable multi-representation model while
-- keeping books.* storage columns as the backward-compatible preferred projection.

ALTER TABLE book_artifacts ADD COLUMN collection_root TEXT;
ALTER TABLE book_artifacts ADD COLUMN folder TEXT;
ALTER TABLE book_artifacts ADD COLUMN state TEXT NOT NULL DEFAULT 'AVAILABLE'
    CHECK(state IN ('AVAILABLE','REMOTE_ONLY','MISSING','CORRUPT','UNAVAILABLE'));

-- Existing catalog artifacts are remote-only unless a local copy was already recorded.
UPDATE book_artifacts
   SET state = CASE
       WHEN local = 1 THEN 'AVAILABLE'
       WHEN remote = 1 THEN 'REMOTE_ONLY'
       ELSE 'MISSING'
   END;

-- Carry legacy storage coordinates into the artifact that already represents the
-- preferred books.* projection.
UPDATE book_artifacts
   SET collection_root = COALESCE((
           SELECT b.collection_root FROM books b WHERE b.id = book_artifacts.book_id
       ), collection_root),
       folder = COALESCE((
           SELECT b.folder FROM books b WHERE b.id = book_artifacts.book_id
       ), folder)
 WHERE EXISTS (
       SELECT 1 FROM books b
        WHERE b.id = book_artifacts.book_id
          AND COALESCE(book_artifacts.file_name, '') = COALESCE(b.file_name, '')
          AND COALESCE(book_artifacts.archive_entry, '') = COALESCE(b.archive_entry, '')
 );

-- Local/manual books created before V36 may not have had any artifact row.  Give
-- each of them one deterministic legacy artifact so every logical book participates
-- in the new model without changing its current storage projection.
INSERT INTO book_artifacts(
    artifact_id, book_id, source_id, artifact_name, media_type, file_format,
    file_name, archive_name, archive_entry, size_bytes, sha256, content_fingerprint,
    remote, local, created_at, updated_at, collection_root, folder, state
)
SELECT
    'legacy:' || b.id,
    b.id,
    NULL,
    CASE
        WHEN COALESCE(b.archive_entry, '') <> '' THEN b.archive_entry
        WHEN COALESCE(b.file_name, '') <> '' THEN b.file_name
        ELSE 'book-' || b.id
    END,
    NULL,
    NULL,
    b.file_name,
    NULL,
    b.archive_entry,
    CASE WHEN b.file_size IS NULL OR b.file_size < 0 THEN 0 ELSE b.file_size END,
    NULL,
    NULL,
    0,
    CASE WHEN b.local = 1 THEN 1 ELSE 0 END,
    COALESCE(b.created_at, CURRENT_TIMESTAMP),
    COALESCE(b.update_date, CURRENT_TIMESTAMP),
    b.collection_root,
    b.folder,
    CASE WHEN b.local = 1 THEN 'AVAILABLE' ELSE 'MISSING' END
FROM books b
WHERE NOT EXISTS (SELECT 1 FROM book_artifacts ba WHERE ba.book_id = b.id);

-- Normalize file_format for inserted legacy artifacts with suffix rules that cover
-- the canonical 7.1 registry.
UPDATE book_artifacts
   SET file_format = CASE
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.fb2' THEN 'fb2'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.epub' THEN 'epub'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.pdf' THEN 'pdf'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.djvu' THEN 'djvu'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.mobi' THEN 'mobi'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.azw3' THEN 'azw3'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.azw' THEN 'azw'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.txt' THEN 'txt'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.rtf' THEN 'rtf'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.docx' THEN 'docx'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.html' THEN 'html'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.htm' THEN 'html'
       WHEN lower(COALESCE(NULLIF(archive_entry, ''), file_name, '')) LIKE '%.chm' THEN 'chm'
       ELSE file_format
   END
 WHERE artifact_id LIKE 'legacy:%';

CREATE UNIQUE INDEX IF NOT EXISTS uq_book_artifacts_book_artifact
    ON book_artifacts(book_id, artifact_id);
CREATE INDEX IF NOT EXISTS idx_book_artifacts_book_state
    ON book_artifacts(book_id, state, artifact_id);

CREATE TABLE IF NOT EXISTS book_artifact_preferences (
    book_id TEXT PRIMARY KEY,
    preferred_artifact_id TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE,
    FOREIGN KEY(book_id, preferred_artifact_id)
        REFERENCES book_artifacts(book_id, artifact_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_book_artifact_preferences_artifact
    ON book_artifact_preferences(preferred_artifact_id);

-- Preserve an exact legacy books.* match first.  Correlation is kept in WHERE
-- rather than ORDER BY because SQLite does not resolve outer aliases in every
-- ORDER BY subquery context.
INSERT OR IGNORE INTO book_artifact_preferences(book_id, preferred_artifact_id)
SELECT b.id,
       (
           SELECT ba.artifact_id
             FROM book_artifacts ba
            WHERE ba.book_id = b.id
              AND COALESCE(ba.file_name, '') = COALESCE(b.file_name, '')
              AND COALESCE(ba.archive_entry, '') = COALESCE(b.archive_entry, '')
            ORDER BY ba.artifact_id
            LIMIT 1
       )
  FROM books b
 WHERE EXISTS (
       SELECT 1 FROM book_artifacts ba
        WHERE ba.book_id = b.id
          AND COALESCE(ba.file_name, '') = COALESCE(b.file_name, '')
          AND COALESCE(ba.archive_entry, '') = COALESCE(b.archive_entry, '')
 );

-- For books without an exact legacy match, prefer a local available artifact,
-- then a stable first artifact.
INSERT OR IGNORE INTO book_artifact_preferences(book_id, preferred_artifact_id)
SELECT b.id,
       (
           SELECT ba.artifact_id
             FROM book_artifacts ba
            WHERE ba.book_id = b.id
            ORDER BY
                CASE WHEN ba.state = 'AVAILABLE' AND ba.local = 1 THEN 0 ELSE 1 END,
                ba.artifact_id
            LIMIT 1
       )
  FROM books b
 WHERE EXISTS (SELECT 1 FROM book_artifacts ba WHERE ba.book_id = b.id);
