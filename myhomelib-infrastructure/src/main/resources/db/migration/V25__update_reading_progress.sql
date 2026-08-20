-- Оновлюємо таблицю reading_progress для підтримки точного відновлення позиції
-- Спочатку створюємо нову таблицю з правильною структурою
CREATE TABLE IF NOT EXISTS reading_progress_new (
                                                    book_id TEXT PRIMARY KEY,
                                                    paragraph_id TEXT NOT NULL,      -- Унікальний ID параграфа (XPath або стабільний ID)
                                                    char_offset INTEGER NOT NULL,    -- Зсув символів у параграфі
                                                    percent REAL DEFAULT 0,          -- Відсоток прочитаного
                                                    chapter_title TEXT,              -- Назва поточної глави
                                                    chapter_id TEXT,                 -- ID глави
                                                    updated_at TEXT NOT NULL,
                                                    reading_time_seconds INTEGER DEFAULT 0
);

-- Копіюємо дані зі старої таблиці
INSERT OR IGNORE INTO reading_progress_new (book_id, paragraph_id, char_offset, percent, updated_at)
SELECT book_id, paragraph_id, char_offset, percent, updated_at
FROM reading_progress;

-- Видаляємо стару таблицю та перейменовуємо нову
DROP TABLE IF EXISTS reading_progress;
ALTER TABLE reading_progress_new RENAME TO reading_progress;

-- Створюємо індекси
CREATE INDEX IF NOT EXISTS idx_reading_progress_book_id ON reading_progress(book_id);
CREATE INDEX IF NOT EXISTS idx_reading_progress_updated_at ON reading_progress(updated_at);