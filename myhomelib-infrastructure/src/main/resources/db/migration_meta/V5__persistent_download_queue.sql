-- v7.1 restart-safe online book download queue. No credentials are persisted here.
CREATE TABLE IF NOT EXISTS online_download_queue (
    collection_id TEXT NOT NULL,
    book_id TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status TEXT NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_attempt TEXT,
    download_destination TEXT,
    physical_archive_identity TEXT,
    resume_information TEXT,
    last_error TEXT,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (collection_id, book_id),
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE,
    CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','FAILED','CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_online_download_queue_status
ON online_download_queue(status, updated_at);

CREATE INDEX IF NOT EXISTS idx_online_download_queue_archive
ON online_download_queue(collection_id, physical_archive_identity);
