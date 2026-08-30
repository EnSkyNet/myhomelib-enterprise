-- v7: durable remote sync state. applied_version is advanced only after a successful apply/index commit.
ALTER TABLE catalog_sources ADD COLUMN profile_type TEXT;
ALTER TABLE catalog_sources ADD COLUMN applied_version TEXT;
ALTER TABLE catalog_sources ADD COLUMN remote_version TEXT;
ALTER TABLE catalog_sources ADD COLUMN etag TEXT;
ALTER TABLE catalog_sources ADD COLUMN last_modified TEXT;
ALTER TABLE catalog_sources ADD COLUMN sha256 TEXT;
ALTER TABLE catalog_sources ADD COLUMN dataset_schema TEXT;
ALTER TABLE catalog_sources ADD COLUMN last_checked_at TEXT;
ALTER TABLE catalog_sources ADD COLUMN last_downloaded_at TEXT;
ALTER TABLE catalog_sources ADD COLUMN last_applied_at TEXT;
ALTER TABLE catalog_sources ADD COLUMN last_error TEXT;
ALTER TABLE catalog_sources ADD COLUMN last_error_at TEXT;

CREATE INDEX IF NOT EXISTS idx_catalog_sources_profile ON catalog_sources(profile_type);
