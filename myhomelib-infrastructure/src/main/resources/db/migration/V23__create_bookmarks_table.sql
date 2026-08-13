CREATE TABLE IF NOT EXISTS bookmarks (
                                         id TEXT PRIMARY KEY,
                                         book_id TEXT NOT NULL,
                                         paragraph_id TEXT NOT NULL,
                                         char_offset INTEGER DEFAULT 0,
                                         position REAL DEFAULT 0,
                                         chapter_title TEXT,
                                         context TEXT,
                                         created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bookmarks_book_id ON bookmarks(book_id);
CREATE INDEX IF NOT EXISTS idx_bookmarks_created_at ON bookmarks(created_at);