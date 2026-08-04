-- ============================================================
-- Створення таблиці видавництв
-- ============================================================

CREATE TABLE IF NOT EXISTS publishers (
                                          id TEXT PRIMARY KEY,
                                          name TEXT NOT NULL,
                                          description TEXT,
                                          website TEXT,
                                          created_at TEXT NOT NULL
);

-- Індекс для швидкого пошуку за назвою
CREATE INDEX IF NOT EXISTS idx_publishers_name ON publishers(name);

-- Додаємо колонку publisher до таблиці books
ALTER TABLE books ADD COLUMN publisher TEXT;

-- Індекс для publisher
CREATE INDEX IF NOT EXISTS idx_books_publisher ON books(publisher);

-- Заповнюємо publishers з даних books
INSERT OR IGNORE INTO publishers (id, name, created_at)
SELECT
    lower(hex(randomblob(4))) || '-' ||
    lower(hex(randomblob(2))) || '-' ||
    lower(hex(randomblob(2))) || '-' ||
    lower(hex(randomblob(2))) || '-' ||
    lower(hex(randomblob(6))),
    publisher,
    datetime('now', 'localtime')
FROM books
WHERE publisher IS NOT NULL AND publisher != ''
GROUP BY publisher;