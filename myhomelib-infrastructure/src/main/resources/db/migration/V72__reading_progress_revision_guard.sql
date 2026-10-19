-- V72: monotonic generation for comparing full reading-progress rows across writers.
-- Existing entries begin at revision 0. Never rebuild the row, changing book_id or anchors.
-- Callers using explicit revision must atomically compare the expected generation.
ALTER TABLE reading_progress ADD COLUMN revision INTEGER NOT NULL DEFAULT 0 CHECK(revision >= 0);
