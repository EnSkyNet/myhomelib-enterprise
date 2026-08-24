-- Stage 5: explicit user-visible reading history.
-- Keep this separate from reading_progress so clearing History never destroys resume position.
CREATE TABLE IF NOT EXISTS reading_history (
    book_id TEXT PRIMARY KEY,
    last_opened_at TEXT NOT NULL,
    open_count INTEGER NOT NULL DEFAULT 1 CHECK (open_count >= 1),
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_reading_history_last_opened_at
    ON reading_history(last_opened_at DESC);

-- Preserve the historical signal already present in older databases.
-- Normalize both `T` and space-separated legacy timestamps before choosing the newest value.
INSERT OR IGNORE INTO reading_history(book_id, last_opened_at, open_count)
SELECT h.book_id, MAX(datetime(h.opened_at)), 1
FROM (
    SELECT book_id, last_read_at AS opened_at
    FROM reading_stats
    WHERE last_read_at IS NOT NULL AND TRIM(last_read_at) <> ''
    UNION ALL
    SELECT book_id, updated_at AS opened_at
    FROM reading_progress
    WHERE updated_at IS NOT NULL AND TRIM(updated_at) <> ''
) h
JOIN books b ON b.id = h.book_id
WHERE datetime(h.opened_at) IS NOT NULL
GROUP BY h.book_id;
