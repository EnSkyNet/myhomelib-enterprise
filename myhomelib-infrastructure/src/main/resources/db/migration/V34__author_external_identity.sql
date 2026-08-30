-- v7: author names are lookup attributes, not globally unique identities.
DROP INDEX IF EXISTS idx_authors_unique_name;

ALTER TABLE authors ADD COLUMN nickname TEXT;
ALTER TABLE authors ADD COLUMN display_name TEXT;
ALTER TABLE authors ADD COLUMN disambiguation TEXT;

-- Exact hot-path lookup used only when a source does not provide a person identity.
CREATE INDEX IF NOT EXISTS idx_authors_name_lookup
    ON authors(first_name, middle_name, last_name);

CREATE TABLE IF NOT EXISTS author_identities (
    author_id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    scheme TEXT NOT NULL,
    external_id TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_id, scheme, external_id),
    FOREIGN KEY (author_id) REFERENCES authors(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_author_identities_author ON author_identities(author_id);
