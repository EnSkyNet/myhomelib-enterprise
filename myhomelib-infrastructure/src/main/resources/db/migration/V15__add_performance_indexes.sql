-- Додаємо індекси для полів, які часто використовуються в ORDER BY та WHERE
CREATE INDEX IF NOT EXISTS idx_books_created_at ON books(created_at);
CREATE INDEX IF NOT EXISTS idx_books_update_date ON books(update_date);
CREATE INDEX IF NOT EXISTS idx_books_rate ON books(rate);

-- Індекси для зв'язків (пришвидшують JOIN)
CREATE INDEX IF NOT EXISTS idx_book_authors_author_id ON book_authors(author_id);
CREATE INDEX IF NOT EXISTS idx_book_genres_genre_code ON book_genres(genre_code);

-- Індекс для пошуку за назвою (для LIKE, якщо використовується)
CREATE INDEX IF NOT EXISTS idx_books_title_lower ON books(lower(title));