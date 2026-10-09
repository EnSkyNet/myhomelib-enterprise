-- R13 regression repair: conversion outputs are secondary representations.
-- Some books imported after V50 can have artifact rows without an explicit
-- book_artifact_preferences row. Older rehydration then used the first artifact
-- as an implicit primary, so a generated conversion could become books.*.
-- Repair only missing-preference books and always prefer a non-conversion source.

CREATE TEMP TABLE _mhl_missing_preference_repair (
    book_id TEXT PRIMARY KEY,
    source_artifact_id TEXT NOT NULL
);

-- Strongest evidence: books.* currently matches a generated conversion and that
-- conversion records the exact source artifact in conversion.sourceArtifactId.
INSERT OR IGNORE INTO _mhl_missing_preference_repair(book_id, source_artifact_id)
SELECT b.id, m.metadata_value
  FROM books b
  JOIN book_artifacts converted
    ON converted.book_id = b.id
   AND COALESCE(converted.source_id, '') LIKE 'conversion:%'
   AND COALESCE(converted.file_name, '') = COALESCE(b.file_name, '')
   AND COALESCE(converted.folder, '') = COALESCE(b.folder, '')
   AND COALESCE(converted.archive_entry, '') = COALESCE(b.archive_entry, '')
   AND COALESCE(converted.collection_root, '') = COALESCE(b.collection_root, '')
  JOIN book_artifact_metadata m
    ON m.artifact_id = converted.artifact_id
   AND m.metadata_key = 'conversion.sourceArtifactId'
  JOIN book_artifacts source
    ON source.book_id = b.id
   AND source.artifact_id = m.metadata_value
   AND COALESCE(source.source_id, '') NOT LIKE 'conversion:%'
 WHERE NOT EXISTS (
       SELECT 1 FROM book_artifact_preferences p WHERE p.book_id = b.id
 );

-- Fallback for missing-preference rows whose books.* already points at a
-- conversion but whose old conversion metadata is incomplete.
INSERT OR IGNORE INTO _mhl_missing_preference_repair(book_id, source_artifact_id)
SELECT b.id,
       (
           SELECT source.artifact_id
             FROM book_artifacts source
            WHERE source.book_id = b.id
              AND COALESCE(source.source_id, '') NOT LIKE 'conversion:%'
            ORDER BY
                CASE WHEN source.local = 1 AND source.state = 'AVAILABLE' THEN 0 ELSE 1 END,
                source.created_at,
                source.artifact_id
            LIMIT 1
       )
  FROM books b
 WHERE NOT EXISTS (
       SELECT 1 FROM book_artifact_preferences p WHERE p.book_id = b.id
 )
   AND EXISTS (
       SELECT 1
         FROM book_artifacts converted
        WHERE converted.book_id = b.id
          AND COALESCE(converted.source_id, '') LIKE 'conversion:%'
          AND COALESCE(converted.file_name, '') = COALESCE(b.file_name, '')
          AND COALESCE(converted.folder, '') = COALESCE(b.folder, '')
          AND COALESCE(converted.archive_entry, '') = COALESCE(b.archive_entry, '')
          AND COALESCE(converted.collection_root, '') = COALESCE(b.collection_root, '')
   )
   AND EXISTS (
       SELECT 1
         FROM book_artifacts source
        WHERE source.book_id = b.id
          AND COALESCE(source.source_id, '') NOT LIKE 'conversion:%'
   );

