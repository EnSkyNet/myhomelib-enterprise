-- R18: books imported from an online catalog before book_artifacts existed were
-- backfilled by V50 as generic legacy artifacts. catalog_book_state is durable proof
-- that such a source is remote; preserve that fact so a missing local copy stays
-- downloadable instead of being projected as a permanently missing manual file.
UPDATE book_artifacts
   SET source_id = COALESCE(
           NULLIF(source_id, ''),
           (SELECT cbs.source_id
              FROM catalog_book_state cbs
             WHERE cbs.book_id = book_artifacts.book_id
             LIMIT 1)
       ),
       remote = 1,
       state = CASE WHEN local = 1 THEN 'AVAILABLE' ELSE 'REMOTE_ONLY' END,
       updated_at = CURRENT_TIMESTAMP
 WHERE artifact_id LIKE 'legacy:%'
   AND COALESCE(source_id, '') NOT LIKE 'conversion:%'
   AND EXISTS (
       SELECT 1 FROM catalog_book_state cbs WHERE cbs.book_id = book_artifacts.book_id
   );
