-- 7.2 MHL-106: durable logical-book merge journal.
-- No foreign keys are intentional: the evidence/snapshot must survive logical deletion and remain undoable.
CREATE TABLE IF NOT EXISTS book_merge_journal (
    merge_id TEXT PRIMARY KEY,
    survivor_book_id TEXT NOT NULL,
    merged_book_id TEXT NOT NULL,
    metadata_source_book_id TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    merged_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    undone_at TEXT,
    CHECK (survivor_book_id <> merged_book_id),
    CHECK (metadata_source_book_id = survivor_book_id OR metadata_source_book_id = merged_book_id)
);

CREATE INDEX IF NOT EXISTS idx_book_merge_journal_active_survivor
    ON book_merge_journal(survivor_book_id, merged_at DESC)
    WHERE undone_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_book_merge_journal_active_merged
    ON book_merge_journal(merged_book_id, merged_at DESC)
    WHERE undone_at IS NULL;
