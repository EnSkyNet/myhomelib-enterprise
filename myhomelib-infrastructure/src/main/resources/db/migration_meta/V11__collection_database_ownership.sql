-- Destructive deletion must distinguish MyHomeLib-owned SQLite databases from user files
-- registered via Attach. NULL is deliberately retained for legacy rows whose provenance cannot
-- be proven; application code treats UNKNOWN as non-destructive.
ALTER TABLE collections ADD COLUMN owns_database INTEGER;

-- This exact marker was written by the legacy/modern attach adapter in all prior builds.
UPDATE collections
SET owns_database = 0
WHERE owns_database IS NULL
  AND notes = 'Attached SQLite collection';

-- Legacy .hlc2 migration always created a fresh database under MyHomeLib control.
UPDATE collections
SET owns_database = 1
WHERE owns_database IS NULL
  AND notes LIKE 'Migrated from %';
