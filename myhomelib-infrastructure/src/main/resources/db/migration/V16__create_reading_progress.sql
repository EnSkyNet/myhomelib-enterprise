-- Таблиця прогресу читання
CREATE TABLE IF NOT EXISTS reading_progress (
                                                book_id TEXT PRIMARY KEY,
                                                paragraph_id TEXT NOT NULL,
                                                char_offset INTEGER NOT NULL,
                                                percent REAL NOT NULL,
                                                updated_at TEXT NOT NULL,
                                                FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
    );

CREATE INDEX IF NOT EXISTS idx_reading_progress_updated_at ON reading_progress(updated_at);