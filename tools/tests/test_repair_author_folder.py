"""Isolated offline SQLite/filesystem regression cases. Never uses a real library."""
import importlib.util
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from contextlib import contextmanager
from unittest.mock import patch

@contextmanager
def connected(path):
    connection = sqlite3.connect(path)
    try:
        with connection:
            yield connection
    finally:
        connection.close()

SCRIPT = Path(__file__).resolve().parents[1] / 'repair-author-folder.py'
spec = importlib.util.spec_from_file_location('repair_author_folder', SCRIPT)
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class RepairAuthorFolderTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.root = self.base / 'library'
        self.root.mkdir()
        self.dbfile = self.base / 'db.sqlite'
        self.src = self.root / 'Алёна Васильева'
        self.src.mkdir()
        (self.src / 'one.fb2').write_bytes(b'fb2 book')
        self.backup = self.base / 'safe-copy.sqlite'
        self.journals = self.base / 'journal'
        with connected(self.dbfile) as db:
            db.executescript('''
            CREATE TABLE authors(id TEXT PRIMARY KEY, first_name TEXT, middle_name TEXT, last_name TEXT);
            CREATE TABLE books(id TEXT PRIMARY KEY, collection_root TEXT, folder TEXT,
                 file_name TEXT, local INTEGER, deleted INTEGER);
            CREATE TABLE book_artifacts(artifact_id TEXT PRIMARY KEY, collection_root TEXT,
                 folder TEXT, file_name TEXT, archive_name TEXT, local INTEGER);
            CREATE TABLE book_authors(book_id TEXT, author_id TEXT);
            ''')
            db.execute('INSERT INTO authors VALUES(?,?,?,?)', ('a', 'Алёна', None, 'Васильева'))
            db.execute('INSERT INTO books VALUES(?,?,?,?,?,?)', ('b', str(self.root), self.src.name, 'one.fb2', 1, 0))
            db.execute('INSERT INTO book_authors VALUES(?,?)', ('b', 'a'))
            db.execute('INSERT INTO book_artifacts VALUES(?,?,?,?,?,?)', ('art', str(self.root), self.src.name, 'one.fb2', None, 1))

    def inspect(self):
        with connected(self.dbfile) as db:
            return db.execute('SELECT folder FROM books').fetchone()[0], db.execute('SELECT folder FROM book_artifacts').fetchone()[0]

    def test_preview_is_read_only_and_unambiguous(self):
        with connected(self.dbfile) as db:
            proposal = repair.build_plan(db, self.root, self.src.name)
        self.assertEqual(proposal['target'], 'Васильева Алёна')
        self.assertEqual(len(proposal['books']), 1)
        self.assertEqual(len(proposal['artifacts']), 1)
        self.assertTrue(self.src.exists())
        self.assertEqual(self.inspect(), (self.src.name, self.src.name))

    def test_applies_both_tables_backups_and_hashes(self):
        target = repair.apply_one(self.dbfile, self.root, self.src.name, self.backup, self.journals)
        self.assertFalse(self.src.exists())
        self.assertEqual((target / 'one.fb2').read_bytes(), b'fb2 book')
        self.assertEqual(self.inspect(), ('Васильева Алёна', 'Васильева Алёна'))
        with connected(self.backup) as before:
            self.assertEqual(before.execute('SELECT folder FROM books').fetchone()[0], self.src.name)
        self.assertEqual(len(list(self.journals.glob('*.pending.json'))), 0)
        self.assertEqual(len(list(self.journals.glob('*.done.json'))), 1)

    def test_collision_blocks_changes(self):
        (self.root / 'Васильева Алёна').mkdir()
        with self.assertRaisesRegex(ValueError, 'вже існує'):
            repair.apply_one(self.dbfile, self.root, self.src.name, self.backup, self.journals)
        self.assertTrue(self.src.exists())
        self.assertEqual(self.inspect(), (self.src.name, self.src.name))

    def test_untracked_file_blocks_changes(self):
        (self.src / 'untracked.epub').write_bytes(b'other')
        with connected(self.dbfile) as db:
            with self.assertRaisesRegex(ValueError, 'не збігаються'):
                repair.build_plan(db, self.root, self.src.name)

    def test_symlink_blocks_changes(self):
        extra = self.base / 'other'
        extra.write_bytes(b'outside')
        try:
            (self.src / 'unsafe.fb2').symlink_to(extra)
        except (OSError, NotImplementedError):
            self.skipTest('No symlinks permitted by filesystem')
        with connected(self.dbfile) as db:
            with self.assertRaisesRegex(ValueError, 'Символічні посилання'):
                repair.build_plan(db, self.root, self.src.name)

    def test_shared_zip_book_rows_update_together(self):
        (self.src / 'one.fb2').unlink()
        (self.src / 'archive.zip').write_bytes(b'archive')
        with connected(self.dbfile) as db:
            db.execute('UPDATE books SET file_name=?', ('archive.zip',))
            db.execute('UPDATE book_artifacts SET file_name=?', ('archive.zip',))
            db.execute('INSERT INTO books VALUES(?,?,?,?,?,?)', ('b2', str(self.root), self.src.name, 'archive.zip', 1, 0))
            db.execute('INSERT INTO book_artifacts VALUES(?,?,?,?,?,?)', ('art2', str(self.root), self.src.name, 'archive.zip', None, 1))
            db.execute('INSERT INTO book_authors VALUES(?,?)', ('b2', 'a'))
        repair.apply_one(self.dbfile, self.root, self.src.name, self.backup, self.journals)
        with connected(self.dbfile) as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM books WHERE folder=?', ('Васильева Алёна',)).fetchone()[0], 2)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM book_artifacts WHERE folder=?', ('Васильева Алёна',)).fetchone()[0], 2)

    def test_mismatched_collection_root_refused(self):
        with connected(self.dbfile) as db:
            db.execute('UPDATE book_artifacts SET collection_root=?', (str(self.base),))
        with connected(self.dbfile) as db:
            with self.assertRaisesRegex(ValueError, 'інший корінь'):
                repair.build_plan(db, self.root, self.src.name)

    def test_pending_journal_prevents_new_attempt(self):
        self.journals.mkdir()
        (self.journals / 'old.pending.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'незавершений'):
            repair.apply_one(self.dbfile, self.root, self.src.name, self.backup, self.journals)

    def test_recovery_rolls_back_rename_when_sqlite_not_committed(self):
        with connected(self.dbfile) as db:
            plan = repair.build_plan(db, self.root, self.src.name)
        self.journals.mkdir()
        journal = self.journals / 'interrupted.pending.json'
        repair.durable_json(journal, {'plan': plan, 'stage': 'FOLDER_RENAMED'})
        self.src.rename(self.root / plan['target'])
        result = repair.recover_pending(self.dbfile, self.root, self.journals)
        self.assertEqual(result, ['Алёна Васильева: ABORTED'])
        self.assertTrue(self.src.is_dir())
        self.assertEqual(self.inspect(), (self.src.name, self.src.name))

    def test_recovery_recognizes_committed_db_after_crash(self):
        with connected(self.dbfile) as db:
            plan = repair.build_plan(db, self.root, self.src.name)
        self.journals.mkdir()
        repair.durable_json(self.journals / 'interrupted.pending.json', {'plan': plan, 'stage': 'FOLDER_RENAMED'})
        self.src.rename(self.root / plan['target'])
        with connected(self.dbfile) as db:
            repair.sync_plan(db, plan)
        self.assertEqual(repair.recover_pending(self.dbfile, self.root, self.journals),
                         ['Алёна Васильева: COMPLETED'])
        self.assertEqual(self.inspect(), (plan['target'], plan['target']))

    def test_backup_inside_collection_is_refused(self):
        with self.assertRaisesRegex(ValueError, 'поза коренем'):
            repair.apply_one(self.dbfile, self.root, self.src.name, self.src / 'copy.db', self.journals)

    def test_failed_database_update_rolls_back_folder_and_sqlite(self):
        with patch.object(repair, 'sync_plan', side_effect=RuntimeError('simulated database failure')):
            with self.assertRaisesRegex(RuntimeError, 'simulated'):
                repair.apply_one(self.dbfile, self.root, self.src.name, self.backup, self.journals)
        self.assertTrue(self.src.is_dir())
        self.assertEqual(self.inspect(), (self.src.name, self.src.name))
        self.assertEqual(len(list(self.journals.glob('*.pending.json'))), 1)
        self.assertEqual(repair.recover_pending(self.dbfile, self.root, self.journals),
                         ['Алёна Васильева: ABORTED'])

    def test_recovery_refuses_new_unjournaled_catalog_reference(self):
        with connected(self.dbfile) as db:
            plan = repair.build_plan(db, self.root, self.src.name)
        self.journals.mkdir()
        repair.durable_json(self.journals / 'crash.pending.json',
                            {'plan': plan, 'stage': 'FOLDER_RENAMED'})
        self.src.rename(self.root / plan['target'])
        with connected(self.dbfile) as db:
            db.execute('INSERT INTO books VALUES(?,?,?,?,?,?)',
                       ('unexpected', str(self.root), self.src.name, 'one.fb2', 1, 0))
        with self.assertRaisesRegex(ValueError, 'несумісному стані'):
            repair.recover_pending(self.dbfile, self.root, self.journals)
        self.assertTrue((self.root / plan['target']).is_dir())
        self.assertTrue((self.journals / 'crash.pending.json').exists())

    def test_second_process_cannot_repair_under_same_journal_lock(self):
        child = ("import importlib.util,sys; from pathlib import Path; "
                 "spec=importlib.util.spec_from_file_location('repair',sys.argv[1]); "
                 "m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m); "
                 "\ntry:\n with m.exclusive_repair_lock(Path(sys.argv[2])): print('ACQUIRED')"
                 "\nexcept ValueError as e: print('BUSY:',e)")
        with repair.exclusive_repair_lock(self.journals):
            blocked = subprocess.run([sys.executable, '-c', child,
                                      str(SCRIPT), str(self.journals)],
                                     capture_output=True, text=True, timeout=10, check=True)
            self.assertIn('BUSY:', blocked.stdout)
            self.assertNotIn('ACQUIRED', blocked.stdout)
        released = subprocess.run([sys.executable, '-c', child,
                                   str(SCRIPT), str(self.journals)],
                                  capture_output=True, text=True, timeout=10, check=True)
        self.assertIn('ACQUIRED', released.stdout)

    def test_second_process_with_different_journal_cannot_repair_same_database(self):
        child = ("import importlib.util,sys; from pathlib import Path; "
                 "spec=importlib.util.spec_from_file_location('repair',sys.argv[1]); "
                 "m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m); "
                 "\ntry:\n with m.exclusive_repair_lock(Path(sys.argv[2]),Path(sys.argv[3])): print('ACQUIRED')"
                 "\nexcept ValueError as e: print('BUSY:',e)")
        another_journal = self.base / 'another-journal'
        with repair.exclusive_repair_lock(self.journals, self.dbfile):
            blocked = subprocess.run([sys.executable, '-c', child,
                                      str(SCRIPT), str(another_journal), str(self.dbfile)],
                                     capture_output=True, text=True, timeout=10, check=True)
            self.assertIn('BUSY:', blocked.stdout)
        released = subprocess.run([sys.executable, '-c', child,
                                   str(SCRIPT), str(another_journal), str(self.dbfile)],
                                  capture_output=True, text=True, timeout=10, check=True)
        self.assertIn('ACQUIRED', released.stdout)

    def test_os_releases_catalog_lock_after_crashed_child(self):
        child = ("import importlib.util,os,sys; from pathlib import Path; "
                 "spec=importlib.util.spec_from_file_location('repair',sys.argv[1]); "
                 "m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m); "
                 "\nwith m.exclusive_repair_lock(Path(sys.argv[2]),Path(sys.argv[3])): os._exit(0)")
        process = subprocess.run([sys.executable, '-c', child, str(SCRIPT),
                                  str(self.journals), str(self.dbfile)],
                                 capture_output=True, text=True, timeout=10)
        self.assertEqual(process.returncode, 0, process.stderr)
        with repair.exclusive_repair_lock(self.journals, self.dbfile):
            self.assertTrue((self.journals / '.myhomelib-author-repair.lock').is_file())

    def test_mutation_during_sqlite_backup_rejected_before_journal_or_rename(self):
        original = repair.write_backup
        def other_process_updated_catalog(connection, backup):
            original(connection, backup)
            with connected(self.dbfile) as peer:
                peer.execute('INSERT INTO authors VALUES(?,?,?,?)',
                             ('late', 'Інше', None, 'Прізвище'))
        with patch.object(repair, 'write_backup', side_effect=other_process_updated_catalog):
            with self.assertRaisesRegex(ValueError, 'SQLite змінено під час резервного'):
                repair.apply_one(self.dbfile, self.root, self.src.name,
                                 self.backup, self.journals)
        self.assertEqual(self.inspect(), (self.src.name, self.src.name))
        self.assertTrue(self.src.exists())
        self.assertFalse(list(self.journals.glob('*.pending.json')))

    def test_journal_inside_library_refused_without_creating_lockfile(self):
        journal = self.root / 'journal'
        with self.assertRaisesRegex(ValueError, 'поза коренем'):
            repair.apply_one(self.dbfile, self.root, self.src.name,
                             self.backup, journal)
        self.assertFalse(journal.exists())

    def test_expected_plan_guard_rejects_unapproved_file_changes(self):
        with connected(self.dbfile) as db:
            approved = repair.build_plan(db, self.root, self.src.name)
        (self.src / 'one.fb2').write_bytes(b'changed after approval')
        with self.assertRaisesRegex(ValueError, 'Затверджений план змінився'):
            repair.apply_one(self.dbfile, self.root, self.src.name,
                             self.backup, self.journals, expected_plan=approved)
        self.assertFalse(self.backup.exists())
        self.assertEqual(self.inspect(), (self.src.name, self.src.name))

    def test_prefix_filter_handles_sql_wildcards_and_nested_folders(self):
        source = self.src
        special = self.root / 'Алёна_% Васильева'
        source.rename(special)
        with connected(self.dbfile) as db:
            db.execute('UPDATE books SET folder=?', (special.name,))
            db.execute('UPDATE book_artifacts SET folder=?', (special.name,))
            db.execute('INSERT INTO books VALUES(?,?,?,?,?,?)',
                       ('unrelated', str(self.root), 'Алёна_X Васильева', 'other.fb2', 1, 0))
            db.execute('INSERT INTO authors VALUES(?,?,?,?)', ('other', 'Другая', None, 'Фамилия'))
            db.execute('INSERT INTO book_authors VALUES(?,?)', ('unrelated', 'other'))
        # No matches for the special spelling: refuses safely. The unrelated
        # underscore wildcard row must not be selected by the SQL filter.
        with connected(self.dbfile) as db:
            rows = list(repair.catalog_folder_rows(db, 'books', 'id,folder', special.name))
        self.assertEqual(rows, [('b', special.name)])

    def test_wrong_book_author_is_not_silently_renamed(self):
        with connected(self.dbfile) as db:
            db.execute('DELETE FROM book_authors WHERE book_id=?', ('b',))
        with connected(self.dbfile) as db:
            with self.assertRaisesRegex(ValueError, 'не пов’язана'):
                repair.build_plan(db, self.root, self.src.name)

    def test_artifact_with_independent_archive_name_blocks_repair(self):
        with connected(self.dbfile) as db:
            db.execute('UPDATE book_artifacts SET archive_name=?', ('another.zip',))
        with connected(self.dbfile) as db:
            with self.assertRaisesRegex(ValueError, 'Неоднозначне'):
                repair.build_plan(db, self.root, self.src.name)

    def test_malformed_recovery_record_is_rejected_without_traceback_or_mutation(self):
        self.journals.mkdir()
        damaged = self.journals / 'broken.pending.json'
        damaged.write_text(json.dumps({'stage': 'FOLDER_RENAMED', 'plan': {'source': 3}}), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Пошкоджений журнал'):
            repair.recover_pending(self.dbfile, self.root, self.journals)
        self.assertEqual(self.inspect(), (self.src.name, self.src.name))
        self.assertEqual(len(list(self.journals.glob('*.pending.json'))), 1)
        self.assertTrue(self.src.is_dir())

    def test_recovery_refuses_symlink_in_parent_of_journal(self):
        actual = self.base / 'journal-real'
        actual.mkdir()
        alias = self.base / 'alias-to-journal'
        try:
            alias.symlink_to(actual, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest('Filesystem does not support symbolic links')
        with self.assertRaisesRegex(ValueError, 'символічн'):
            repair.recover_pending(self.dbfile, self.root, alias)
        self.assertEqual(self.inspect(), (self.src.name, self.src.name))

    def test_ambiguous_authors_are_blocked(self):
        with connected(self.dbfile) as db:
            db.execute('INSERT INTO authors VALUES(?,?,?,?)', ('b', 'Алёна', 'Інша', 'Васильева'))
        # Old two-part variant matches both, but expected folders differ.
        with connected(self.dbfile) as db:
            with self.assertRaisesRegex(ValueError, 'не визначено однозначно'):
                repair.build_plan(db, self.root, self.src.name)


if __name__ == '__main__':
    unittest.main()
