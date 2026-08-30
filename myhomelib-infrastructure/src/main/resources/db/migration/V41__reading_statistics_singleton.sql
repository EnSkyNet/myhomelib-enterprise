-- v7.1: reading statistics is an aggregate snapshot, not an event log.
-- Older code used INSERT OR REPLACE without a UNIQUE(book_id) constraint, so
-- every save could append another row. Keep the newest snapshot per book and
-- make the intended singleton contract enforceable by SQLite.
DELETE FROM reading_stats AS current
WHERE EXISTS (
    SELECT 1
    FROM reading_stats AS newer
    WHERE newer.book_id = current.book_id
      AND (
          newer.last_read_at > current.last_read_at
          OR (newer.last_read_at = current.last_read_at AND newer.id > current.id)
      )
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_reading_stats_book_id
    ON reading_stats(book_id);
