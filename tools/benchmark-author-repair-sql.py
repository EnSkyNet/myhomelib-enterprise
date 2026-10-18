#!/usr/bin/env python3
"""Reproducible synthetic SQLite-only probe. Never opens a user's library.

Compares the stage-10 per-book operations with the stage-11 bounded versions.
This is NOT a prediction of Windows/D: throughput (no real disk I/O).
"""
from __future__ import annotations

from contextlib import closing
import importlib.util
import json
from pathlib import Path
import sqlite3
import statistics
import time

REPAIR = Path(__file__).resolve().with_name('repair-author-folder.py')
spec = importlib.util.spec_from_file_location('bench_author_repair', REPAIR)
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


OLD_FOLDER_QUERY = ("SELECT id FROM books WHERE folder = ? OR substr(folder, 1, ?) = ?")


def median_time(fn, loops=5):
    samples = []
    for _ in range(loops):
        begin = time.perf_counter()
        fn()
        samples.append(round((time.perf_counter() - begin) * 1000, 3))
    return round(statistics.median(samples), 3)


def stage10_authors(db, identifiers, expected):
    for book_id in identifiers:
        authors = db.execute(
            'SELECT a.first_name,a.middle_name,a.last_name FROM book_authors ba '
            'JOIN authors a ON a.id=ba.author_id WHERE ba.book_id=?',
            (book_id,),
        ).fetchall()
        if not any(repair.norm(' '.join(x for x in (repair.norm(last), repair.norm(first),
                                                 repair.norm(middle)) if x)) == expected
                   for first, middle, last in authors):
            raise RuntimeError('bad synthetic test data')


def stage10_sync(db, plan):
    for table, field, ids in (('books', 'id', plan['books']), ('book_artifacts', 'artifact_id', plan['artifacts'])):
        for identifier in ids:
            row = db.execute(f'SELECT collection_root,folder FROM {table} WHERE {field}=?',
                             (identifier,)).fetchone()
            if row is None or not repair.matching_root(row[0], Path(plan['root'])):
                raise RuntimeError('bad synthetic test data')
            folder = repair.replace_prefix(row[1], plan['source'], plan['target'])
            updated = db.execute(f'UPDATE {table} SET folder=? WHERE {field}=? AND folder=?',
                                 (folder, identifier, row[1])).rowcount
            if updated != 1:
                raise RuntimeError('bad synthetic test data')


def main():
    # Small enough for CI; many unrelated catalog rows to exercise folder scans.
    total, selected = 70_000, 1_200
    old, new = 'Алёна_% Васильева', 'Васильева Алёна'
    root = '/synthetic/root'
    with closing(sqlite3.connect(':memory:')) as db:
        db.executescript('''
            CREATE TABLE authors(id TEXT PRIMARY KEY, first_name TEXT, middle_name TEXT, last_name TEXT);
            CREATE TABLE book_authors(book_id TEXT, author_id TEXT);
            CREATE TABLE books(id TEXT PRIMARY KEY, collection_root TEXT, folder TEXT,
                file_name TEXT, local INTEGER, deleted INTEGER);
            CREATE TABLE book_artifacts(artifact_id TEXT PRIMARY KEY, collection_root TEXT,
                folder TEXT, file_name TEXT, archive_name TEXT, local INTEGER);
            CREATE INDEX idx_books_folder ON books(folder);
            CREATE INDEX idx_artifacts_folder ON book_artifacts(folder);
            CREATE INDEX idx_book_authors_book ON book_authors(book_id);
        ''')
        db.execute('INSERT INTO authors VALUES (?,?,?,?)', ('a', 'Алёна', '', 'Васильева'))
        batches = 1000
        for start in range(0, total, batches):
            ids = range(start, min(start + batches, total))
            db.executemany('INSERT INTO books VALUES (?,?,?,?,?,?)',
                           [(str(i), root, old if i < selected else f'Other{i}', 'book.zip', 1, 0)
                            for i in ids])
            db.executemany('INSERT INTO book_artifacts VALUES (?,?,?,?,?,?)',
                           [(str(i), root, old if i < selected else f'Other{i}', 'book.zip', None, 1)
                            for i in ids])
            db.executemany('INSERT INTO book_authors VALUES (?,?)',
                           [(str(i), 'a') for i in ids if i < selected])
        db.commit()
        ids = [str(i) for i in range(selected)]
        plan = dict(root=root, source=old, target=new, books=ids, artifacts=ids)

        def old_folder():
            return list(db.execute(OLD_FOLDER_QUERY, (old, len(old) + 1, old + '/')))
        def new_folder():
            return list(repair.catalog_folder_rows(db, 'books', 'id', old))
        assert {x[0] for x in old_folder()} == {x[0] for x in new_folder()} == set(ids)

        def timed_transaction(operation):
            db.execute('BEGIN')
            try:
                operation(db, plan)
            finally:
                db.execute('ROLLBACK')

        result = {
            'dataset': 'synthetic SQLite :memory:',
            'catalog_book_rows': total,
            'selected_book_rows': selected,
            'stage10_folder_query_median_ms': median_time(old_folder),
            'stage11_folder_query_median_ms': median_time(new_folder),
            'stage10_author_lookup_median_ms': median_time(lambda: stage10_authors(db, ids, new)),
            'stage11_author_lookup_median_ms': median_time(lambda: repair.validate_book_authors(db, ids, new)),
            'stage10_folder_sync_median_ms': median_time(lambda: timed_transaction(stage10_sync), 3),
            'stage11_folder_sync_median_ms': median_time(lambda: timed_transaction(repair.sync_plan), 3),
            'planned_stage10_author_selects': selected,
            'planned_stage11_author_selects': (selected + repair.SQL_BATCH_SIZE - 1)//repair.SQL_BATCH_SIZE,
            'planned_stage10_path_updates': selected * 2,
            'planned_stage11_path_updates': ((selected + repair.SQL_BATCH_SIZE - 1)//repair.SQL_BATCH_SIZE) * 2,
        }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
