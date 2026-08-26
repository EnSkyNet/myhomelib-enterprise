CREATE TABLE IF NOT EXISTS collection_source_watch (
    collection_id TEXT PRIMARY KEY,
    source_file TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 0,
    debounce_seconds INTEGER NOT NULL DEFAULT 60,
    baseline_fingerprint TEXT,
    observed_fingerprint TEXT,
    last_checked_at TEXT,
    update_available INTEGER NOT NULL DEFAULT 0,
    last_status TEXT,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_collection_source_watch_enabled
ON collection_source_watch(enabled);
