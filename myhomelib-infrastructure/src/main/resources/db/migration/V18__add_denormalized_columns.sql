-- Додаємо денормалізовані колонки для пришвидшення сортування та фільтрації
ALTER TABLE books ADD COLUMN format TEXT;
ALTER TABLE books ADD COLUMN author_sort TEXT;

-- Створюємо індекси для цих колонок
CREATE INDEX IF NOT EXISTS idx_books_format ON books(format);
CREATE INDEX IF NOT EXISTS idx_books_author_sort ON books(author_sort);

-- Оновлюємо існуючі записи (можна зробити через Java-код, але тут залишаємо заглушку)
-- Фактичне заповнення буде виконуватися під час імпорту або окремим скриптом