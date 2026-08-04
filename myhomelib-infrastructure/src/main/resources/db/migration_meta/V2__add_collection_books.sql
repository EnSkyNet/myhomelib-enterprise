-- Створюємо таблицю зв'язків колекцій і книг у meta-БД
CREATE TABLE IF NOT EXISTS collection_books (
                                                collection_id TEXT NOT NULL,
                                                book_id TEXT NOT NULL,
                                                PRIMARY KEY (collection_id, book_id),
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE
    );

-- Індекси для швидкого пошуку
CREATE INDEX IF NOT EXISTS idx_collection_books_collection_id ON collection_books(collection_id);
CREATE INDEX IF NOT EXISTS idx_collection_books_book_id ON collection_books(book_id);