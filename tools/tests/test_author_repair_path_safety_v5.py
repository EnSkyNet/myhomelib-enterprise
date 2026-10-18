"""Cross-platform fail-closed path tests; uses only throw-away SQLite and folders."""
import importlib.util
import sqlite3
import tempfile
import unicodedata
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('author_repair_v5', TOOLS / 'repair-author-folder.py')
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class AuthorRepairPathSafetyV5Test(unittest.TestCase):
    def test_windows_device_names_must_be_rejected_on_every_host(self):
        for name in ('CON', 'con.txt', 'PrN.fb2', 'AUX', 'NUL.zip', 'COM1', 'com9.epub',
                     'LPT1', 'lpt9.fb2', 'CONIN$', 'CONOUT$', 'COM\N{SUPERSCRIPT ONE}.txt'):
            with self.subTest(name=name):
                self.assertFalse(repair.valid_component(name), name)
        for name in ('Collection', 'COM10', 'CONNIE', 'LPT0', 'Автор Ім’я.fb2'):
            with self.subTest(name=name):
                self.assertTrue(repair.valid_component(name), name)

    def test_canonically_equivalent_existing_target_blocks_preview(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            root = base / 'library'
            root.mkdir()
            old = 'Алёна Васильева'
            (root / old).mkdir()
            (root / old / 'one.fb2').write_bytes(b'one')
            other = unicodedata.normalize('NFD', 'Васильева Алёна')
            self.assertNotEqual(other, 'Васильева Алёна')
            (root / other).mkdir()
            db = sqlite3.connect(':memory:')
            try:
                db.executescript('''
                    CREATE TABLE authors(id TEXT PRIMARY KEY, first_name TEXT, middle_name TEXT, last_name TEXT);
                    CREATE TABLE books(id TEXT PRIMARY KEY, collection_root TEXT, folder TEXT, file_name TEXT, local INT, deleted INT);
                    CREATE TABLE book_artifacts(artifact_id TEXT PRIMARY KEY, collection_root TEXT, folder TEXT, file_name TEXT, archive_name TEXT, local INT);
                    CREATE TABLE book_authors(book_id TEXT, author_id TEXT);
                ''')
                db.execute('INSERT INTO authors VALUES (?, ?, ?, ?)', ('a', 'Алёна', '', 'Васильева'))
                db.execute('INSERT INTO books VALUES (?, ?, ?, ?, ?, ?)', ('b', str(root), old, 'one.fb2', 1, 0))
                db.execute('INSERT INTO book_artifacts VALUES (?, ?, ?, ?, ?, ?)', ('ar', str(root), old, 'one.fb2', '', 1))
                db.execute('INSERT INTO book_authors VALUES (?, ?)', ('b', 'a'))
                with self.assertRaisesRegex(ValueError, 'така сама|саму назву'):
                    repair.build_plan(db, root, old)
            finally:
                db.close()

    def test_symlink_in_parent_of_library_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            actual = base / 'physical'
            actual.mkdir()
            (actual / 'library').mkdir()
            alias = base / 'alias'
            try:
                alias.symlink_to(actual, target_is_directory=True)
            except (OSError, NotImplementedError):
                self.skipTest('Symlinks are not supported on this filesystem')
            root = alias / 'library'
            self.assertFalse(root.is_symlink())
            self.assertTrue(root.is_dir())
            with self.assertRaisesRegex(ValueError, 'символічн'):
                repair.require_no_symlink_components(root, 'Library root')

    def test_symlinked_backup_parent_is_rejected_before_mutation(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            outside = base / 'outside'
            outside.mkdir()
            alias = base / 'backup-alias'
            try:
                alias.symlink_to(outside, target_is_directory=True)
            except (OSError, NotImplementedError):
                self.skipTest('Symlinks are not supported on this filesystem')
            with self.assertRaisesRegex(ValueError, 'символічн'):
                repair.require_no_symlink_components(alias / 'copy.db', 'SQLite backup')


if __name__ == '__main__':
    unittest.main()
