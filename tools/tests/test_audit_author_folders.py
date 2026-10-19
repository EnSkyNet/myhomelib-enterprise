"""Standalone stdlib regression tests for read-only catalog-backed folder audit."""
import importlib.util
import sqlite3
from contextlib import closing, contextmanager
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'audit-author-folders.py'
spec = importlib.util.spec_from_file_location('audit_author_folders', SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


@contextmanager
def committed_connection(path):
    # sqlite3.Connection context manager commits but does not close the handle.
    with closing(sqlite3.connect(path)) as db:
        with db:
            yield db



class AuthorFolderAuditTest(unittest.TestCase):
    def test_catalog_verified_order_collision_and_uncertainty(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            db = root / 'library.db'
            with committed_connection(db) as conn:
                conn.execute('CREATE TABLE authors(first_name TEXT, middle_name TEXT, last_name TEXT)')
                conn.executemany('INSERT INTO authors VALUES(?,?,?)', [
                    ('Алёна', '', 'Васильева'),
                    ('Александр', 'Владимирович', 'Андреев'),
                    ('Іван', '', 'Шевченко'),
                    ('Павел', '', 'Иванов'),
                    ('Павел', '', 'Сидоров'),
                ])
            folders = root / 'authors'
            folders.mkdir()
            for name in ('Алёна Васильева', 'Андреев Александр Владимирович',
                         'Іван Шевченко', 'Шевченко Іван', 'Павел Иванов',
                         'Псевдонім', 'Александр Владимирович Андреев'):
                (folders / name).mkdir()
            mapping = module.catalog_names(db)
            rows = {row[1]: row for row in module.audit_folders(folders, mapping, module.common_names())}
            self.assertEqual(rows['Алёна Васильева'][:3],
                             ('КАНДИДАТ_ЗА_ПІБ_У_SQLITE', 'Алёна Васильева', 'Васильева Алёна'))
            self.assertEqual(rows['Іван Шевченко'][0], 'ЦІЛЬОВА_ПАПКА_ІСНУЄ')
            self.assertEqual(rows['Андреев Александр Владимирович'][0], 'ПІБ_ЗБІГАЄТЬСЯ_З_ІМЕНЕМ_В_SQLITE')
            self.assertEqual(rows['Александр Владимирович Андреев'][:3],
                             ('ЦІЛЬОВА_ПАПКА_ІСНУЄ', 'Александр Владимирович Андреев', 'Андреев Александр Владимирович'))
            self.assertEqual(rows['Псевдонім'][0], 'ОДНЕ_ІМЯ_АБО_ПСЕВДОНІМ')
            self.assertEqual(rows['Павел Иванов'][0], 'КАНДИДАТ_ЗА_ПІБ_У_SQLITE')
            self.assertFalse((folders / 'Васильева Алёна').exists())
            self.assertTrue(db.exists())

    def test_colliding_catalog_authors_are_not_confirmed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            db = root / 'library.db'
            with committed_connection(db) as conn:
                conn.execute('CREATE TABLE authors(first_name TEXT, middle_name TEXT, last_name TEXT)')
                conn.executemany('INSERT INTO authors VALUES(?,?,?)', [
                    ('Іван', 'Петрович', 'Коваль'),
                    ('Іван', 'Ігорович', 'Коваль'),
                ])
            folders = root / 'authors'
            folders.mkdir()
            (folders / 'Іван Коваль').mkdir()
            rows = module.audit_folders(folders, module.catalog_names(db), set())
            self.assertEqual(rows[0][0], 'КОНФЛІКТ_АВТОРІВ')

    def test_unrelated_author_in_sqlite_is_never_labelled_as_confirmed(self):
        # SQLite may know an author whose books are in another collection.
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            database = base / 'unrelated.sqlite'
            with committed_connection(database) as conn:
                conn.execute('CREATE TABLE authors(first_name TEXT, middle_name TEXT, last_name TEXT)')
                conn.execute('INSERT INTO authors VALUES (?, ?, ?)', ('Ганна', '', 'Коваль'))
            root = base / 'authors'
            root.mkdir()
            (root / 'Ганна Коваль').mkdir()
            row = module.audit_folders(root, module.catalog_names(database), set())[0]
            self.assertEqual(row[0], 'КАНДИДАТ_ЗА_ПІБ_У_SQLITE')
            self.assertNotIn('ПІДТВЕРДЖЕНО', row[0])

    def test_csv_formula_is_inert(self):
        self.assertEqual(module.safe_csv('=1+2'), "'=1+2")
        self.assertEqual(module.safe_csv(' @SUM(A1:A2)'), "' @SUM(A1:A2)")
        self.assertEqual(module.safe_csv('Іван Коваль'), 'Іван Коваль')


if __name__ == '__main__':
    unittest.main()
