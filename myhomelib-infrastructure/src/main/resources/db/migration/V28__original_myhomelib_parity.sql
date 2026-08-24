-- Original MyHomeLib feature parity fields
ALTER TABLE books ADD COLUMN year INTEGER;
ALTER TABLE authors ADD COLUMN annotation TEXT;
CREATE INDEX IF NOT EXISTS idx_books_year ON books(year);
CREATE INDEX IF NOT EXISTS idx_books_created_at ON books(created_at);
CREATE INDEX IF NOT EXISTS idx_books_rate ON books(rate);
