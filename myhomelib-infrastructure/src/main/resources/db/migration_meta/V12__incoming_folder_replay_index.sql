-- MHL-113: only WAITING paths belong in the bounded SQLite replay queue.
-- This partial covering index stores no READY/IMPORTED history and supports:
-- WHERE status='WAITING' AND (collection_id>? OR (collection_id=? AND file_path>?))
-- ORDER BY collection_id,file_path LIMIT 128.
CREATE INDEX IF NOT EXISTS idx_incoming_folder_waiting_replay
    ON incoming_folder_file(collection_id, file_path)
    WHERE status='WAITING';
