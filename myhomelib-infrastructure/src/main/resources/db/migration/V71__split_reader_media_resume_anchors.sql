-- V71: retain the last-active legacy anchor for sync/shelves, while keeping independent
-- text and audio resume anchors. Migrate only the observable last-active position;
-- a position already replaced by pre-V71 software cannot be reconstructed.
-- Flyway runs SQLite DDL/updates as one migration; do not rebuild existing tables.
ALTER TABLE reading_progress ADD COLUMN text_anchor_id TEXT;
ALTER TABLE reading_progress ADD COLUMN audio_anchor_id TEXT;

UPDATE reading_progress
   SET audio_anchor_id = anchor_id
 WHERE anchor_id LIKE 'audio:%' AND anchor_id IS NOT NULL;

UPDATE reading_progress
   SET text_anchor_id = anchor_id
 WHERE anchor_id IS NOT NULL AND anchor_id <> '' AND anchor_id NOT LIKE 'audio:%';
