CREATE TABLE IF NOT EXISTS annotation_remote_export_state (
    remote TEXT NOT NULL,
    annotation_id TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    exported_at TEXT NOT NULL,
    PRIMARY KEY (remote, annotation_id),
    FOREIGN KEY (annotation_id) REFERENCES annotations(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_annotation_remote_export_state_remote
    ON annotation_remote_export_state(remote, exported_at);
