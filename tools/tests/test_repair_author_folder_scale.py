"""Bounded SQL and atomicity checks for large author-folder repair plans."""
import importlib.util
import sqlite3
import tempfile
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / 'repair-author-folder.py'
spec = importlib.util.spec_from_file_location('author_repair_scale', SOURCE)
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class RepairScaleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.connection = sqlite3.connect(':memory:')
        self.addCleanup(self.connection.close)
        self.connection.executescript('''
            CREATE TABLE authors (id TEXT PRIMARY KEY, first_name TEXT,
                 middle_name TEXT, last_name TEXT);
            CREATE TABLE book_authors (book_id TEXT, author_id TEXT);
            CREATE TABLE books (id TEXT PRIMARY KEY, collection_root TEXT,
                 folder TEXT, file_name TEXT, local INTEGER, deleted INTEGER);
            CREATE TABLE book_artifacts (artifact_id TEXT PRIMARY KEY,
                 collection_root TEXT, folder TEXT, file_name TEXT,
                 archive_name TEXT, local INTEGER);
        ''')
        self.source = 'Алёна_% Васильева'
        self.target = 'Васильева Алёна'
        self.connection.execute('INSERT INTO authors VALUES (?,?,?,?)',
                                ('a', 'Алёна', None, 'Васильева'))

    def fill(self, count=900):
        db = self.connection
        rows = [('book-' + str(i), str(self.root),
                 self.source + ('/Том 1' if i % 2 else ''), 'file.zip', 1, 0)
                for i in range(count)]
        db.executemany('INSERT INTO books VALUES (?,?,?,?,?,?)', rows)
        db.executemany('INSERT INTO book_artifacts VALUES (?,?,?,?,?,?)',
                       [('art-' + str(i), row[1], row[2], row[3], None, 1)
                        for i, row in enumerate(rows)])
        db.executemany('INSERT INTO book_authors VALUES (?,?)',
                       [(row[0], 'a') for row in rows])
        db.commit()
        return dict(root=str(self.root), source=self.source, target=self.target,
                    books=[row[0] for row in rows],
                    artifacts=['art-' + str(i) for i in range(count)])

    def test_prefix_lookup_handles_unicode_case_wildcards_and_can_use_index(self):
        db = self.connection
        db.execute('CREATE INDEX idx_books_folder_test ON books(folder)')
        entries = [self.source, self.source + '/Том 1',
                   self.source + '/_100% та інші',
                   self.source + '0', self.source + 'а',
                   self.source.upper(), 'Інший автор']
        db.executemany('INSERT INTO books VALUES (?,?,?,?,?,?)',
                       [(str(i), str(self.root), folder, 'x.fb2', 1, 0)
                        for i, folder in enumerate(entries)])
        found = [row[0] for row in repair.catalog_folder_rows(db, 'books', 'id', self.source)]
        self.assertEqual(set(found), {'0', '1', '2'})
        queryplan = list(db.execute('EXPLAIN QUERY PLAN SELECT id FROM books '
                     'WHERE folder COLLATE BINARY = ? '
                     'OR (folder COLLATE BINARY >= ? AND folder COLLATE BINARY < ?)',
                     (self.source, self.source + '/', self.source + '0')))
        self.assertTrue(any('INDEX' in row[3] for row in queryplan), queryplan)

    def test_authorship_is_verified_with_bounded_selects(self):
        plan = self.fill(950)
        statements = []
        self.connection.set_trace_callback(statements.append)
        try:
            repair.validate_book_authors(self.connection, plan['books'], self.target)
        finally:
            self.connection.set_trace_callback(None)
        selects = [s for s in statements if 'FROM book_authors ba JOIN authors a' in s]
        self.assertEqual(len(selects), 3)
        self.assertLessEqual(max(s.count("'book-") for s in selects), 400)

    def test_wrong_author_in_later_batch_is_rejected(self):
        plan = self.fill(950)
        self.connection.execute('DELETE FROM book_authors WHERE book_id=?', ('book-901',))
        with self.assertRaisesRegex(ValueError, 'не пов’язана'):
            repair.validate_book_authors(self.connection, plan['books'], self.target)

    def test_bulk_sql_updates_exactly_selected_rows_and_nested_suffix(self):
        plan = self.fill(950)
        # Same textual folder exists in a distinct collection, but must not move.
        self.connection.execute('INSERT INTO books VALUES (?,?,?,?,?,?)',
                                ('foreign', '/different/collection', self.source,
                                 'foreign.zip', 1, 0))
        queries = []
        self.connection.set_trace_callback(queries.append)
        try:
            with self.connection:
                repair.sync_plan(self.connection, plan)
        finally:
            self.connection.set_trace_callback(None)
        updates = [s for s in queries if s.lstrip().upper().startswith('UPDATE ')]
        self.assertEqual(len(updates), 6)  # 3 chunks for each of 2 tables
        self.assertEqual(self.connection.execute('SELECT folder FROM books WHERE id=?',
                                                  ('book-1',)).fetchone()[0],
                         self.target + '/Том 1')
        self.assertEqual(self.connection.execute('SELECT folder FROM book_artifacts WHERE artifact_id=?',
                                                  ('art-2',)).fetchone()[0], self.target)
        self.assertEqual(self.connection.execute('SELECT folder FROM books WHERE id=?',
                                                  ('foreign',)).fetchone()[0], self.source)

    def test_missing_record_refuses_update_and_rolls_back_prior_chunks(self):
        plan = self.fill(900)
        self.connection.execute('DELETE FROM books WHERE id=?', ('book-899',))
        self.connection.commit()
        with self.assertRaisesRegex(ValueError, 'Каталог змінився'):
            with self.connection:
                repair.sync_plan(self.connection, plan)
        self.assertEqual(self.connection.execute(
            'SELECT folder FROM books WHERE id=?', ('book-0',)).fetchone()[0], self.source)
        self.assertEqual(self.connection.execute(
            'SELECT folder FROM book_artifacts WHERE artifact_id=?',
            ('art-0',)).fetchone()[0], self.source)

    def test_incorrect_root_refuses_updates(self):
        plan = self.fill(900)
        self.connection.execute('UPDATE books SET collection_root=? WHERE id=?',
                                ('/other', 'book-898'))
        self.connection.commit()
        with self.assertRaisesRegex(ValueError, 'Каталог змінився'):
            with self.connection:
                repair.sync_plan(self.connection, plan)
        self.assertEqual(self.connection.execute('SELECT COUNT(*) FROM books WHERE folder=?',
                                                  (self.source,)).fetchone()[0], 450)
        self.assertEqual(self.connection.execute(
            'SELECT COUNT(*) FROM books WHERE folder=?',
            (self.source + '/Том 1',)).fetchone()[0], 450)

    def test_trigger_failure_rolls_back_books_and_artifacts(self):
        plan = self.fill(900)
        self.connection.executescript('''
            CREATE TRIGGER stop_artifact_change BEFORE UPDATE OF folder
            ON book_artifacts BEGIN SELECT RAISE(ABORT, 'stopped by trigger'); END;
        ''')
        with self.assertRaisesRegex(sqlite3.IntegrityError, 'stopped by trigger'):
            with self.connection:
                repair.sync_plan(self.connection, plan)
        self.assertEqual(self.connection.execute(
            'SELECT folder FROM books WHERE id=?', ('book-0',)).fetchone()[0], self.source)
        self.assertEqual(self.connection.execute(
            'SELECT folder FROM book_artifacts WHERE artifact_id=?',
            ('art-0',)).fetchone()[0], self.source)


if __name__ == '__main__':
    unittest.main()
