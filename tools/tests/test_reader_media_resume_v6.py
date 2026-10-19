"""V71 migration and actual SQL UPSERT contract using only synthetic in-memory SQLite."""
from __future__ import annotations

import pathlib
import re
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration/V71__split_reader_media_resume_anchors.sql"
MIGRATION_V72 = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration/V72__reading_progress_revision_guard.sql"
SOURCE = ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteReadingProgressRepository.java"


class ReaderMediaResumeMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:", isolation_level=None)
        self.db.executescript("""
            CREATE TABLE books(id TEXT PRIMARY KEY, progress INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE reading_progress(
                book_id TEXT PRIMARY KEY, anchor_id TEXT, paragraph_index INTEGER DEFAULT 0,
                paragraph_id TEXT NOT NULL DEFAULT '', char_offset INTEGER DEFAULT 0,
                percent REAL DEFAULT 0, chapter_title TEXT, chapter_id TEXT,
                updated_at TEXT NOT NULL, reading_time_seconds INTEGER DEFAULT 0,
                last_device TEXT NOT NULL DEFAULT 'desktop');
            INSERT INTO books(id,progress) VALUES('text',10),('audio',20),('empty',0);
            INSERT INTO reading_progress(book_id,anchor_id,updated_at) VALUES
                ('text','1:10:0:0','2026-10-16'),
                ('audio','audio:4000:1:2','2026-10-16'),
                ('empty',NULL,'2026-10-16');
        """)
        self.db.executescript(MIGRATION.read_text(encoding="utf-8"))
        self.db.executescript(MIGRATION_V72.read_text(encoding="utf-8"))
        source = SOURCE.read_text(encoding="utf-8")
        self.sql = re.search(r'String sql = """(.*?)""";', source, re.S).group(1)

    def tearDown(self):
        self.db.close()

    def save(self, book, anchor, percent):
        is_audio = anchor.startswith('audio:')
        args = (book, anchor, None if is_audio else anchor, anchor if is_audio else None,
                0, 'p0', 0, percent, '', '', '2026-10-16', 0, 'desktop')
        self.db.execute('BEGIN IMMEDIATE')
        try:
            self.db.execute(self.sql, args)
            self.db.execute('UPDATE books SET progress=? WHERE id=?', (round(percent), book))
            self.db.execute('COMMIT')
        except Exception:
            self.db.execute('ROLLBACK')
            raise

    def record(self, book):
        return self.db.execute('SELECT anchor_id,text_anchor_id,audio_anchor_id FROM reading_progress WHERE book_id=?', (book,)).fetchone()

    def test_migration_preserves_known_last_active_medium_without_guessing_missing_one(self):
        self.assertEqual(self.record('text'), ('1:10:0:0', '1:10:0:0', None))
        self.assertEqual(self.record('audio'), ('audio:4000:1:2', None, 'audio:4000:1:2'))
        self.assertEqual(self.record('empty'), (None, None, None))
        self.assertEqual(self.db.execute('PRAGMA integrity_check').fetchone()[0], 'ok')

    def test_interleaving_does_not_overwrite_other_medium(self):
        self.save('text', 'audio:84000:1:2', 42)
        self.assertEqual(self.record('text'), ('audio:84000:1:2', '1:10:0:0', 'audio:84000:1:2'))
        self.save('text', '1:500:3:2', 50)
        self.assertEqual(self.record('text'), ('1:500:3:2', '1:500:3:2', 'audio:84000:1:2'))
        self.assertEqual(self.db.execute('PRAGMA integrity_check').fetchone()[0], 'ok')

    def test_failed_projection_update_rolls_back_both_anchors(self):
        before = self.record('audio')
        self.db.execute("""CREATE TRIGGER stop_summary BEFORE UPDATE OF progress ON books
            WHEN NEW.progress=99 BEGIN SELECT RAISE(ABORT,'simulated failure'); END""")
        with self.assertRaises(sqlite3.DatabaseError):
            self.save('audio', '1:990:0:0', 99)
        self.assertEqual(self.record('audio'), before)
        self.assertEqual(self.db.execute("SELECT progress FROM books WHERE id='audio'").fetchone()[0], 20)

    def test_sync_delete_resets_both_media_by_removing_parent_row(self):
        self.save('text', 'audio:84000:1:2', 40)
        self.db.execute("DELETE FROM reading_progress WHERE book_id='text'")
        self.assertIsNone(self.record('text'))


if __name__ == '__main__':
    unittest.main()

class ReaderMediaTransferContractTest(unittest.TestCase):
    """Exercise production SQL statements against independent synthetic databases."""
    def test_copy_book_state_preserves_both_anchors(self):
        source_code = (ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp"
                       "/infrastructure/persistence/sqlite/SqliteBookUserStateTransferAdapter.java").read_text()
        method = source_code.split('private static void copyReadingProgress(', 1)[1].split(
            'private static void copyReadingHistory(', 1)[0]
        select = re.search(r'String select = """(.*?)"""\.formatted', method, re.S).group(1)
        upsert = re.search(r'String upsert = """(.*?)""";', method, re.S).group(1)
        self.assertIn('copyRows(source, target, ids, select, upsert, 13, false)', method)
        ddl = """CREATE TABLE reading_progress(
            book_id TEXT PRIMARY KEY, anchor_id TEXT, text_anchor_id TEXT, audio_anchor_id TEXT,
            paragraph_index INTEGER, paragraph_id TEXT, char_offset INTEGER, percent REAL,
            chapter_title TEXT, chapter_id TEXT, updated_at TEXT,
            reading_time_seconds INTEGER, last_device TEXT);"""
        src, dest = sqlite3.connect(':memory:'), sqlite3.connect(':memory:')
        try:
            src.execute(ddl)
            dest.execute(ddl.replace("last_device TEXT);", "last_device TEXT, revision INTEGER NOT NULL DEFAULT 0);"))
            src.execute("INSERT INTO reading_progress VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        ('book', 'audio:123:0:1', '1:44:0:0', 'audio:123:0:1', 1,
                         'p1', 0, 44.0, 'Ch', '', '2026-10-16', 2, 'desktop'))
            args = src.execute(select.replace('%s', '?'), ('book',)).fetchone()
            dest.execute(upsert, args)
            self.assertEqual(dest.execute('SELECT text_anchor_id,audio_anchor_id,last_device '
                                          'FROM reading_progress').fetchone(),
                             ('1:44:0:0', 'audio:123:0:1', 'desktop'))
            self.assertEqual(dest.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
        finally:
            src.close()
            dest.close()

    def test_backup_export_and_restore_include_independent_anchors(self):
        text = (ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp'
                '/infrastructure/backup/VersionedUserDataTransferAdapter.java').read_text()
        exporter = text.split('private void exportReadingProgress(', 1)[1].split(
            'private void exportReadingHistory(', 1)[0]
        importer = text.split('private void restoreReadingProgressRow(', 1)[1].split(
            'private void restoreReadingHistoryRow(', 1)[0]
        for column, key in [('text_anchor_id','textAnchorId'), ('audio_anchor_id','audioAnchorId')]:
            self.assertIn('rp.' + column + ' AS ' + key, exporter)
            self.assertIn('restoredMediaAnchor(n, "' + key + '"', importer)
            self.assertIn(column + '=COALESCE(excluded.' + column, importer)
        self.assertIn('legacy.startsWith("audio:") == audio', importer)
