-- 7.2 MHL-104: restart-safe exact duplicate scanner.
-- Each scan snapshots the eligible artifact ids so resume semantics are stable even if
-- catalog rows are added while hashing is in progress.
CREATE TABLE IF NOT EXISTS artifact_duplicate_scans (
    scan_id TEXT PRIMARY KEY,
    status TEXT NOT NULL CHECK(status IN ('RUNNING','PAUSED','COMPLETED')),
    total INTEGER NOT NULL DEFAULT 0 CHECK(total >= 0),
    processed INTEGER NOT NULL DEFAULT 0 CHECK(processed >= 0),
    hashed INTEGER NOT NULL DEFAULT 0 CHECK(hashed >= 0),
    skipped INTEGER NOT NULL DEFAULT 0 CHECK(skipped >= 0),
    bytes_processed INTEGER NOT NULL DEFAULT 0 CHECK(bytes_processed >= 0),
    started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_artifact_duplicate_scans_incomplete
    ON artifact_duplicate_scans(completed_at, started_at DESC);

CREATE TABLE IF NOT EXISTS artifact_duplicate_scan_queue (
    scan_id TEXT NOT NULL,
    artifact_id TEXT NOT NULL,
    processed_at TEXT,
    skip_reason TEXT,
    PRIMARY KEY(scan_id, artifact_id),
    FOREIGN KEY(scan_id) REFERENCES artifact_duplicate_scans(scan_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_artifact_duplicate_scan_queue_pending
    ON artifact_duplicate_scan_queue(scan_id, processed_at, artifact_id);
