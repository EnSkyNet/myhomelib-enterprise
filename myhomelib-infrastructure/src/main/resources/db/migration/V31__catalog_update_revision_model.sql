-- Stage 6: reliable online catalog revision model.
-- Catalog-owned state is intentionally separated from books/user data so a remote UPSERT
-- cannot destroy local storage, rating/progress/review or bookmarks.

CREATE TABLE IF NOT EXISTS catalog_sources (
    source_id TEXT PRIMARY KEY,
    source_key TEXT NOT NULL UNIQUE,
    source_location TEXT,
    source_revision INTEGER NOT NULL DEFAULT 1 CHECK (source_revision >= 1),
    source_fingerprint TEXT NOT NULL,
    first_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_synced_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS followed_authors (
    author_id TEXT PRIMARY KEY,
    followed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (author_id) REFERENCES authors(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS catalog_book_state (
    book_id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL,
    source_book_key TEXT NOT NULL DEFAULT '',
    catalog_revision INTEGER NOT NULL CHECK (catalog_revision >= 1),
    catalog_fingerprint TEXT NOT NULL,
    catalog_file_name TEXT NOT NULL DEFAULT '',
    catalog_folder TEXT NOT NULL DEFAULT '',
    catalog_archive_entry TEXT NOT NULL DEFAULT '',
    catalog_file_size INTEGER NOT NULL DEFAULT 0 CHECK (catalog_file_size >= 0),
    downloaded_revision INTEGER,
    downloaded_fingerprint TEXT,
    downloaded_baseline_at TEXT,
    first_seen_revision INTEGER NOT NULL CHECK (first_seen_revision >= 1),
    last_seen_revision INTEGER NOT NULL CHECK (last_seen_revision >= 1),
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE,
    FOREIGN KEY (source_id) REFERENCES catalog_sources(source_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_catalog_book_state_source
    ON catalog_book_state(source_id, last_seen_revision);
CREATE INDEX IF NOT EXISTS idx_catalog_book_state_downloaded
    ON catalog_book_state(downloaded_revision) WHERE downloaded_revision IS NOT NULL;

CREATE TABLE IF NOT EXISTS catalog_update_events (
    book_id TEXT NOT NULL,
    update_type TEXT NOT NULL CHECK (update_type IN ('NEW_BY_FOLLOWED_AUTHOR', 'UPDATED_DOWNLOADED_BOOK')),
    source_id TEXT NOT NULL,
    detected_revision INTEGER NOT NULL CHECK (detected_revision >= 1),
    catalog_fingerprint TEXT NOT NULL,
    detected_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at TEXT,
    PRIMARY KEY (book_id, update_type),
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE,
    FOREIGN KEY (source_id) REFERENCES catalog_sources(source_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_catalog_update_events_pending
    ON catalog_update_events(acknowledged_at, detected_at DESC);
