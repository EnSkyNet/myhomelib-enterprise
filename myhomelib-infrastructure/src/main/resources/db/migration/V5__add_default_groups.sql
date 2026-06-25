-- Таблиця груп
CREATE TABLE IF NOT EXISTS groups (
                                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                                      name TEXT NOT NULL UNIQUE,
                                      allow_delete INTEGER NOT NULL DEFAULT 1
);

-- Таблиця зв'язків книг з групами
CREATE TABLE IF NOT EXISTS book_groups (
                                           book_id TEXT NOT NULL,
                                           group_id INTEGER NOT NULL,
                                           PRIMARY KEY (book_id, group_id),
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE,
    FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE
    );

-- Додаємо стандартні групи
INSERT OR IGNORE INTO groups (name, allow_delete) VALUES ('Favorites', 0);
INSERT OR IGNORE INTO groups (name, allow_delete) VALUES ('To Read', 0);