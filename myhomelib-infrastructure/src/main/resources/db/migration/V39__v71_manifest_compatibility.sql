-- v7.1: manifest/cache compatibility keys. Existing rows intentionally receive
-- empty/default values so they are invalidated once and rebuilt under v7.1.
ALTER TABLE catalog_manifests ADD COLUMN manifest_schema TEXT NOT NULL DEFAULT '';
ALTER TABLE catalog_manifests ADD COLUMN importer_version TEXT NOT NULL DEFAULT '';
ALTER TABLE catalog_manifests ADD COLUMN source_format TEXT NOT NULL DEFAULT '';
ALTER TABLE catalog_manifests ADD COLUMN normalization_version TEXT NOT NULL DEFAULT '';
ALTER TABLE catalog_manifests ADD COLUMN fingerprint_model TEXT NOT NULL DEFAULT '';
ALTER TABLE catalog_manifests ADD COLUMN fingerprint_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE catalog_manifests ADD COLUMN processing_flags TEXT NOT NULL DEFAULT '';
ALTER TABLE catalog_manifests ADD COLUMN features_enabled TEXT NOT NULL DEFAULT '';
ALTER TABLE catalog_manifests ADD COLUMN dataset_normalization_model TEXT;

CREATE INDEX IF NOT EXISTS idx_catalog_manifests_compat
    ON catalog_manifests(manifest_schema, importer_version, source_format, normalization_version,
                         fingerprint_model, fingerprint_version);
