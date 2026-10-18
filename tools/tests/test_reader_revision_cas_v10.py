"""SQLite-based contract tests executing SQL extracted from the actual Java repository.

The tests do not require Maven or the user's library. They are not equivalent to
compiling the Spring repository or running Flyway/JUnit.
"""
from __future__ import annotations
import pathlib
import re
import sqlite3
from contextlib import closing
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
REPO = ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteReadingProgressRepository.java'
MIGRATION = ROOT / 'myhomelib-infrastructure/src/main/resources/db/migration/V72__reading_progress_revision_guard.sql'
DELETE_GENERATION_MIGRATION = ROOT / 'myhomelib-infrastructure/src/main/resources/db/migration/V73__reading_progress_delete_generation.sql'


def query(label):
    source = REPO.read_text('utf-8')
    found = re.search(r'String ' + re.escape(label) + r' = """(.*?)""";', source, re.S)
    if not found:
        raise AssertionError('missing actual SQL: ' + label)
    # SQLite parses the Java SQL comments fine.
    return found.group(1)


def schema(db):
    db.executescript('''
        CREATE TABLE books(id TEXT PRIMARY KEY, progress INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE reading_progress(
            book_id TEXT PRIMARY KEY, anchor_id TEXT, text_anchor_id TEXT, audio_anchor_id TEXT,
            paragraph_index INTEGER DEFAULT 0, paragraph_id TEXT DEFAULT '', char_offset INTEGER DEFAULT 0,
            percent REAL DEFAULT 0, chapter_title TEXT DEFAULT '', chapter_id TEXT DEFAULT '',
            updated_at TEXT NOT NULL, reading_time_seconds INTEGER DEFAULT 0, last_device TEXT DEFAULT 'desktop');
        INSERT INTO books(id,progress) VALUES ('b',0);
    ''')
    db.executescript(MIGRATION.read_text('utf-8'))
    db.executescript(DELETE_GENERATION_MIGRATION.read_text('utf-8'))


def args(anchor):
    return ('b', anchor, None if anchor.startswith('audio:') else anchor,
            anchor if anchor.startswith('audio:') else None, 0, 'p0', 0, 40,
            '', '', '2026-10-16T21:00:00', 0, 'desktop')


def insert_never_deleted_args(anchor):
    return (*args(anchor), 'b')


def insert_with_generation_args(anchor, generation):
    return (*args(anchor), 'b', generation)


def update_args(anchor, revision):
    p = args(anchor)
    return (*p[1:], p[0], revision)


