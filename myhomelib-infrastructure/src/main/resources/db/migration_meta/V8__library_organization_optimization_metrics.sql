ALTER TABLE library_organization_items ADD COLUMN optimization_profile TEXT NOT NULL DEFAULT '';
ALTER TABLE library_organization_items ADD COLUMN size_before INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library_organization_items ADD COLUMN size_after INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library_organization_items ADD COLUMN bytes_saved INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library_organization_items ADD COLUMN percent_saved REAL NOT NULL DEFAULT 0;
ALTER TABLE library_organization_items ADD COLUMN optimization_summary_json TEXT NOT NULL DEFAULT '{}';
