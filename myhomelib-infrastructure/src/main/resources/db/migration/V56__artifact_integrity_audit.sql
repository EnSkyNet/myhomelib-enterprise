-- MHL-116: persistent, non-destructive baseline/cache for incremental local-artifact audits.
CREATE TABLE IF NOT EXISTS artifact_integrity_state (
    artifact_id TEXT PRIMARY KEY,
    baseline_size_bytes INTEGER CHECK(baseline_size_bytes IS NULL OR baseline_size_bytes >= 0),
    baseline_sha256 TEXT,
    last_physical_size_bytes INTEGER NOT NULL DEFAULT -1,
    last_modified_millis INTEGER NOT NULL DEFAULT -1,
    observed_size_bytes INTEGER NOT NULL DEFAULT -1,
    observed_sha256 TEXT,
    status TEXT NOT NULL DEFAULT 'OK'
        CHECK(status IN ('OK','MISSING','UNREADABLE','CORRUPT_ARCHIVE','SIZE_CHANGED','HASH_CHANGED')),
    detail TEXT,
    checked_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(artifact_id) REFERENCES book_artifacts(artifact_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_artifact_integrity_state_status
    ON artifact_integrity_state(status, artifact_id);
