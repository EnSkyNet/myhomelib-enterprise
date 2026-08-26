-- Stage 8/9: complete the V18 denormalized columns for existing databases.
-- New/updated books are written with these values directly by the repository/batch writer,
-- avoiding per-row trigger overhead during very large INPX imports.

UPDATE books
SET format = CASE
    WHEN LOWER(COALESCE(file_name, '')) LIKE '%.fb2.zip' THEN 'FB2ZIP'
    WHEN LOWER(COALESCE(file_name, '')) LIKE '%.fb2' THEN 'FB2'
    WHEN LOWER(COALESCE(file_name, '')) LIKE '%.epub' THEN 'EPUB'
    WHEN LOWER(COALESCE(file_name, '')) LIKE '%.pdf' THEN 'PDF'
    WHEN LOWER(COALESCE(file_name, '')) LIKE '%.mobi' THEN 'MOBI'
    WHEN LOWER(COALESCE(file_name, '')) LIKE '%.inpx' THEN 'INPX'
    WHEN LOWER(COALESCE(file_name, '')) LIKE '%.zip' THEN 'ZIP'
    ELSE 'UNKNOWN'
END
WHERE format IS NULL OR TRIM(format) = '';

UPDATE books
SET author_sort = COALESCE((
    SELECT MIN(LOWER(TRIM(
        COALESCE(a.last_name, '') || ' ' || COALESCE(a.first_name, '') || ' ' || COALESCE(a.middle_name, '')
    )))
    FROM book_authors ba
    JOIN authors a ON a.id = ba.author_id
    WHERE ba.book_id = books.id
), '')
WHERE author_sort IS NULL OR TRIM(author_sort) = '';

-- Author edits are rare and happen outside the bulk import hot path; keep denormalized order correct.
CREATE TRIGGER IF NOT EXISTS trg_authors_author_sort_update
AFTER UPDATE OF first_name, last_name, middle_name ON authors
BEGIN
    UPDATE books
    SET author_sort = COALESCE((
        SELECT MIN(LOWER(TRIM(COALESCE(a.last_name, '') || ' ' || COALESCE(a.first_name, '') || ' ' || COALESCE(a.middle_name, ''))))
        FROM book_authors ba JOIN authors a ON a.id = ba.author_id
        WHERE ba.book_id = books.id
    ), '')
    WHERE id IN (SELECT book_id FROM book_authors WHERE author_id = NEW.id);
END;
