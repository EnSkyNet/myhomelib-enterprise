#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import platform
import runpy
import sqlite3
import statistics
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STAGE24 = runpy.run_path(str(ROOT / 'tools/stage24-performance-baseline.py'))
configure_fixture = STAGE24['configure_fixture']
migrate = STAGE24['migrate']
synthesize = STAGE24['synthesize']

BATCHES = (500, 1000, 5000, 10000)
SUSPENDED_INDEXES = ('idx_books_title', 'idx_books_series', 'idx_authors_last_name')

INSERT_BOOK = '''INSERT INTO books(
    id,title,file_name,folder,language,file_size,keywords,annotation,rate,progress,update_date,deleted,local,
    collection_root,format,author_sort,publisher,year,lib_id)
    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)'''
INSERT_BOOK_AUTHOR = 'INSERT INTO book_authors(book_id,author_id) VALUES(?,?)'


def configure_bulk_runtime(conn: sqlite3.Connection) -> None:
    # Match the production datasource + current bulk optimizer durability/cache policy as closely as CPython sqlite permits.
    conn.execute('PRAGMA journal_mode=WAL')
    conn.execute('PRAGMA synchronous=NORMAL')
    conn.execute('PRAGMA temp_store=MEMORY')
    conn.execute('PRAGMA cache_size=-262144')
    conn.execute('PRAGMA mmap_size=2147483648')
    conn.execute('PRAGMA foreign_keys=ON')


def capture_index_definitions(conn: sqlite3.Connection) -> dict[str, str]:
    definitions: dict[str, str] = {}
    for name in SUSPENDED_INDEXES:
        row = conn.execute(
            "SELECT sql FROM sqlite_master WHERE type='index' AND name=? AND sql IS NOT NULL", (name,)
        ).fetchone()
        if row is None or not row[0]:
            raise RuntimeError(f'Expected import index is missing: {name}')
        definitions[name] = row[0]
    return definitions


def set_indexes_suspended(conn: sqlite3.Connection, suspended: bool, definitions: dict[str, str]) -> None:
    if suspended:
        for name in definitions:
            conn.execute(f'DROP INDEX IF EXISTS "{name}"')
    else:
        for name, sql in definitions.items():
            row = conn.execute("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", (name,)).fetchone()
            if row is None:
                conn.execute(sql)
    conn.commit()


