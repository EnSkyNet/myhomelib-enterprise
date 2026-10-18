"""Synthetic SQLite contract for Reader tombstones (no user catalogue touched).

These tests parse the actual DELETE/UPDATE SQL used in SqliteReadingProgressRepository.
The separate JUnit test runs this contract through Spring on full Maven environments.
"""
from __future__ import annotations

import pathlib
import re
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp"
          "/infrastructure/persistence/sqlite/SqliteReadingProgressRepository.java")


class ReadingProgressTombstoneContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = SOURCE.read_text(encoding="utf-8")
        body = cls.text.split("public void deleteByBookId(String bookId)", 1)[1]
        cls.statements = re.findall(r'jdbc\.update\("([^"\n]+)"\s*,\s*bookId\)', body)
        assert len(cls.statements) == 2, cls.statements

    def setUp(self):
        self.conn = sqlite3.connect(":memory:", isolation_level=None)
        self.conn.executescript("""
            PRAGMA foreign_keys=ON;
            CREATE TABLE books(id TEXT PRIMARY KEY, progress INTEGER NOT NULL);
            CREATE TABLE reading_progress(book_id TEXT PRIMARY KEY,anchor_id TEXT);
            INSERT INTO books VALUES ('book-a', 64), ('book-b', 85);
            INSERT INTO reading_progress VALUES ('book-a','0:64:0:0'), ('book-b','0:85:0:0');
        """)

    def tearDown(self):
        self.conn.close()

    def apply(self, book_id):
        self.conn.execute("BEGIN IMMEDIATE")
        try:
            for sql in self.statements:
                self.conn.execute(sql, (book_id,))
            self.conn.execute("COMMIT")
        except Exception:
            self.conn.execute("ROLLBACK")
            raise

    def test_tombstone_resets_only_selected_book(self):
        self.apply("book-a")
        self.assertIsNone(self.conn.execute(
            "SELECT anchor_id FROM reading_progress WHERE book_id='book-a'").fetchone())
        self.assertEqual(self.conn.execute(
            "SELECT progress FROM books WHERE id='book-a'").fetchone()[0], 0)
        self.assertEqual(self.conn.execute(
            "SELECT progress FROM books WHERE id='book-b'").fetchone()[0], 85)
        self.assertEqual(self.conn.execute("PRAGMA integrity_check").fetchone()[0], "ok")

    def test_failure_of_projection_reset_rolls_back_delete(self):
        self.conn.execute("""
            CREATE TRIGGER fail_summary BEFORE UPDATE OF progress ON books
            WHEN NEW.progress=0 BEGIN SELECT RAISE(ABORT, 'simulated disk failure'); END
        """)
        with self.assertRaises(sqlite3.DatabaseError):
            self.apply("book-a")
        self.assertEqual(self.conn.execute(
            "SELECT anchor_id FROM reading_progress WHERE book_id='book-a'").fetchone()[0], "0:64:0:0")
        self.assertEqual(self.conn.execute(
            "SELECT progress FROM books WHERE id='book-a'").fetchone()[0], 64)
        self.assertEqual(self.conn.execute("PRAGMA integrity_check").fetchone()[0], "ok")

    def test_transaction_boundary_is_declared_for_production_and_fallback(self):
        method = self.text.split("public void deleteByBookId(String bookId)", 1)[1]
        method = method.split('log.debug("Deleted reading progress', 1)[0]
        self.assertIn("transactionTemplate.execute", method)
        self.assertIn("CollectionTransactionExecutor.run", method)
        self.assertIn("busyRetry.run", method)


if __name__ == "__main__":
    unittest.main()
