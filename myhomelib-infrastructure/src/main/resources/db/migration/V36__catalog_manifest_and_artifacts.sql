-- v7: source manifest/cache and forward-compatible logical-book/artifact identity foundation.
CREATE TABLE IF NOT EXISTS catalog_manifests (
    source_key TEXT PRIMARY KEY,
    source_path TEXT,
    size_bytes INTEGER NOT NULL DEFAULT 0 CHECK(size_bytes >= 0),
    mtime_millis INTEGER NOT NULL DEFAULT 0,
    fingerprint TEXT,
    parser_version TEXT NOT NULL,
    record_count INTEGER,
    dataset_schema TEXT,
    last_parsed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS book_identities (
    book_id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    scheme TEXT NOT NULL,
    external_id TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(source_id, scheme, external_id),
    FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_book_identities_book ON book_identities(book_id);

CREATE TABLE IF NOT EXISTS book_artifacts (
    artifact_id TEXT PRIMARY KEY,
    book_id TEXT NOT NULL,
    source_id TEXT,
    artifact_name TEXT NOT NULL,
    media_type TEXT,
    file_format TEXT,
    file_name TEXT,
    archive_name TEXT,
    archive_entry TEXT,
    size_bytes INTEGER CHECK(size_bytes IS NULL OR size_bytes >= 0),
    sha256 TEXT,
    content_fingerprint TEXT,
    remote INTEGER NOT NULL DEFAULT 0 CHECK(remote IN (0,1)),
    local INTEGER NOT NULL DEFAULT 0 CHECK(local IN (0,1)),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_book_artifacts_book ON book_artifacts(book_id);
CREATE INDEX IF NOT EXISTS idx_book_artifacts_sha256 ON book_artifacts(sha256) WHERE sha256 IS NOT NULL;
