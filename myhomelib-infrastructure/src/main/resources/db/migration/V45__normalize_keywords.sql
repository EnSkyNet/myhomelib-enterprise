-- Normalized keyword index for navigation/filter queries.
-- books.keywords remains the canonical raw metadata string for compatibility/export,
-- while these tables are a searchable projection maintained by Java write paths.
CREATE TABLE IF NOT EXISTS keywords (
    normalized_name TEXT PRIMARY KEY,
    display_name    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS keyword_books (
    normalized_name TEXT NOT NULL,
    book_id         TEXT NOT NULL,
    PRIMARY KEY (normalized_name, book_id),
    FOREIGN KEY (normalized_name) REFERENCES keywords(normalized_name) ON DELETE CASCADE,
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_keyword_books_book_id
    ON keyword_books(book_id);

CREATE INDEX IF NOT EXISTS idx_keyword_books_keyword_book
    ON keyword_books(normalized_name, book_id);
