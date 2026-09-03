-- Cover the hot author-navigation cursor path: initial + deterministic name order.
-- The existing V32 index remains useful for initial-only lookups; this index avoids
-- scanning/sorting the entire initial before a bounded keyset page can be returned.
CREATE INDEX IF NOT EXISTS idx_authors_navigation_page
ON authors (
    SUBSTR((
        CASE
            WHEN TRIM(COALESCE(last_name, '')) <> '' THEN TRIM(last_name)
            WHEN TRIM(COALESCE(first_name, '')) <> '' THEN TRIM(first_name)
            ELSE TRIM(COALESCE(middle_name, ''))
        END
    ), 1, 1),
    COALESCE(last_name, '') COLLATE NOCASE,
    COALESCE(first_name, '') COLLATE NOCASE,
    COALESCE(middle_name, '') COLLATE NOCASE,
    id
);
