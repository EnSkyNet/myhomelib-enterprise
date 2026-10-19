"""True SQLite migration-chain rehearsal without Maven/Flyway or personal library data.

This validates the SQL scripts in order against SQLite itself. It is not a
substitute for Flyway metadata checks, Java compatibility, or a production DB copy.
"""
from __future__ import annotations
import pathlib
import re
import sqlite3
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / 'myhomelib-infrastructure/src/main/resources/db/migration'


def scripts():
    return sorted(MIGRATIONS.glob('V[0-9]*__*.sql'),
                  key=lambda p: int(re.match(r'^V(\d+)__', p.name).group(1)))


def migrate(connection, stop=None, start=1):
    for file in scripts():
        version = int(re.match(r'^V(\d+)__', file.name).group(1))
        if stop is not None and version > stop:
            break
        if version < start:
            continue
        connection.executescript(file.read_text('utf-8'))


def seed_v70(connection):
    connection.executemany('INSERT INTO books(id,title,file_name,progress) VALUES(?,?,?,?)', [
        ('text', 'Text', 'text.fb2', 11), ('audio', 'Audio', 'audio.fb2', 21),
        ('none', 'None', 'none.fb2', 0)])
    connection.executemany('INSERT INTO reading_progress'
                           '(book_id,paragraph_id,char_offset,percent,updated_at,anchor_id) VALUES(?,?,?,?,?,?)', [
        ('text', 'p1', 0, 11, '2026-10-16 12:00:00', '1:123:2:3'),
        ('audio', 'audio-track:1', 0, 21, '2026-10-16 12:00:00', 'audio:12000:1:2'),
        ('none', 'p0', 0, 0, '2026-10-16 12:00:00', None)])


