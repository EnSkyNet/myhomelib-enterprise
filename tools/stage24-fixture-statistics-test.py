#!/usr/bin/env python3
"""Regression checks for deterministic statistics on the real migrated fixture schema."""
from contextlib import closing
import importlib.util
import sqlite3
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch


def load_baseline():
    path = Path(__file__).with_name('stage24-performance-baseline.py')
    spec = importlib.util.spec_from_file_location('stage24_baseline', path)
    module = importlib.util.module_from_spec(spec)
    # These tests never collect RSS or call benchmark_size. The Linux-only resource
    # dependency is unavailable on native Windows; do not fabricate memory metrics.
    if sys.platform == 'win32':
        with patch.dict(sys.modules, {'resource': types.ModuleType('resource')}):
            spec.loader.exec_module(module)
    else:
        spec.loader.exec_module(module)
    return module


baseline = load_baseline()


class FixtureStatisticsTest(unittest.TestCase):
    def test_statistics_survive_reopen_and_match_fixture_cardinality(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / 'synthetic.db'
            with closing(sqlite3.connect(db)) as conn:
                baseline.configure_fixture(conn)
                baseline.migrate(conn)
                baseline.synthesize(conn, 3000)
            with closing(sqlite3.connect(db)) as conn:
                for table, count in [('books', 3000), ('authors', 1000), ('book_authors', 3000)]:
                    rows = conn.execute('SELECT stat FROM sqlite_stat1 WHERE idx=?', (f'sqlite_autoindex_{table}_1',)).fetchall()
                    self.assertTrue(rows, f'Missing planner statistics for {table}')
                    self.assertTrue(all(int(row[0].split()[0]) == count for row in rows), rows)
                    self.assertEqual(count, conn.execute(f'SELECT count(*) FROM {table}').fetchone()[0])
                self.assertEqual('ok', conn.execute('PRAGMA quick_check').fetchone()[0])

    def test_full_integrity_check_detects_a_corrupt_secondary_index(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / 'indexed.db'
            with closing(sqlite3.connect(db)) as conn:
                conn.execute('PRAGMA page_size=4096')
                conn.execute('CREATE TABLE books(id INTEGER PRIMARY KEY, title TEXT NOT NULL)')
                conn.execute('CREATE INDEX idx_books_title ON books(title)')
                conn.executemany(
                    'INSERT INTO books(title) VALUES (?)',
                    ((f'Synthetic title {i:06d}',) for i in range(2000)),
                )
                conn.commit()
                self.assertEqual('ok', baseline.full_integrity_check(conn)['result'])
                page_size = conn.execute('PRAGMA page_size').fetchone()[0]
                root_page = conn.execute(
                    "SELECT rootpage FROM sqlite_master WHERE type='index' AND name='idx_books_title'"
                ).fetchone()[0]
                self.assertGreater(root_page, 1)

            # Keep the SQLite header and sqlite_master readable, but invalidate the
            # secondary-index b-tree page. A schema-only probe can miss this defect.
            with db.open('r+b') as stream:
                stream.seek((root_page - 1) * page_size)
                original = stream.read(1)
                self.assertNotEqual(b'\\x00', original)
                stream.seek((root_page - 1) * page_size)
                stream.write(b'\\x00')
                stream.flush()

            with closing(sqlite3.connect(db)) as conn:
                result = baseline.full_integrity_check(conn)
                self.assertIn(result['result'], ('failed', 'error'), result)
                self.assertGreater(result['wall_ms'], 0)

    def test_import_probe_still_rolls_back_all_synthetic_writes(self):
        with closing(sqlite3.connect(':memory:')) as conn:
            baseline.configure_fixture(conn)
            baseline.migrate(conn)
            baseline.synthesize(conn, 3000)
            before = conn.execute('SELECT id,title FROM books ORDER BY id').fetchall()
            links = conn.execute('SELECT * FROM book_authors ORDER BY book_id,author_id').fetchall()
            result = baseline.import_probe(conn, 3000, rows=200)
            self.assertEqual(200, result['rows'])
            self.assertEqual(before, conn.execute('SELECT id,title FROM books ORDER BY id').fetchall())
            self.assertEqual(links, conn.execute('SELECT * FROM book_authors ORDER BY book_id,author_id').fetchall())


if __name__ == '__main__':
    unittest.main()