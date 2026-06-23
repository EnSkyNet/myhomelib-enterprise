-- Full-text search is handled by Lucene. Keep SQLite focused on persistence.
DROP TRIGGER IF EXISTS books_fts_insert;
DROP TRIGGER IF EXISTS books_fts_update;
DROP TRIGGER IF EXISTS books_fts_delete;
DROP TABLE IF EXISTS books_fts;
