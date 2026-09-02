-- Distinguish a remote-only book from a local file that became unavailable (NAS/offline disk/etc.).
ALTER TABLE books ADD COLUMN missing_since TEXT;
CREATE INDEX IF NOT EXISTS idx_books_missing_since ON books(missing_since) WHERE missing_since IS NOT NULL;
