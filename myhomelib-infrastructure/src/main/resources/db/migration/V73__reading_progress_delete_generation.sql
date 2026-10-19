-- V73: avoid an ABA revision collision after a reading-progress row is deleted.
-- A Reader that observed revision N must not be able to overwrite a freshly
-- recreated row with the same book_id and the same revision N.
-- The ledger is retained only while the corresponding progress row is absent.
CREATE TABLE IF NOT EXISTS reading_progress_deleted_generations (
    book_id TEXT PRIMARY KEY,
    next_revision INTEGER NOT NULL CHECK(next_revision > 0)
);

CREATE TRIGGER reading_progress_record_deleted_generation
AFTER DELETE ON reading_progress
BEGIN
    INSERT INTO reading_progress_deleted_generations(book_id, next_revision)
    VALUES(OLD.book_id, OLD.revision + 1)
    ON CONFLICT(book_id) DO UPDATE SET
        next_revision = MAX(reading_progress_deleted_generations.next_revision, excluded.next_revision);
END;

CREATE TRIGGER reading_progress_apply_deleted_generation
AFTER INSERT ON reading_progress
WHEN EXISTS(SELECT 1 FROM reading_progress_deleted_generations WHERE book_id = NEW.book_id)
BEGIN
    UPDATE reading_progress
       SET revision = MAX(revision, (SELECT next_revision
                                       FROM reading_progress_deleted_generations
                                      WHERE book_id = NEW.book_id))
     WHERE book_id = NEW.book_id;
    DELETE FROM reading_progress_deleted_generations WHERE book_id = NEW.book_id;
END;
