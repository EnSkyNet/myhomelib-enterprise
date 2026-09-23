-- Normalize the derived series identity table and add indexes matching the
-- case/whitespace-insensitive semantics used by browsing and BookQueryBuilder.
-- books.series remains the source of truth; series supplies stable IDs for UI navigation.

-- Keep one stable identity for any legacy duplicates that differ only by case/spacing.
DELETE FROM series
WHERE rowid NOT IN (
    SELECT MIN(rowid)
    FROM series
    WHERE name IS NOT NULL AND TRIM(name) <> ''
    GROUP BY LOWER(TRIM(name))
)
OR name IS NULL
OR TRIM(name) = '';

UPDATE series
SET name = TRIM(name)
WHERE name <> TRIM(name);

-- Prevent the lazy repair path from creating duplicate identities under races/repeated opens.
CREATE UNIQUE INDEX IF NOT EXISTS ux_series_name_normalized
ON series(LOWER(TRIM(name)));

-- The ordinary books(series) index cannot serve LOWER(TRIM(series)) joins. This expression
-- index makes series navigation/querying scale on large catalogues while excluding tombstones.
CREATE INDEX IF NOT EXISTS idx_books_active_series_normalized
ON books(LOWER(TRIM(series)), id)
WHERE deleted = 0 AND series IS NOT NULL AND TRIM(series) <> '';
