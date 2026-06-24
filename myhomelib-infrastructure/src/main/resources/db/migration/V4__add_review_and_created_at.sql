-- Додаємо колонки review та created_at до таблиці books
ALTER TABLE books ADD COLUMN review TEXT;
ALTER TABLE books ADD COLUMN created_at TEXT;

-- Оновлюємо created_at для існуючих книг (ставимо поточну дату, якщо NULL)
UPDATE books SET created_at = COALESCE(created_at, datetime('now', 'localtime'));