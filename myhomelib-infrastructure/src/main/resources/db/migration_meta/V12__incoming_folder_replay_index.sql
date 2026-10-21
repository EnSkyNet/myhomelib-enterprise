-- MHL-113: SQLite-backed 4 GB RAM watcher overflow uses this ordered keyset cursor:
-- WHERE status='WAITING' AND (collection_id>? OR (collection_id=? AND file_path>?))
-- ORDER BY collection_id,file_path LIMIT 128.
-- A covering index avoids scanning imported/ready records while replaying pending paths.
CREATE INDEX IF NOT EXISTS idx_incoming_folder_waiting_replay
    ON incoming_folder_file(status, collection_id, file_path);