class FullMigrationChainTest(unittest.TestCase):
    def test_every_migration_applies_on_new_sqlite_database(self):
        versions = [int(re.match(r'^V(\d+)__', f.name).group(1)) for f in scripts()]
        self.assertEqual(versions, list(range(1, versions[-1] + 1)))
        connection = sqlite3.connect(':memory:', isolation_level=None)
        try:
            connection.execute('PRAGMA foreign_keys=ON')
            migrate(connection)
            self.assertEqual(connection.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
            self.assertFalse(connection.execute('PRAGMA foreign_key_check').fetchall())
            columns = {r[1] for r in connection.execute('PRAGMA table_info(reading_progress)')}
            self.assertTrue({'text_anchor_id', 'audio_anchor_id', 'revision'} <= columns)
        finally:
            connection.close()

    def test_v73_prevents_stale_revision_after_delete_and_recreate(self):
        connection = sqlite3.connect(':memory:', isolation_level=None)
        try:
            connection.execute('PRAGMA foreign_keys=ON')
            migrate(connection)
            connection.execute("INSERT INTO books(id,title,file_name,progress) VALUES('b','Book','b.fb2',0)")
            connection.execute("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) VALUES('b','',0,'2026-10-17',0)")
            # A different process previously observed this revision.
            original = connection.execute("SELECT revision FROM reading_progress WHERE book_id='b'").fetchone()[0]
            connection.execute("DELETE FROM reading_progress WHERE book_id='b'")
            self.assertEqual(connection.execute(
                "SELECT next_revision FROM reading_progress_deleted_generations WHERE book_id='b'").fetchone()[0], 1)
            connection.execute("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) VALUES('b','',0,'new',0)")
            revived = connection.execute("SELECT revision FROM reading_progress WHERE book_id='b'").fetchone()[0]
            self.assertGreater(revived, original)
            changed = connection.execute("UPDATE reading_progress SET anchor_id='stale',revision=revision+1 "
                                         "WHERE book_id='b' AND revision=?", (original,)).rowcount
            self.assertEqual(changed, 0)
            self.assertEqual(connection.execute(
                "SELECT anchor_id FROM reading_progress WHERE book_id='b'").fetchone()[0], None)
            self.assertEqual(connection.execute(
                'SELECT COUNT(*) FROM reading_progress_deleted_generations').fetchone()[0], 0)
            # Each subsequent lifecycle must advance the generation as well.
            connection.execute("DELETE FROM reading_progress WHERE book_id='b'")
            connection.execute("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) VALUES('b','',0,'again',0)")
            self.assertEqual(connection.execute(
                "SELECT revision FROM reading_progress WHERE book_id='b'").fetchone()[0], revived + 1)
            self.assertEqual(connection.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
        finally:
            connection.close()

    def test_v73_failed_recreation_rolls_back_tombstone_consumption(self):
        connection = sqlite3.connect(':memory:', isolation_level=None)
        try:
            migrate(connection)
            connection.execute("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) VALUES('b','',0,'old',3)")
            connection.execute("DELETE FROM reading_progress WHERE book_id='b'")
            connection.execute('BEGIN IMMEDIATE')
            connection.execute("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) VALUES('b','',0,'new',0)")
            self.assertEqual(connection.execute("SELECT revision FROM reading_progress WHERE book_id='b'").fetchone()[0], 4)
            connection.rollback()
            self.assertIsNone(connection.execute("SELECT revision FROM reading_progress WHERE book_id='b'").fetchone())
            self.assertEqual(connection.execute(
                "SELECT next_revision FROM reading_progress_deleted_generations WHERE book_id='b'").fetchone()[0], 4)
            connection.execute("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) VALUES('b','',0,'again',0)")
            self.assertEqual(connection.execute("SELECT revision FROM reading_progress WHERE book_id='b'").fetchone()[0], 4)
        finally:
            connection.close()

    def test_v73_rejects_stale_cas_across_two_sqlite_connections(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(pathlib.Path(directory) / 'progress.db')
            first = sqlite3.connect(path, isolation_level=None)
            second = sqlite3.connect(path, isolation_level=None)
            try:
                migrate(first)
                first.execute("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) "
                              "VALUES('b','',0,'initial',0)")
                stale = second.execute("SELECT revision FROM reading_progress WHERE book_id='b'").fetchone()[0]
                first.execute("DELETE FROM reading_progress WHERE book_id='b'")
                first.execute("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) "
                              "VALUES('b','',0,'new',0)")
                self.assertEqual(second.execute(
                    "UPDATE reading_progress SET anchor_id='stale',revision=revision+1 "
                    "WHERE book_id='b' AND revision=?", (stale,)).rowcount, 0)
                self.assertEqual(first.execute(
                    "SELECT updated_at FROM reading_progress WHERE book_id='b'").fetchone()[0], 'new')
            finally:
                second.close()
                first.close()

    def test_v73_repeated_deletes_do_not_lose_revision_or_accumulate_consumed_ledger(self):
        connection = sqlite3.connect(':memory:', isolation_level=None)
        try:
            migrate(connection)
            connection.execute("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) "
                               "VALUES('b','',0,'start',0)")
            for n in range(1000):
                connection.execute("DELETE FROM reading_progress WHERE book_id='b'")
                connection.execute("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,updated_at,revision) "
                                   "VALUES('b','',0,'cycle',0)")
            self.assertEqual(connection.execute(
                "SELECT revision FROM reading_progress WHERE book_id='b'").fetchone()[0], 1000)
            self.assertEqual(connection.execute(
                "SELECT COUNT(*) FROM reading_progress_deleted_generations").fetchone()[0], 0)
        finally:
            connection.close()

    def test_v71_upgrades_real_v70_schema_without_losing_last_observed_anchor(self):
        connection = sqlite3.connect(':memory:', isolation_level=None)
        try:
            connection.execute('PRAGMA foreign_keys=ON')
            migrate(connection, 70)
            seed_v70(connection)
            before = list(connection.execute('SELECT book_id,anchor_id FROM reading_progress ORDER BY book_id'))
            migrate(connection, start=71)
            after = dict((row[0], row[1:]) for row in connection.execute(
                'SELECT book_id,anchor_id,text_anchor_id,audio_anchor_id FROM reading_progress'))
            self.assertEqual(after['text'], ('1:123:2:3', '1:123:2:3', None))
            self.assertEqual(after['audio'], ('audio:12000:1:2', None, 'audio:12000:1:2'))
            self.assertEqual(after['none'], (None, None, None))
            self.assertEqual([(id, row[0]) for id, row in sorted(after.items())], before)
            self.assertEqual(connection.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
            self.assertFalse(connection.execute('PRAGMA foreign_key_check').fetchall())
        finally:
            connection.close()

    def test_v71_schema_ddl_rolls_back_on_simulated_sql_error_under_transaction(self):
        connection = sqlite3.connect(':memory:', isolation_level=None)
        try:
            connection.execute('PRAGMA foreign_keys=ON')
            migrate(connection, 70)
            seed_v70(connection)
            connection.executescript('''CREATE TRIGGER fail_v71_update BEFORE UPDATE ON reading_progress
                WHEN OLD.book_id='audio' BEGIN SELECT RAISE(ABORT, 'injected disk/write error'); END;''')
            file = next(f for f in scripts() if f.name.startswith('V71__'))
            with self.assertRaises(sqlite3.DatabaseError):
                connection.executescript('BEGIN IMMEDIATE;\n' + file.read_text('utf-8') + '\nCOMMIT;')
            connection.rollback()
            columns = {r[1] for r in connection.execute('PRAGMA table_info(reading_progress)')}
            self.assertNotIn('text_anchor_id', columns)
            self.assertNotIn('audio_anchor_id', columns)
            self.assertEqual(connection.execute('SELECT anchor_id FROM reading_progress WHERE book_id=?',
                                                ('audio',)).fetchone()[0], 'audio:12000:1:2')
            self.assertEqual(connection.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
        finally:
            connection.close()


if __name__ == '__main__':
    unittest.main()
