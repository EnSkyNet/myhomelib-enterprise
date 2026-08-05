-- ============================================================
-- Таблиця збережених пошуків
-- ============================================================

CREATE TABLE IF NOT EXISTS saved_searches (
                                              id TEXT PRIMARY KEY,
                                              name TEXT NOT NULL UNIQUE,
                                              query TEXT NOT NULL,
                                              filters TEXT,
                                              created_at TEXT NOT NULL,
                                              last_used TEXT NOT NULL,
                                              use_count INTEGER DEFAULT 0
);

-- Індекси
CREATE INDEX IF NOT EXISTS idx_saved_searches_name ON saved_searches(name);
CREATE INDEX IF NOT EXISTS idx_saved_searches_last_used ON saved_searches(last_used);
CREATE INDEX IF NOT EXISTS idx_saved_searches_use_count ON saved_searches(use_count);