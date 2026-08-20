-- ============================================================
-- Перестворюємо таблицю reading_progress з правильною структурою
-- ============================================================

-- Створюємо нову таблицю
CREATE TABLE IF NOT EXISTS reading_progress_new (
                                                    book_id TEXT PRIMARY KEY,
                                                    paragraph_id TEXT NOT NULL,      -- XPath або стабільний ID
                                                    char_offset INTEGER NOT NULL,
                                                    percent REAL DEFAULT 0,
                                                    chapter_title TEXT,
                                                    chapter_id TEXT,
                                                    updated_at TEXT NOT NULL,
                                                    reading_time_seconds INTEGER DEFAULT 0
);

-- Копіюємо дані зі старої таблиці
INSERT OR IGNORE INTO reading_progress_new (book_id, paragraph_id, char_offset, percent, updated_at)
SELECT book_id, paragraph_id, char_offset, percent, updated_at
FROM reading_progress;

-- Видаляємо стару таблицю
DROP TABLE IF EXISTS reading_progress;

-- Перейменовуємо нову
ALTER TABLE reading_progress_new RENAME TO reading_progress;

-- Створюємо індекси
CREATE INDEX IF NOT EXISTS idx_reading_progress_book_id ON reading_progress(book_id);
CREATE INDEX IF NOT EXISTS idx_reading_progress_updated_at ON reading_progress(updated_at);