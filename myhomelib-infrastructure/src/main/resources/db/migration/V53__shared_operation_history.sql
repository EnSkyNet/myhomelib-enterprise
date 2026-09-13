-- 7.2 MHL-112: shared restart-safe operation history for reversible collection mutations.
CREATE TABLE IF NOT EXISTS operation_history (
    operation_id TEXT PRIMARY KEY,
    operation_type TEXT NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    affected_count INTEGER NOT NULL DEFAULT 0,
    changed_count INTEGER NOT NULL DEFAULT 0,
    rules_json TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TEXT,
    undone_at TEXT,
    CHECK (operation_type IN ('BULK_METADATA', 'BOOK_MERGE')),
    CHECK (affected_count >= 0),
    CHECK (changed_count >= 0)
);

CREATE TABLE IF NOT EXISTS bulk_metadata_changes (
    operation_id TEXT NOT NULL,
    sequence_no INTEGER NOT NULL,
    book_id TEXT NOT NULL,
    before_json TEXT NOT NULL,
    after_json TEXT NOT NULL,
    PRIMARY KEY (operation_id, sequence_no),
    UNIQUE (operation_id, book_id),
    FOREIGN KEY (operation_id) REFERENCES operation_history(operation_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_operation_history_latest
    ON operation_history(completed_at DESC, operation_id)
    WHERE completed_at IS NOT NULL AND undone_at IS NULL AND changed_count > 0;

CREATE INDEX IF NOT EXISTS idx_bulk_metadata_changes_book
    ON bulk_metadata_changes(book_id, operation_id);

-- Backfill pre-MHL-112 merge journals so restart-safe shared undo also covers upgrades.
INSERT OR IGNORE INTO operation_history(
    operation_id, operation_type, summary, affected_count, changed_count,
    rules_json, created_at, completed_at, undone_at
)
SELECT merge_id, 'BOOK_MERGE',
       'Book merge: ' || survivor_book_id || ' <- ' || merged_book_id,
       2, 2, NULL, merged_at, merged_at, undone_at
  FROM book_merge_journal;
