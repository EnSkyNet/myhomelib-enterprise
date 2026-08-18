-- ============================================================
-- Таблиця статистики читання
-- ============================================================

CREATE TABLE IF NOT EXISTS reading_stats (
                                             id INTEGER PRIMARY KEY AUTOINCREMENT,
                                             book_id TEXT NOT NULL,
                                             first_read_at TEXT NOT NULL,
                                             last_read_at TEXT NOT NULL,
                                             total_reading_seconds INTEGER DEFAULT 0,
                                             reading_sessions INTEGER DEFAULT 0,
                                             start_percent INTEGER DEFAULT 0,
                                             end_percent INTEGER DEFAULT 0,
                                             current_percent INTEGER DEFAULT 0,
                                             completed_at TEXT,
                                             FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
    );

-- Індекси для швидкого пошуку
CREATE INDEX IF NOT EXISTS idx_reading_stats_book_id ON reading_stats(book_id);
CREATE INDEX IF NOT EXISTS idx_reading_stats_last_read_at ON reading_stats(last_read_at);
CREATE INDEX IF NOT EXISTS idx_reading_stats_current_percent ON reading_stats(current_percent);