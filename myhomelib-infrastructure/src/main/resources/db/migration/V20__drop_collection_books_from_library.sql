-- Видаляємо таблицю collection_books з library-БД
-- Вона більше не використовується, дані зберігаються в meta-БД

DROP TABLE IF EXISTS collection_books;

-- Також видаляємо пов'язані індекси, якщо вони існують
DROP INDEX IF EXISTS idx_collection_books_collection_id;
DROP INDEX IF EXISTS idx_collection_books_book_id;