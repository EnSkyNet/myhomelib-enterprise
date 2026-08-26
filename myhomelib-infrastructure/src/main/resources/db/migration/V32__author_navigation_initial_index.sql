-- Scalable author navigation for large catalogues.
CREATE INDEX IF NOT EXISTS idx_authors_navigation_initial
ON authors (
    SUBSTR((
        CASE
            WHEN TRIM(COALESCE(last_name, '')) <> '' THEN TRIM(last_name)
            WHEN TRIM(COALESCE(first_name, '')) <> '' THEN TRIM(first_name)
            ELSE TRIM(COALESCE(middle_name, ''))
        END
    ), 1, 1)
);
