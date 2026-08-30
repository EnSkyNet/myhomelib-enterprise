-- v7.1: per-book Reader overrides are collection-scoped user data.
-- The previous global JSON file required O(N) read/rewrite on every change and
-- could mix book ids from unrelated collections. Store overrides in the active
-- collection DB so lookups/updates are O(1), transactional, and naturally
-- deleted with the book.
CREATE TABLE IF NOT EXISTS reader_book_preferences (
    book_id TEXT PRIMARY KEY,
    preferences_json TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
);
