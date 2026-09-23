CREATE TABLE IF NOT EXISTS library_organization_operations (
    id TEXT PRIMARY KEY,
    collection_id TEXT,
    profile_id TEXT NOT NULL,
    source_root TEXT NOT NULL,
    destination_root TEXT NOT NULL,
    state TEXT NOT NULL,
    started_at TEXT NOT NULL,
    completed_at TEXT,
    undo_deadline TEXT,
    summary_json TEXT NOT NULL DEFAULT '{}',
    CHECK (state IN ('PLANNED','APPLYING','COMPLETED','FAILED','CANCELLED','ROLLED_BACK'))
);

CREATE TABLE IF NOT EXISTS library_organization_items (
    operation_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    book_id TEXT,
    source_path TEXT NOT NULL,
    target_path TEXT,
    source_sha256 TEXT NOT NULL DEFAULT '',
    target_sha256 TEXT NOT NULL DEFAULT '',
    action TEXT NOT NULL,
    state TEXT NOT NULL,
    backup_path TEXT,
    error_code TEXT,
    error_message TEXT,
    PRIMARY KEY (operation_id, item_id),
    FOREIGN KEY (operation_id) REFERENCES library_organization_operations(id) ON DELETE CASCADE,
    CHECK (state IN ('PLANNED','STAGING','VALIDATED','COMMITTED','SKIPPED','FAILED','ROLLED_BACK'))
);

CREATE INDEX IF NOT EXISTS idx_meta_library_org_operations_state
    ON library_organization_operations(state, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_meta_library_org_items_operation_state
    ON library_organization_items(operation_id, state, item_id);
