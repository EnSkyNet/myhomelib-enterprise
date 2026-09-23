-- Remove technical pseudo-authors created by historical importers for missing author metadata.
-- These values were implementation sentinels, not bibliographic people. Keeping them in the
-- relational graph leaks Ukrainian UI text into other locales and makes author navigation/counts
-- incorrect. Physical online-book locations are stored on books and remain untouched.

DELETE FROM book_authors
 WHERE author_id IN (
     SELECT id
       FROM authors
      WHERE COALESCE(TRIM(first_name), '') = ''
        AND COALESCE(TRIM(middle_name), '') = ''
        AND TRIM(COALESCE(last_name, '')) IN ('Без автора', 'Невідомий автор', 'Невідомий Автор')
 );

DELETE FROM authors
 WHERE COALESCE(TRIM(first_name), '') = ''
   AND COALESCE(TRIM(middle_name), '') = ''
   AND TRIM(COALESCE(last_name, '')) IN ('Без автора', 'Невідомий автор', 'Невідомий Автор')
   AND NOT EXISTS (SELECT 1 FROM book_authors ba WHERE ba.author_id = authors.id);


-- Historical denormalized sort projections used the same pseudo-author text.
-- Clear only exact sentinel values; real bibliographic sort keys are preserved.
UPDATE books
   SET author_sort = COALESCE((
       SELECT MIN(LOWER(TRIM(
           COALESCE(a.last_name, '') || ' ' || COALESCE(a.first_name, '') || ' ' || COALESCE(a.middle_name, '')
       )))
         FROM book_authors ba
         JOIN authors a ON a.id = ba.author_id
        WHERE ba.book_id = books.id
   ), '')
 WHERE TRIM(COALESCE(author_sort, '')) IN (
       'Без автора', 'без автора',
       'Невідомий автор', 'Невідомий Автор', 'невідомий автор'
 );
