-- Optimize language facets and language-filtered title browsing without exposing
-- the index to unrelated title-only scans. The normalized expression intentionally
-- matches BookFilterSqlAdapter exactly. Keeping the index compact (language + title)
-- limits import/write amplification; id/local/year are filtered from the table and
-- the bounded LIMIT path remains fast in measured 500k catalogues.
CREATE INDEX IF NOT EXISTS idx_books_active_language_title
ON books (
    LOWER(TRIM(COALESCE(language, ''))),
    title
)
WHERE deleted = 0
  AND LOWER(TRIM(COALESCE(language, ''))) <> '';
