-- V28: collection-scoped Reader profiles with explicit precedence handled in Application.
-- Keep V42 reader_book_preferences intact for backward compatibility; BOOK rows are copied.
CREATE TABLE IF NOT EXISTS reader_preference_overrides (
    scope_type TEXT NOT NULL CHECK (scope_type IN ('BOOK','SERIES','FORMAT')),
    scope_key TEXT NOT NULL,
    preferences_json TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope_type, scope_key)
);

INSERT OR IGNORE INTO reader_preference_overrides(scope_type, scope_key, preferences_json, updated_at)
SELECT 'BOOK', book_id, preferences_json, updated_at
FROM reader_book_preferences;

-- Generic scoped rows cannot have a direct FK to books. Preserve V42 book-delete semantics explicitly.
CREATE TRIGGER IF NOT EXISTS trg_reader_preference_overrides_book_delete
AFTER DELETE ON books
BEGIN
    DELETE FROM reader_preference_overrides
     WHERE scope_type='BOOK' AND scope_key=OLD.id;
END;
