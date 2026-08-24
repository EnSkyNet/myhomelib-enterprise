-- Stable catalog identity and fields used by original MyHomeLib/INPX.
ALTER TABLE books ADD COLUMN lib_id TEXT;
ALTER TABLE books ADD COLUMN library_rate INTEGER DEFAULT 0;
ALTER TABLE books ADD COLUMN translators TEXT;
ALTER TABLE books ADD COLUMN city TEXT;
ALTER TABLE books ADD COLUMN source_url TEXT;
CREATE INDEX IF NOT EXISTS idx_books_lib_id ON books(lib_id);
CREATE INDEX IF NOT EXISTS idx_books_library_rate ON books(library_rate);