def probe(conn: sqlite3.Connection, base_books: int, rows: int, batch_size: int, seed: int) -> float:
    authors = max(1, base_books // 3)
    start_id = base_books + 50_000_000 + seed * rows
    conn.execute('BEGIN')
    start = time.perf_counter()
    try:
        for lo in range(0, rows, batch_size):
            hi = min(rows, lo + batch_size)
            books_batch: list[tuple] = []
            author_links: list[tuple] = []
            for offset in range(lo, hi):
                i = start_id + offset
                author_id = i % authors
                initial = chr(ord('A') + (author_id % 26))
                author_sort = f'{initial}uthor{author_id:07d} First{author_id % 1000}'
                book_id = f'z{i:09d}'
                books_batch.append((
                    book_id, f'Import probe {i}', f'probe{i}.fb2', '/probe', 'uk', 123456,
                    'benchmark,import', 'Import throughput probe', 0, 0, '2026-08-30', 0, 0,
                    '/probe', 'FB2', author_sort, 'Benchmark Publisher', 2026, f'BATCH-{i:09d}'
                ))
                author_links.append((book_id, f'a{author_id:07d}'))
            conn.executemany(INSERT_BOOK, books_batch)
            conn.executemany(INSERT_BOOK_AUTHOR, author_links)
        return (time.perf_counter() - start) * 1000.0
    finally:
        conn.rollback()


def benchmark_size(size: int, probe_rows: int, repeats: int) -> list[dict]:
    with tempfile.TemporaryDirectory() as td:
        db = Path(td) / f'inpx-batch-{size}.db'
        conn = sqlite3.connect(db)
        configure_fixture(conn)
        migrate(conn)
        synthesize(conn, size)
        conn.commit()
        conn.close()

        conn = sqlite3.connect(db)
        configure_bulk_runtime(conn)
        definitions = capture_index_definitions(conn)
        results: list[dict] = []
        try:
            for suspended in (False, True):
                set_indexes_suspended(conn, suspended, definitions)
                for batch_size in BATCHES:
                    runs = [
                        probe(conn, size, probe_rows, batch_size, seed=repeat + batch_size)
                        for repeat in range(repeats)
                    ]
                    median_ms = statistics.median(runs)
                    results.append({
                        'books': size,
                        'probe_rows': probe_rows,
                        'indexes_suspended': suspended,
                        'batch_size': batch_size,
                        'median_ms': round(median_ms, 3),
                        'rows_per_sec': round(probe_rows / (median_ms / 1000.0), 1),
                        'runs_ms': [round(value, 3) for value in runs],
                    })
        finally:
            set_indexes_suspended(conn, False, definitions)
            conn.close()
        return results


def recommendation(results: list[dict]) -> dict:
    # Default selection is based on indexes-intact mode because it is the safer lower-bound path.
    intact = [row for row in results if not row['indexes_suspended']]
    grouped: dict[int, list[float]] = {}
    for row in intact:
        grouped.setdefault(int(row['batch_size']), []).append(float(row['rows_per_sec']))
    medians = {batch: statistics.median(values) for batch, values in grouped.items()}
    best = max(medians, key=medians.get)
    # Prefer 1000 when it is within 5% of the measured winner: it bounds Java-side allocations better than 5k/10k
    # without returning to the legacy 500 default unnecessarily.
    selected = 1000 if medians.get(1000, 0.0) >= medians[best] * 0.95 else best
    return {
        'selected_default_batch_size': selected,
        'indexes_intact_rows_per_sec_median_by_batch': {str(k): round(v, 1) for k, v in sorted(medians.items())},
        'selection_rule': 'choose 1000 when within 5% of measured winner; otherwise choose measured winner',
        'scope_note': 'SQLite-only evidence. JVM heap/GC and JdbcTemplate batch allocation still require Maven/JVM benchmark before final release acceptance.',
    }


def main() -> None:
    ap = argparse.ArgumentParser(description='Offline INPX batch-size/index-maintenance probe')
    ap.add_argument('--sizes', default='100000,500000')
    ap.add_argument('--probe-rows', type=int, default=12000)
    ap.add_argument('--repeats', type=int, default=5)
    ap.add_argument('--out', default=str(ROOT / 'docs/release/PERFORMANCE-v7.1-INPX-BATCH-RAW.json'))
    args = ap.parse_args()
    sizes = [int(part.strip()) for part in args.sizes.split(',') if part.strip()]

    started = time.time()
    results: list[dict] = []
    for size in sizes:
        print(f'[inpx-batch] benchmarking {size:,} books...', flush=True)
        results.extend(benchmark_size(size, args.probe_rows, args.repeats))

    report = {
        'schema': 1,
        'kind': 'myhomelib-v7.1-inpx-batch-index-offline-sqlite-evidence',
        'date': '2026-08-30',
        'environment': {
            'python': platform.python_version(),
            'sqlite': sqlite3.sqlite_version,
            'os': platform.platform(),
            'machine': platform.machine(),
            'cpu_count': os.cpu_count(),
        },
        'settings': {
            'journal_mode': 'WAL',
            'synchronous': 'NORMAL',
            'temp_store': 'MEMORY',
            'cache_size': -262144,
            'mmap_size': 2147483648,
            'batches': list(BATCHES),
            'suspended_indexes': list(SUSPENDED_INDEXES),
        },
        'duration_seconds': round(time.time() - started, 3),
        'results': results,
    }
    report['recommendation'] = recommendation(results)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('[inpx-batch] selected default:', report['recommendation']['selected_default_batch_size'])
    print('[inpx-batch] report:', out)


if __name__ == '__main__':
    main()
