-- Conversion output is an additional representation, not a replacement for the source file.
-- Older builds could mark a generated conversion artifact as preferred and then copy its
-- output directory/file into books.*, losing the original catalogue location. Repair only
-- books whose current preferred artifact is a conversion. Prefer the exact source artifact
-- recorded by ConvertBookUseCase metadata; fall back to a stable non-conversion artifact.

CREATE TEMP TABLE _mhl_conversion_projection_repair (
    book_id TEXT PRIMARY KEY,
    source_artifact_id TEXT NOT NULL
);

INSERT OR IGNORE INTO _mhl_conversion_projection_repair(book_id, source_artifact_id)
SELECT p.book_id, m.metadata_value
  FROM book_artifact_preferences p
  JOIN book_artifacts converted
    ON converted.book_id = p.book_id
   AND converted.artifact_id = p.preferred_artifact_id
  JOIN book_artifact_metadata m
    ON m.artifact_id = converted.artifact_id
   AND m.metadata_key = 'conversion.sourceArtifactId'
  JOIN book_artifacts source
    ON source.book_id = p.book_id
   AND source.artifact_id = m.metadata_value
 WHERE COALESCE(converted.source_id, '') LIKE 'conversion:%';

INSERT OR IGNORE INTO _mhl_conversion_projection_repair(book_id, source_artifact_id)
SELECT p.book_id,
       (
           SELECT source.artifact_id
             FROM book_artifacts source
            WHERE source.book_id = p.book_id
              AND source.artifact_id <> p.preferred_artifact_id
              AND COALESCE(source.source_id, '') NOT LIKE 'conversion:%'
            ORDER BY
                CASE WHEN source.local = 1 AND source.state = 'AVAILABLE' THEN 0 ELSE 1 END,
                source.created_at,
                source.artifact_id
            LIMIT 1
       )
  FROM book_artifact_preferences p
  JOIN book_artifacts converted
    ON converted.book_id = p.book_id
   AND converted.artifact_id = p.preferred_artifact_id
 WHERE COALESCE(converted.source_id, '') LIKE 'conversion:%'
   AND EXISTS (
       SELECT 1
         FROM book_artifacts source
        WHERE source.book_id = p.book_id
          AND source.artifact_id <> p.preferred_artifact_id
          AND COALESCE(source.source_id, '') NOT LIKE 'conversion:%'
   );

UPDATE book_artifact_preferences
   SET preferred_artifact_id = (
           SELECT r.source_artifact_id
             FROM _mhl_conversion_projection_repair r
            WHERE r.book_id = book_artifact_preferences.book_id
       ),
       updated_at = CURRENT_TIMESTAMP
 WHERE book_id IN (SELECT book_id FROM _mhl_conversion_projection_repair);

UPDATE books
   SET file_name = COALESCE((
           SELECT COALESCE(NULLIF(a.archive_name, ''), a.file_name, '')
             FROM _mhl_conversion_projection_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), file_name),
       folder = COALESCE((
           SELECT a.folder
             FROM _mhl_conversion_projection_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), ''),
       archive_entry = COALESCE((
           SELECT a.archive_entry
             FROM _mhl_conversion_projection_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), ''),
       file_size = COALESCE((
           SELECT a.size_bytes
             FROM _mhl_conversion_projection_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), 0),
       collection_root = COALESCE((
           SELECT a.collection_root
             FROM _mhl_conversion_projection_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), ''),
       local = COALESCE((
           SELECT a.local
             FROM _mhl_conversion_projection_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), 0),
       missing_since = CASE
           WHEN COALESCE((
               SELECT a.local
                 FROM _mhl_conversion_projection_repair r
                 JOIN book_artifacts a
                   ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
                WHERE r.book_id = books.id
           ), 0) = 1 THEN NULL
           ELSE COALESCE(missing_since, CURRENT_TIMESTAMP)
       END,
       format = COALESCE((
           SELECT NULLIF(a.file_format, '')
             FROM _mhl_conversion_projection_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), format)
 WHERE id IN (SELECT book_id FROM _mhl_conversion_projection_repair);

DROP TABLE _mhl_conversion_projection_repair;
