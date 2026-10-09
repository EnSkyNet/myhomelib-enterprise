-- V30: Kobo native sync durable incremental state. Secrets are intentionally NOT stored here.
CREATE TABLE IF NOT EXISTS kobo_sync_meta (
    device_id TEXT PRIMARY KEY,
    cursor INTEGER NOT NULL DEFAULT 0,
    generation INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS kobo_sync_books (
    device_id TEXT NOT NULL,
    book_id TEXT NOT NULL,
    revision TEXT NOT NULL,
    present INTEGER NOT NULL DEFAULT 1 CHECK (present IN (0,1)),
    change_kind TEXT NOT NULL DEFAULT 'ADD' CHECK (change_kind IN ('ADD','UPDATE','REMOVE')),
    cursor INTEGER NOT NULL,
    seen_generation INTEGER NOT NULL DEFAULT 0,
    title TEXT NOT NULL DEFAULT '',
    authors TEXT NOT NULL DEFAULT '',
    series TEXT NOT NULL DEFAULT '',
    sequence_number INTEGER,
    artifact_id TEXT NOT NULL DEFAULT '',
    artifact_revision TEXT NOT NULL DEFAULT '',
    format TEXT NOT NULL DEFAULT '',
    download_name TEXT NOT NULL DEFAULT '',
    modified_at TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (device_id, book_id),
    FOREIGN KEY (device_id) REFERENCES kobo_sync_meta(device_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_kobo_sync_books_cursor
    ON kobo_sync_books(device_id, cursor);

CREATE TABLE IF NOT EXISTS kobo_progress_versions (
    device_id TEXT NOT NULL,
    book_id TEXT NOT NULL,
    canonical_revision TEXT NOT NULL DEFAULT '',
    version INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, book_id),
    FOREIGN KEY (device_id) REFERENCES kobo_sync_meta(device_id) ON DELETE CASCADE
);
