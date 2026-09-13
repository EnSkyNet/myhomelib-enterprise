-- MHL-113: persisted incoming-folder watcher configuration and restart-safe candidate queue.
CREATE TABLE IF NOT EXISTS incoming_folder_watch (
    collection_id TEXT PRIMARY KEY,
    folder_path TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 0,
    debounce_seconds INTEGER NOT NULL DEFAULT 2,
    stability_seconds INTEGER NOT NULL DEFAULT 3,
    last_scan_at TEXT,
    last_status TEXT NOT NULL DEFAULT 'CONFIGURED',
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS incoming_folder_file (
    collection_id TEXT NOT NULL,
    file_path TEXT NOT NULL,
    observed_size INTEGER NOT NULL DEFAULT 0,
    observed_mtime INTEGER NOT NULL DEFAULT 0,
    signature_seen_at TEXT,
    fingerprint TEXT,
    status TEXT NOT NULL DEFAULT 'WAITING',
    detected_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    imported_at TEXT,
    last_error TEXT,
    imported_count INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    error_count INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (collection_id, file_path),
    FOREIGN KEY (collection_id) REFERENCES incoming_folder_watch(collection_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_incoming_folder_file_ready
ON incoming_folder_file(collection_id, status, detected_at);

CREATE INDEX IF NOT EXISTS idx_incoming_folder_file_fingerprint
ON incoming_folder_file(collection_id, fingerprint, status);
