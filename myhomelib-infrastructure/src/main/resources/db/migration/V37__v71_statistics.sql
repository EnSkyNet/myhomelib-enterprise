-- v7.1 statistics lifecycle: distinguish active/local/remote/read/favourite/deleted/source counts.
ALTER TABLE library_statistics ADD COLUMN local_books_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library_statistics ADD COLUMN remote_books_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library_statistics ADD COLUMN read_books_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library_statistics ADD COLUMN unread_books_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library_statistics ADD COLUMN favorites_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library_statistics ADD COLUMN deleted_books_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library_statistics ADD COLUMN sources_count INTEGER NOT NULL DEFAULT 0;
