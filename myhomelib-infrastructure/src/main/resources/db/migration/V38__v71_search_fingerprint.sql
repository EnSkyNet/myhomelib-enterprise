-- v7.1 selective Lucene indexing state. Fingerprints are versioned and must not be compared across models.
CREATE TABLE IF NOT EXISTS book_search_state (
    book_id TEXT PRIMARY KEY,
    fingerprint_model TEXT NOT NULL,
    fingerprint_version INTEGER NOT NULL,
    fingerprint TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_book_search_state_model
    ON book_search_state(fingerprint_model, fingerprint_version);
