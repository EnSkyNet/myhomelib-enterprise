-- Таблиця колекцій (мета-БД)
CREATE TABLE IF NOT EXISTS collections (
                                           id TEXT PRIMARY KEY,
                                           name TEXT NOT NULL,
                                           root_folder TEXT,
                                           db_file TEXT,
                                           type INTEGER DEFAULT 0,
                                           user TEXT,
                                           password TEXT,
                                           url TEXT,
                                           notes TEXT,
                                           created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

-- Індекси
CREATE INDEX IF NOT EXISTS idx_collections_name ON collections(name);