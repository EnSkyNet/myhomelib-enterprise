"""Synthetic end-to-end, offline-only tests for bounded author-folder batch repair."""
import importlib.util
import csv
from contextlib import closing, contextmanager
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('repair_author_folders_batch', ROOT / 'repair-author-folders-batch.py')
batch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(batch)


@contextmanager
def committed_connection(path):
    # sqlite3's own context manager commits but does not close the handle.
    with closing(sqlite3.connect(path)) as db:
        with db:
            yield db


class BatchRepairTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        self.root = self.base / 'library'
        self.root.mkdir()
        self.db = self.base / 'books.db'
        self.list = self.base / 'select.txt'
        self.backups = self.base / 'backups'
        self.journals = self.base / 'journals'
        with committed_connection(self.db) as db:
            db.executescript('''
                CREATE TABLE authors(id TEXT PRIMARY KEY, first_name TEXT, middle_name TEXT, last_name TEXT);
                CREATE TABLE books(id TEXT PRIMARY KEY, collection_root TEXT, folder TEXT,
                     file_name TEXT, local INTEGER, deleted INTEGER);
                CREATE TABLE book_artifacts(artifact_id TEXT PRIMARY KEY, collection_root TEXT,
                     folder TEXT, file_name TEXT, archive_name TEXT, local INTEGER);
                CREATE TABLE book_authors(book_id TEXT, author_id TEXT);
            ''')
        self.add_author('a', 'Алёна', 'Васильева', 'a.fb2')
        self.add_author('b', 'Глеб', 'Алёхин', 'b.fb2')
        self.names = ['Алёна Васильева', 'Глеб Алёхин']
        self.list.write_text('\n'.join(self.names), encoding='utf-8')

    def add_author(self, key, given, last, filename):
        old = given + ' ' + last
        (self.root / old).mkdir()
        (self.root / old / filename).write_bytes(('content-' + key).encode())
        with committed_connection(self.db) as db:
            db.execute('INSERT INTO authors VALUES(?,?,?,?)', (key, given, None, last))
            db.execute('INSERT INTO books VALUES(?,?,?,?,?,?)', (key, str(self.root), old, filename, 1, 0))
            db.execute('INSERT INTO book_artifacts VALUES(?,?,?,?,?,?)', ('ar-' + key, str(self.root), old, filename, None, 1))
            db.execute('INSERT INTO book_authors VALUES(?,?)', (key, key))

    def rows(self):
        with committed_connection(self.db) as db:
            return db.execute('SELECT id,folder FROM books ORDER BY id').fetchall()

    def test_preview_is_read_only_and_stable(self):
        names = batch.selected_folders(self.list, self.root)
        before = self.db.read_bytes()
        plans, signature = batch.preflight(self.db, self.root, names)
        self.assertEqual(len(plans), 2)
        self.assertEqual(len(signature), 64)
        self.assertEqual(signature, batch.preflight(self.db, self.root, names)[1])
        self.assertEqual(self.db.read_bytes(), before)
        self.assertFalse(self.backups.exists())
        self.assertFalse(self.journals.exists())

    def test_apply_both_and_keeps_each_backup(self):
        signature = batch.preflight(self.db, self.root, self.names)[1]
        done = batch.do_batch(self.db, self.root, self.names, signature, self.backups, self.journals)
        self.assertEqual(len(done), 2)
        self.assertEqual(len(list(self.backups.glob('*.sqlite'))), 2)
        self.assertEqual(len(list(self.journals.glob('*.done.json'))), 2)
        self.assertFalse(list(self.journals.glob('*.pending.json')))
        self.assertEqual(self.rows(), [('a', 'Васильева Алёна'), ('b', 'Алёхин Глеб')])
        with committed_connection(self.db) as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM book_artifacts WHERE folder IN (?,?)',
                                        ('Васильева Алёна', 'Алёхин Глеб')).fetchone()[0], 2)

    def test_stale_approval_rejected_before_write(self):
        signature = batch.preflight(self.db, self.root, self.names)[1]
        (self.root / self.names[0] / 'a.fb2').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'План змінився'):
            batch.do_batch(self.db, self.root, self.names, signature, self.backups, self.journals)
        self.assertFalse(self.backups.exists())
        self.assertEqual(self.rows()[0][1], self.names[0])

    def test_collision_in_second_folder_prevents_any_change(self):
        (self.root / 'Алёхин Глеб').mkdir()
        with self.assertRaisesRegex(ValueError, 'вже існує'):
            batch.preflight(self.db, self.root, self.names)
        self.assertEqual(self.rows()[0][1], self.names[0])

    def test_wrong_approval_rejected(self):
        with self.assertRaisesRegex(ValueError, 'План змінився'):
            batch.do_batch(self.db, self.root, self.names, '0' * 64, self.backups, self.journals)
        self.assertFalse(self.backups.exists())

    def test_partial_failure_stops_and_preserves_journal(self):
        signature = batch.preflight(self.db, self.root, self.names)[1]
        orig = batch.repair.apply_one
        calls = []

        def fail_second(db, root, name, backup, journals):
            calls.append(name)
            if len(calls) == 2:
                raise RuntimeError('disk failure')
            return orig(db, root, name, backup, journals)

        with self.assertRaisesRegex(RuntimeError, 'disk failure'):
            batch.do_batch(self.db, self.root, self.names, signature,
                           self.backups, self.journals, executor=fail_second)
        self.assertEqual(self.rows()[0][1], 'Васильева Алёна')
        self.assertEqual(self.rows()[1][1], self.names[1])
        self.assertEqual(len(list(self.journals.glob('*.done.json'))), 1)
        self.assertFalse((self.root / self.names[0]).exists())
        self.assertTrue((self.root / self.names[1]).exists())

    def test_insufficient_backup_space_blocks_whole_batch(self):
        signature = batch.preflight(self.db, self.root, self.names)[1]
        with patch.object(batch.shutil, 'disk_usage', return_value=SimpleNamespace(free=100)):
            with self.assertRaisesRegex(ValueError, 'Недостатньо вільного місця'):
                batch.do_batch(self.db, self.root, self.names, signature, self.backups, self.journals)
        self.assertFalse(self.backups.exists())
        self.assertEqual(self.rows()[0][1], self.names[0])

    def test_pending_journal_blocks_batch_before_write(self):
        signature = batch.preflight(self.db, self.root, self.names)[1]
        self.journals.mkdir()
        (self.journals / 'bad.pending.json').write_text('{}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'незавершений'):
            batch.do_batch(self.db, self.root, self.names, signature, self.backups, self.journals)
        self.assertFalse(self.backups.exists())

    def write_audit(self, language='uk', overrides=None):
        catalog = json.loads((ROOT.parent / 'myhomelib-ui' / 'src' / 'main' /
                              'resources' / 'lang' / 'default' / f'{language}.json').read_text(encoding='utf-8'))['translations']
        header = [catalog['ui.organizer.author_batch.column.' + key]
                  for key in ('current', 'proposed', 'books', 'status')]
        proposal = catalog['ui.organizer.author_preview.status.proposal']
        rows = [[self.names[0], 'Васильева Алёна', '1', proposal],
                [self.names[1], 'Алёхин Глеб', '1', proposal]]
        if overrides is not None:
            overrides(rows)
        report = self.base / 'audit.csv'
        with report.open('w', encoding='utf-8-sig', newline='') as stream:
            writer = csv.writer(stream, delimiter=';', quoting=csv.QUOTE_ALL)
            writer.writerow(header)
            writer.writerows(rows)
        return report

    def test_csv_report_can_be_revalidated_for_all_supported_languages(self):
        for language in ('uk', 'en', 'bg'):
            audit = self.write_audit(language)
            rows = batch.selected_audit_rows(audit, self.root, self.names)
            plans, signature = batch.preflight(self.db, self.root, self.names)
            batch.validate_audit_rows(plans, rows)
            self.assertEqual(len(rows), 2)
            self.assertEqual(len(signature), 64)

    def test_explicitly_selected_csv_proposals_can_be_applied_offline(self):
        audit = self.write_audit('uk')
        signature = batch.preflight(self.db, self.root, self.names)[1]
        changes = batch.do_batch(self.db, self.root, self.names, signature,
                                 self.backups, self.journals, audit_csv=audit)
        self.assertEqual(len(changes), 2)
        self.assertEqual(self.rows(), [('a', 'Васильева Алёна'), ('b', 'Алёхин Глеб')])
        self.assertEqual(len(list(self.journals.glob('*.done.json'))), 2)

    def test_stale_csv_cannot_authorize_a_batch_write(self):
        audit = self.write_audit(overrides=lambda rows: rows[1].__setitem__(1, 'Невірна назва'))
        signature = batch.preflight(self.db, self.root, self.names)[1]
        with self.assertRaisesRegex(ValueError, 'CSV застарів'):
            batch.do_batch(self.db, self.root, self.names, signature,
                           self.backups, self.journals, audit_csv=audit)
        self.assertFalse(self.backups.exists())
        self.assertEqual(self.rows()[0][1], self.names[0])

    def test_csv_conflict_status_rejected_even_if_name_matches(self):
        audit = self.write_audit(overrides=lambda rows: rows[0].__setitem__(3, 'Конфлікт'))
        with self.assertRaisesRegex(ValueError, 'статусу можливого'):
            batch.selected_audit_rows(audit, self.root, self.names)

    def test_modified_or_missing_csv_names_rejected(self):
        audit = self.write_audit(overrides=lambda rows: rows[1].__setitem__(0, 'Інша папка'))
        with self.assertRaisesRegex(ValueError, 'не містить усіх'):
            batch.selected_audit_rows(audit, self.root, self.names)

    def test_csv_with_duplicate_selection_rows_is_rejected(self):
        audit = self.write_audit(overrides=lambda rows: rows.append(rows[0][:]))
        with self.assertRaisesRegex(ValueError, 'повторну'):
            batch.selected_audit_rows(audit, self.root, self.names)

    def test_sqlite_authors_scanned_once_in_batch_preflight(self):
        scanned = []
        original = batch.repair.candidate_matches
        def observe(db, names):
            db.set_trace_callback(scanned.append)
            return original(db, names)
        with patch.object(batch.repair, 'candidate_matches', side_effect=observe):
            plans, signature = batch.preflight(self.db, self.root, self.names)
        self.assertEqual(len(plans), 2)
        self.assertEqual(sum('SELECT first_name,middle_name,last_name FROM authors' in q for q in scanned), 1)
        self.assertTrue(any('FROM books WHERE folder COLLATE BINARY =' in q for q in scanned))
        self.assertTrue(any('FROM book_artifacts WHERE folder COLLATE BINARY =' in q for q in scanned))
        self.assertFalse(any(q.strip().upper() == 'SELECT ID,COLLECTION_ROOT,FOLDER,FILE_NAME,LOCAL,DELETED FROM BOOKS' for q in scanned))

    def test_source_file_inside_library_rejected(self):
        inside = self.root / 'list.txt'
        inside.write_text(self.names[0], encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'поза бібліотекою'):
            batch.selected_folders(inside, self.root)

    def test_duplicate_names_rejected(self):
        self.list.write_text(self.names[0] + '\n' + self.names[0], encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'повторну'):
            batch.selected_folders(self.list, self.root)

    def test_more_than_ten_rejected(self):
        self.list.write_text('\n'.join('Author ' + str(i) for i in range(11)), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, '10 папок'):
            batch.selected_folders(self.list, self.root)

    def test_backup_under_library_rejected(self):
        signature = batch.preflight(self.db, self.root, self.names)[1]
        with self.assertRaisesRegex(ValueError, 'поза бібліотекою'):
            batch.do_batch(self.db, self.root, self.names, signature, self.root / 'bak', self.journals)

    def test_second_plan_is_rechecked_after_first_folder_commits(self):
        approval = batch.preflight(self.db, self.root, self.names)[1]
        original = batch.repair.apply_one
        def change_next_folder(*args, **kwargs):
            result = original(*args, **kwargs)
            if args[2] == self.names[0]:
                (self.root / self.names[1] / 'b.fb2').write_bytes(b'changed after confirmation')
            return result
        with patch.object(batch.repair, 'apply_one', side_effect=change_next_folder):
            with self.assertRaisesRegex(ValueError, 'Затверджений план змінився'):
                batch.do_batch(self.db, self.root, self.names, approval,
                               self.backups, self.journals)
        self.assertEqual(self.rows()[0][1], 'Васильева Алёна')
        self.assertEqual(self.rows()[1][1], self.names[1])
        self.assertEqual(len(list(self.backups.glob('*.sqlite'))), 1)
        self.assertFalse(list(self.journals.glob('*.pending.json')))

    def test_target_alias_across_sources_rejected(self):
        # If a target is also selected as a source, the preflight refuses the
        # set rather than depending on the order in which moves occur.
        names = self.names[:]
        with patch.object(batch.repair, 'build_plan', side_effect=[
            {'source': 'A', 'target': 'B', 'books': [1], 'artifacts': [], 'hashes': {}},
            {'source': 'B', 'target': 'C', 'books': [2], 'artifacts': [], 'hashes': {}},
        ]):
            with self.assertRaisesRegex(ValueError, 'перетинаються'):
                batch.preflight(self.db, self.root, names)


if __name__ == '__main__':
    unittest.main()
