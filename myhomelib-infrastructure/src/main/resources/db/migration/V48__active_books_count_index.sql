-- Keep exact active-book counts cheap on very large catalogues.
-- The browsing/query layer consistently uses `deleted = 0`, so SQLite can satisfy
-- COUNT(*) from this narrow partial covering index instead of scanning the wide books table.
-- Do not replace the predicate with COALESCE(deleted, 0) = 0 in hot-path queries: that
-- expression does not imply this partial-index predicate to SQLite's planner.
CREATE INDEX IF NOT EXISTS idx_books_active_id
    ON books(id)
    WHERE deleted = 0;
