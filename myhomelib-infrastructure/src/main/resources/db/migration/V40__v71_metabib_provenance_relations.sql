-- v7.1: source provenance/relations foundation. The normalized book remains the
-- operational projection; versioned source facts are retained independently.
CREATE TABLE IF NOT EXISTS catalog_dataset_metadata (
    source_id TEXT PRIMARY KEY,
    dataset_id TEXT,
    dataset_schema TEXT,
    record_schema TEXT,
    library TEXT,
    generator_name TEXT,
    generator_version TEXT,
    normalization_model TEXT,
    database_id TEXT,
    database_format TEXT,
    dump_date TEXT,
    dump_checksum TEXT,
    database_dumps_json TEXT,
    ordering_json TEXT,
    processing_json TEXT,
    archives_json TEXT,
    features_json TEXT,
    raw_header_json TEXT,
    imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(source_id) REFERENCES catalog_sources(source_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS catalog_record_provenance (
    source_id TEXT NOT NULL,
    book_id TEXT NOT NULL,
    dataset_id TEXT,
    source_book_id TEXT NOT NULL,
    record_schema TEXT,
    locator_kind TEXT,
    locator_source TEXT,
    locator_value TEXT,
    raw_record_json TEXT,
    observations_json TEXT,
    claims_json TEXT,
    identities_json TEXT,
    artifacts_json TEXT,
    imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(source_id, book_id),
    FOREIGN KEY(source_id) REFERENCES catalog_sources(source_id) ON DELETE CASCADE,
    FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_catalog_record_provenance_dataset
    ON catalog_record_provenance(dataset_id);

CREATE TABLE IF NOT EXISTS book_source_relations (
    relation_id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL,
    book_id TEXT NOT NULL,
    relation_index INTEGER NOT NULL CHECK(relation_index >= 0),
    relation_type TEXT NOT NULL,
    observation_id TEXT,
    target_scheme TEXT,
    target_value TEXT,
    event_id TEXT,
    event_time TEXT,
    participants_json TEXT,
    raw_relation_json TEXT,
    imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(source_id) REFERENCES catalog_sources(source_id) ON DELETE CASCADE,
    FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE,
    UNIQUE(source_id, book_id, relation_index)
);
CREATE INDEX IF NOT EXISTS idx_book_source_relations_book ON book_source_relations(book_id);
CREATE INDEX IF NOT EXISTS idx_book_source_relations_target
    ON book_source_relations(target_scheme, target_value)
    WHERE target_scheme IS NOT NULL AND target_value IS NOT NULL;

CREATE TABLE IF NOT EXISTS book_artifact_metadata (
    artifact_id TEXT NOT NULL,
    metadata_key TEXT NOT NULL,
    metadata_value TEXT,
    PRIMARY KEY(artifact_id, metadata_key),
    FOREIGN KEY(artifact_id) REFERENCES book_artifacts(artifact_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS artifact_occurrences (
    occurrence_id TEXT PRIMARY KEY,
    artifact_id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    book_id TEXT NOT NULL,
    occurrence_index INTEGER NOT NULL CHECK(occurrence_index >= 0),
    archive_name TEXT NOT NULL,
    entry_name TEXT NOT NULL,
    archive_index INTEGER CHECK(archive_index IS NULL OR archive_index >= 0),
    compressed_size INTEGER CHECK(compressed_size IS NULL OR compressed_size >= 0),
    uncompressed_size INTEGER CHECK(uncompressed_size IS NULL OR uncompressed_size >= 0),
    modified_at TEXT,
    FOREIGN KEY(artifact_id) REFERENCES book_artifacts(artifact_id) ON DELETE CASCADE,
    FOREIGN KEY(source_id) REFERENCES catalog_sources(source_id) ON DELETE CASCADE,
    FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE,
    UNIQUE(artifact_id, occurrence_index)
);
CREATE INDEX IF NOT EXISTS idx_artifact_occurrences_archive
    ON artifact_occurrences(archive_name, archive_index);
