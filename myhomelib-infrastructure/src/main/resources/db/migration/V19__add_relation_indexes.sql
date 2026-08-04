-- Додаємо індекси для пришвидшення JOIN-запитів

-- Індекс для book_authors.author_id (швидкий пошук книг автора)
CREATE INDEX IF NOT EXISTS idx_book_authors_author_id ON book_authors(author_id);

-- Індекс для book_genres.genre_code (швидкий пошук книг за жанром)
CREATE INDEX IF NOT EXISTS idx_book_genres_genre_code ON book_genres(genre_code);

-- Індекс для book_authors.book_id (вже є за PRIMARY KEY, але на всяк випадок)
-- PRIMARY KEY (book_id, author_id) вже створює індекс

-- Індекс для book_genres.book_id (вже є за PRIMARY KEY)
-- PRIMARY KEY (book_id, genre_code) вже створює індекс

-- Додаткові індекси для часто використовуваних полів
CREATE INDEX IF NOT EXISTS idx_books_collection_root ON books(collection_root);
CREATE INDEX IF NOT EXISTS idx_books_language ON books(language);

-- Аналіз БД для оновлення статистики
ANALYZE;