class RevisionCasV10Test(unittest.TestCase):
    def test_migration_preserves_v71_rows_and_starts_at_zero(self):
        with closing(sqlite3.connect(':memory:')) as db:
            schema(db)
            db.execute(query('insertIfNeverDeleted'), insert_never_deleted_args('1:2:0:0'))
            self.assertEqual(db.execute('SELECT revision FROM reading_progress').fetchone()[0], 0)
            self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0], 'ok')

    def test_two_sqlite_connections_stale_revision_rejected_and_sibling_anchor_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'progress.sqlite'
            a, b = sqlite3.connect(path), sqlite3.connect(path)
            try:
                schema(a)
                self.assertEqual(a.execute(query('insertIfNeverDeleted'), insert_never_deleted_args('1:10:0:0')).rowcount, 1)
                a.commit()
                seen_a = a.execute('SELECT revision FROM reading_progress').fetchone()[0]
                seen_b = b.execute('SELECT revision FROM reading_progress').fetchone()[0]
                self.assertEqual((seen_a, seen_b), (0, 0))
                self.assertEqual(a.execute(query('updateIfRevision'), update_args('audio:5000:1:2', seen_a)).rowcount, 1)
                a.execute("UPDATE books SET progress=40 WHERE id='b'")
                a.commit()
                self.assertEqual(b.execute(query('updateIfRevision'), update_args('1:90:0:0', seen_b)).rowcount, 0)
                b.commit()
                anchor, text_anchor, audio_anchor, revision = a.execute(
                    'SELECT anchor_id,text_anchor_id,audio_anchor_id,revision FROM reading_progress').fetchone()
                self.assertEqual((anchor, text_anchor, audio_anchor, revision),
                                 ('audio:5000:1:2', '1:10:0:0', 'audio:5000:1:2', 1))
                self.assertEqual(a.execute('SELECT progress FROM books').fetchone()[0], 40)
            finally:
                a.close()
                b.close()

    def test_insert_absent_and_legacy_writer_invalidates_stale_revision(self):
        with closing(sqlite3.connect(':memory:')) as db:
            schema(db)
            sql = query('insertIfNeverDeleted')
            self.assertEqual(db.execute(sql, insert_never_deleted_args('1:10:0:0')).rowcount, 1)
            self.assertEqual(db.execute(sql, insert_never_deleted_args('1:100:0:0')).rowcount, 0)
            # Legacy SQL also advances revision so an old Reader CAS cannot succeed.
            self.assertEqual(db.execute(query('sql'), args('1:40:0:0')).rowcount, 1)
            self.assertEqual(db.execute(query('updateIfRevision'), update_args('1:20:0:0', 0)).rowcount, 0)
            self.assertEqual(db.execute('SELECT anchor_id,revision FROM reading_progress').fetchone(), ('1:40:0:0', 1))

    def test_v73_old_absent_reader_cannot_insert_after_intervening_create_and_delete(self):
        with closing(sqlite3.connect(':memory:', isolation_level=None)) as db:
            schema(db)
            stale_insert = query('insertIfNeverDeleted')
            db.execute(stale_insert, insert_never_deleted_args('1:10:0:0'))
            db.execute("DELETE FROM reading_progress WHERE book_id='b'")
            self.assertEqual(db.execute('SELECT next_revision FROM reading_progress_deleted_generations').fetchone()[0], 1)
            self.assertEqual(db.execute(stale_insert, insert_never_deleted_args('1:70:0:0')).rowcount, 0)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM reading_progress').fetchone()[0], 0)

    def test_v73_matching_tombstone_lets_first_writer_recreate_once(self):
        with closing(sqlite3.connect(':memory:', isolation_level=None)) as db:
            schema(db)
            db.execute(query('insertIfNeverDeleted'), insert_never_deleted_args('1:10:0:0'))
            db.execute("DELETE FROM reading_progress WHERE book_id='b'")
            recreate = query('insertForObservedDeleteGeneration')
            self.assertEqual(db.execute(recreate, insert_with_generation_args('audio:5000:0:0', 1)).rowcount, 1)
            self.assertEqual(db.execute(recreate, insert_with_generation_args('audio:9000:0:0', 1)).rowcount, 0)
            self.assertEqual(db.execute('SELECT audio_anchor_id,revision FROM reading_progress').fetchone(),
                             ('audio:5000:0:0', 1))
            db.execute("DELETE FROM reading_progress WHERE book_id='b'")
            self.assertEqual(db.execute(recreate, insert_with_generation_args('1:80:0:0', 1)).rowcount, 0)
            self.assertEqual(db.execute(recreate, insert_with_generation_args('1:80:0:0', 2)).rowcount, 1)
            self.assertEqual(db.execute('SELECT revision FROM reading_progress').fetchone()[0], 2)

    def test_v72_rejected_negative_revision_and_sqlite_transaction_rollback(self):
        with closing(sqlite3.connect(':memory:')) as db:
            schema(db)
            db.execute(query('insertIfNeverDeleted'), insert_never_deleted_args('1:10:0:0'))
            db.commit()  # The original row must survive the failed later transaction.
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute('UPDATE reading_progress SET revision=-1')
            db.execute("CREATE TRIGGER fail_summary BEFORE UPDATE ON books BEGIN SELECT RAISE(ABORT,'forced'); END")
            with self.assertRaises(sqlite3.DatabaseError):
                with db:
                    db.execute(query('updateIfRevision'), update_args('1:20:0:0', 0))
                    db.execute("UPDATE books SET progress=20 WHERE id='b'")
            self.assertEqual(db.execute('SELECT anchor_id,revision FROM reading_progress').fetchone(), ('1:10:0:0', 0))


if __name__ == '__main__':
    unittest.main()
