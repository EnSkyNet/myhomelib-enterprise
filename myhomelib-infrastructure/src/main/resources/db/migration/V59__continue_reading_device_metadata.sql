-- MHL-410: remember which device most recently wrote reading progress so desktop/web can expose it.
ALTER TABLE reading_progress ADD COLUMN last_device TEXT NOT NULL DEFAULT 'desktop';

UPDATE reading_progress
   SET last_device = 'desktop'
 WHERE last_device IS NULL OR TRIM(last_device) = '';

CREATE INDEX IF NOT EXISTS idx_reading_progress_active_recent
    ON reading_progress(percent, updated_at DESC, book_id);