-- Restore the legacy books.* projection for rows proven to have been replaced
-- by a generated conversion while lacking an explicit preference.
UPDATE books
   SET file_name = COALESCE((
           SELECT COALESCE(NULLIF(a.archive_name, ''), a.file_name, '')
             FROM _mhl_missing_preference_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), file_name),
       folder = COALESCE((
           SELECT a.folder
             FROM _mhl_missing_preference_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), ''),
       archive_entry = COALESCE((
           SELECT a.archive_entry
             FROM _mhl_missing_preference_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), ''),
       file_size = COALESCE((
           SELECT a.size_bytes
             FROM _mhl_missing_preference_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), 0),
       collection_root = COALESCE((
           SELECT a.collection_root
             FROM _mhl_missing_preference_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), ''),
       local = COALESCE((
           SELECT a.local
             FROM _mhl_missing_preference_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), 0),
       missing_since = CASE
           WHEN COALESCE((
               SELECT a.local
                 FROM _mhl_missing_preference_repair r
                 JOIN book_artifacts a
                   ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
                WHERE r.book_id = books.id
           ), 0) = 1 THEN NULL
           ELSE COALESCE(missing_since, CURRENT_TIMESTAMP)
       END,
       format = COALESCE((
           SELECT NULLIF(a.file_format, '')
             FROM _mhl_missing_preference_repair r
             JOIN book_artifacts a
               ON a.book_id = r.book_id AND a.artifact_id = r.source_artifact_id
            WHERE r.book_id = books.id
       ), format)
 WHERE id IN (SELECT book_id FROM _mhl_missing_preference_repair);

-- First create preferences for all missing rows whose current books.* projection
-- already matches a non-conversion artifact. This covers post-V50 imports even
-- when no conversion has yet happened.
INSERT OR IGNORE INTO book_artifact_preferences(book_id, preferred_artifact_id)
SELECT b.id,
       (
           SELECT a.artifact_id
             FROM book_artifacts a
            WHERE a.book_id = b.id
              AND COALESCE(a.source_id, '') NOT LIKE 'conversion:%'
              AND COALESCE(a.file_name, '') = COALESCE(b.file_name, '')
              AND COALESCE(a.folder, '') = COALESCE(b.folder, '')
              AND COALESCE(a.archive_entry, '') = COALESCE(b.archive_entry, '')
              AND COALESCE(a.collection_root, '') = COALESCE(b.collection_root, '')
            ORDER BY
                CASE WHEN a.local = 1 AND a.state = 'AVAILABLE' THEN 0 ELSE 1 END,
                a.created_at,
                a.artifact_id
            LIMIT 1
       )
  FROM books b
 WHERE NOT EXISTS (
       SELECT 1 FROM book_artifact_preferences p WHERE p.book_id = b.id
 )
   AND EXISTS (
       SELECT 1
         FROM book_artifacts a
        WHERE a.book_id = b.id
          AND COALESCE(a.source_id, '') NOT LIKE 'conversion:%'
          AND COALESCE(a.file_name, '') = COALESCE(b.file_name, '')
          AND COALESCE(a.folder, '') = COALESCE(b.folder, '')
          AND COALESCE(a.archive_entry, '') = COALESCE(b.archive_entry, '')
          AND COALESCE(a.collection_root, '') = COALESCE(b.collection_root, '')
   );

-- Last-resort deterministic preference for any remaining missing-preference
-- book: never select a generated conversion while a source artifact exists.
INSERT OR IGNORE INTO book_artifact_preferences(book_id, preferred_artifact_id)
SELECT b.id,
       (
           SELECT a.artifact_id
             FROM book_artifacts a
            WHERE a.book_id = b.id
              AND COALESCE(a.source_id, '') NOT LIKE 'conversion:%'
            ORDER BY
                CASE WHEN a.local = 1 AND a.state = 'AVAILABLE' THEN 0 ELSE 1 END,
                a.created_at,
                a.artifact_id
            LIMIT 1
       )
  FROM books b
 WHERE NOT EXISTS (
       SELECT 1 FROM book_artifact_preferences p WHERE p.book_id = b.id
 )
   AND EXISTS (
       SELECT 1
         FROM book_artifacts a
        WHERE a.book_id = b.id
          AND COALESCE(a.source_id, '') NOT LIKE 'conversion:%'
   );

DROP TABLE _mhl_missing_preference_repair;
