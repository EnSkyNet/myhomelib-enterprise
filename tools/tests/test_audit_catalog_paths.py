"""Self-contained test fixtures for read-only storage audit."""
import importlib.util
import sqlite3
from contextlib import closing, contextmanager
import tempfile
import unittest
from pathlib import Path

TOOL = Path(__file__).resolve().parents[1] / 'audit-catalog-paths.py'
spec = importlib.util.spec_from_file_location('catalog_audit', TOOL)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


@contextmanager
def committed_connection(path):
    # sqlite3.Connection context manager commits but does not close the handle.
    with closing(sqlite3.connect(path)) as db:
        with db:
            yield db



class StorageAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'library'
        self.root.mkdir()
        self.database = self.base / 'catalog.db'
        with committed_connection(self.database) as db:
            db.execute('CREATE TABLE books (id TEXT, collection_root TEXT, folder TEXT, file_name TEXT, archive_entry TEXT, local INTEGER, deleted INTEGER)')

    def insert(self, id, collection_root, folder, filename, entry=''):
        with committed_connection(self.database) as db:
            db.execute('INSERT INTO books VALUES (?, ?, ?, ?, ?, 1, 0)',
                       (id, collection_root, folder, filename, entry))

    def check(self):
        return module.audit(self.database, self.root, self.base / 'result.csv')

    def test_valid_and_missing_books_are_distinguished(self):
        (self.root / 'Author').mkdir()
        (self.root / 'Author' / 'present.fb2').write_bytes(b'original')
        self.insert('1', str(self.root), 'Author', 'present.fb2')
        self.insert('2', str(self.root), 'Author', 'missing.fb2')
        counts = self.check()
        self.assertEqual(counts['ФАЙЛ_ІСНУЄ'], 1)
        self.assertEqual(counts['ФАЙЛ_НЕ_ЗНАЙДЕНО'], 1)

    def test_shared_physical_archives_are_flagged_without_modification(self):
        (self.root / 'packed.zip').write_bytes(b'archive')
        self.insert('1', str(self.root), '', 'packed.zip', 'a.fb2')
        self.insert('2', str(self.root), '', 'packed.zip', 'b.fb2')
        counts = self.check()
        self.assertEqual(counts['СПІЛЬНИХ_ПОСИЛАНЬ'], 2)
        self.assertEqual((self.root / 'packed.zip').read_bytes(), b'archive')
        self.assertEqual(len((self.base / 'result.csv').read_text(encoding='utf-8-sig').splitlines()), 3)

    def test_root_mismatch_does_not_guess_path(self):
        self.insert('x', str(self.base / 'other'), '', 'book.fb2')
        counts = self.check()
        self.assertEqual(counts['ІНШИЙ_КОРІНЬ_КОЛЕКЦІЇ'], 1)

    def test_traversal_and_invalid_names_are_rejected(self):
        self.insert('x', str(self.root), '../elsewhere', 'private.fb2')
        counts = self.check()
        self.assertEqual(counts['НЕБЕЗПЕЧНИЙ_ШЛЯХ'], 1)

    def test_report_never_writes_inside_real_library(self):
        self.insert('x', str(self.root), '', 'missing.fb2')
        forbidden = self.root / 'accidental.csv'
        with self.assertRaisesRegex(ValueError, 'поза бібліотекою'):
            module.audit(self.database, self.root, forbidden)
        self.assertFalse(forbidden.exists())

    def test_csv_escapes_spreadsheet_formula(self):
        self.insert('=HYPERLINK(1)', str(self.root), '', 'missing.fb2')
        self.check()
        output = (self.base / 'result.csv').read_text(encoding='utf-8-sig')
        self.assertIn("'=HYPERLINK(1)", output)


if __name__ == '__main__':
    unittest.main()
