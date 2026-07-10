ALTER TABLE library_statistics ADD COLUMN format_version INTEGER DEFAULT 1;

-- або створити окрему таблицю
CREATE TABLE IF NOT EXISTS library_metadata (
                                                key TEXT PRIMARY KEY,
                                                value TEXT
);

INSERT OR IGNORE INTO library_metadata (key, value) VALUES ('format_version', '1');
INSERT OR IGNORE INTO library_metadata (key, value) VALUES ('created_at', datetime('now'));