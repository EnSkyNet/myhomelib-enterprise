-- MHL-114: evolve classic saved searches into typed smart collections without a parallel store.
ALTER TABLE saved_searches ADD COLUMN kind TEXT NOT NULL DEFAULT 'SEARCH';
ALTER TABLE saved_searches ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_saved_searches_kind_pinned_name
    ON saved_searches(kind, pinned DESC, name COLLATE NOCASE);
