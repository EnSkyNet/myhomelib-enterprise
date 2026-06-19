-- ============================================================
-- MyHomeLib Enterprise - Initial Schema
-- ============================================================

-- Books
CREATE TABLE IF NOT EXISTS books (
                                     id TEXT PRIMARY KEY,
                                     title TEXT NOT NULL,
                                     series TEXT,
                                     sequence_number INTEGER,
                                     file_name TEXT NOT NULL,
                                     folder TEXT,
                                     archive_entry TEXT,
                                     language TEXT,
                                     file_size INTEGER,
                                     keywords TEXT,
                                     annotation TEXT,
                                     rate INTEGER DEFAULT 0,
                                     progress INTEGER DEFAULT 0,
                                     update_date TEXT,
                                     isbn TEXT,
                                     deleted INTEGER DEFAULT 0,
                                     local INTEGER DEFAULT 0
);

-- Authors
CREATE TABLE IF NOT EXISTS authors (
                                       id TEXT PRIMARY KEY,
                                       first_name TEXT,
                                       middle_name TEXT,
                                       last_name TEXT
);

-- Book-Authors (many-to-many)
CREATE TABLE IF NOT EXISTS book_authors (
                                            book_id TEXT NOT NULL,
                                            author_id TEXT NOT NULL,
                                            PRIMARY KEY (book_id, author_id),
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES authors(id) ON DELETE CASCADE
    );

-- Genres
CREATE TABLE IF NOT EXISTS genres (
                                      code TEXT PRIMARY KEY,
                                      name TEXT,
                                      parent_code TEXT,
                                      fb2_code TEXT
);

-- Book-Genres (many-to-many)
CREATE TABLE IF NOT EXISTS book_genres (
                                           book_id TEXT NOT NULL,
                                           genre_code TEXT NOT NULL,
                                           PRIMARY KEY (book_id, genre_code),
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE,
    FOREIGN KEY (genre_code) REFERENCES genres(code) ON DELETE CASCADE
    );

-- Collections (system data)
CREATE TABLE IF NOT EXISTS collections (
                                           id INTEGER PRIMARY KEY AUTOINCREMENT,
                                           name TEXT NOT NULL,
                                           root_folder TEXT,
                                           db_file TEXT,
                                           type INTEGER DEFAULT 0,
                                           user TEXT,
                                           password TEXT,
                                           url TEXT,
                                           notes TEXT,
                                           created TEXT
);

-- Groups (Favorites, To Read, etc.)
CREATE TABLE IF NOT EXISTS groups (
                                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                                      name TEXT NOT NULL UNIQUE,
                                      allow_delete INTEGER DEFAULT 1
);

-- Book-Groups (many-to-many)
CREATE TABLE IF NOT EXISTS book_groups (
                                           book_id TEXT NOT NULL,
                                           group_id INTEGER NOT NULL,
                                           PRIMARY KEY (book_id, group_id),
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE,
    FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE
    );

-- Settings (key-value)
CREATE TABLE IF NOT EXISTS settings (
                                        key TEXT PRIMARY KEY,
                                        value TEXT
);

-- ============================================================
-- Default Groups
-- ============================================================
INSERT OR IGNORE INTO groups (name, allow_delete) VALUES ('Favorites', 0);
INSERT OR IGNORE INTO groups (name, allow_delete) VALUES ('To Read', 0);

-- ============================================================
-- Indexes
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_books_title ON books(title);
CREATE INDEX IF NOT EXISTS idx_books_series ON books(series);
CREATE INDEX IF NOT EXISTS idx_books_language ON books(language);
CREATE INDEX IF NOT EXISTS idx_authors_last_name ON authors(last_name);

-- ============================================================
-- FTS5 (для повнотекстового пошуку)
-- Виправлено: додано стовпець book_id для прямого зв'язку з books.id
-- ============================================================
CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(
    book_id UNINDEXED,   -- зберігаємо ID книги без індексації
    title,
    authors,
    series,
    keywords,
    annotation,
    tokenize='unicode61'
);

-- FTS triggers (використовуємо book_id замість rowid)
CREATE TRIGGER IF NOT EXISTS books_fts_insert AFTER INSERT ON books
BEGIN
INSERT INTO books_fts(book_id, title, authors, series, keywords, annotation)
VALUES (
           new.id,
           lower(new.title),
           lower(COALESCE((
                              SELECT group_concat(last_name || ' ' || first_name)
                              FROM authors
                                       JOIN book_authors ON authors.id = book_authors.author_id
                              WHERE book_authors.book_id = new.id
                          ), '')),
           lower(new.series),
           lower(new.keywords),
           lower(new.annotation)
       );
END;

CREATE TRIGGER IF NOT EXISTS books_fts_update AFTER UPDATE ON books
BEGIN
UPDATE books_fts
SET title = lower(new.title),
    authors = lower(COALESCE((
                                 SELECT group_concat(last_name || ' ' || first_name)
                                 FROM authors
                                          JOIN book_authors ON authors.id = book_authors.author_id
                                 WHERE book_authors.book_id = new.id
                             ), '')),
    series = lower(new.series),
    keywords = lower(new.keywords),
    annotation = lower(new.annotation)
WHERE book_id = new.id;
END;

CREATE TRIGGER IF NOT EXISTS books_fts_delete AFTER DELETE ON books
BEGIN
DELETE FROM books_fts WHERE book_id = old.id;
END;