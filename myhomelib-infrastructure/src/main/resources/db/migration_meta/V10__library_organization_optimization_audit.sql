ALTER TABLE library_organization_items ADD COLUMN target_standard TEXT NOT NULL DEFAULT '';
ALTER TABLE library_organization_items ADD COLUMN device_profile TEXT NOT NULL DEFAULT '';
ALTER TABLE library_organization_items ADD COLUMN compression_backend TEXT NOT NULL DEFAULT '';
ALTER TABLE library_organization_items ADD COLUMN validation_status TEXT NOT NULL DEFAULT '';
