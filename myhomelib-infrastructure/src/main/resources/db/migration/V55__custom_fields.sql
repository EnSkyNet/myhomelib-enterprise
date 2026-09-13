CREATE TABLE IF NOT EXISTS custom_field_definitions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL COLLATE NOCASE UNIQUE,
    field_type TEXT NOT NULL CHECK(field_type IN ('TEXT','NUMBER','BOOL','DATE','ENUM')),
    enum_options_json TEXT NOT NULL DEFAULT '[]',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS custom_field_values (
    book_id TEXT NOT NULL,
    definition_id INTEGER NOT NULL,
    value_text TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(book_id, definition_id),
    FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE,
    FOREIGN KEY(definition_id) REFERENCES custom_field_definitions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_custom_field_values_definition_value
    ON custom_field_values(definition_id, value_text);
CREATE INDEX IF NOT EXISTS idx_custom_field_values_book
    ON custom_field_values(book_id);
