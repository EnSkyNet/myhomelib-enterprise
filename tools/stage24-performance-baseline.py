#!/usr/bin/env python3
from __future__ import annotations

import argparse
import gc
import json
import os
import platform
import re
import resource
import sqlite3
import statistics
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / 'myhomelib-infrastructure/src/main/resources/db/migration'
DEFAULT_SIZES = [100_000, 500_000, 1_000_000]


def now_ms() -> float:
    return time.perf_counter() * 1000.0


def percentile(values: list[float], pct: float) -> float:
    data = sorted(values)
    if not data:
        return 0.0
    idx = max(0, min(len(data) - 1, round((len(data) - 1) * pct)))
    return data[idx]


def timed(fn, repeats: int = 7, warmups: int = 2) -> dict:
    for _ in range(warmups):
        fn()
    values = []
    for _ in range(repeats):
        start = now_ms()
        fn()
        values.append(now_ms() - start)
    return {
        'min_ms': round(min(values), 3),
        'median_ms': round(statistics.median(values), 3),
        'p95_ms': round(percentile(values, 0.95), 3),
        'max_ms': round(max(values), 3),
    }


def migration_files() -> list[Path]:
    return sorted(MIGRATIONS.glob('V*__*.sql'), key=lambda p: int(re.match(r'V(\d+)__', p.name).group(1)))


def migrate(conn: sqlite3.Connection) -> float:
    start = now_ms()
    for path in migration_files():
        conn.executescript(path.read_text(encoding='utf-8'))
    conn.commit()
    return now_ms() - start


def configure_fixture(conn: sqlite3.Connection) -> None:
    # Fixture generation is setup, not an import benchmark. Keep it fast and deterministic.
    conn.execute('PRAGMA journal_mode=MEMORY')
    conn.execute('PRAGMA synchronous=OFF')
    conn.execute('PRAGMA temp_store=MEMORY')
    conn.execute('PRAGMA cache_size=-65536')
    conn.execute('PRAGMA foreign_keys=OFF')


def configure_queries(conn: sqlite3.Connection) -> None:
    conn.execute('PRAGMA temp_store=MEMORY')
    conn.execute('PRAGMA cache_size=-65536')
    conn.execute('PRAGMA foreign_keys=ON')


def synthesize(conn: sqlite3.Connection, books: int) -> dict:
    """Populate the exact migrated schema quickly; setup throughput is not called import throughput."""
    authors = max(1, books // 3)
    start = now_ms()
    conn.executescript('''
        CREATE TEMP TABLE benchmark_digits(d INTEGER PRIMARY KEY);
        INSERT INTO benchmark_digits VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
        CREATE TEMP TABLE benchmark_nums(n INTEGER PRIMARY KEY);
    ''')
    conn.execute('''INSERT INTO benchmark_nums(n)
        SELECT d0.d + 10*d1.d + 100*d2.d + 1000*d3.d + 10000*d4.d + 100000*d5.d
        FROM benchmark_digits d0, benchmark_digits d1, benchmark_digits d2,
             benchmark_digits d3, benchmark_digits d4, benchmark_digits d5
        WHERE d0.d + 10*d1.d + 100*d2.d + 1000*d3.d + 10000*d4.d + 100000*d5.d < ?''', (books,))
    conn.executemany('INSERT OR IGNORE INTO genres(code,name,fb2_code) VALUES(?,?,?)',
                     ((f'g{i:02d}', f'Genre g{i:02d}', f'g{i:02d}') for i in range(40)))
    conn.execute('''INSERT INTO authors(id,first_name,middle_name,last_name,search_name)
        SELECT printf('a%07d',n), 'First'||(n%1000), '',
               char(65+(n%26))||'uthor'||printf('%07d',n),
               lower(char(65+(n%26))||'uthor'||printf('%07d',n)||' First'||(n%1000))
        FROM benchmark_nums WHERE n < ?''', (authors,))
    conn.execute('''INSERT INTO books(
        id,title,series,sequence_number,file_name,folder,archive_entry,language,file_size,keywords,annotation,
        rate,progress,update_date,isbn,deleted,local,review,collection_root,format,author_sort,publisher,year,lib_id)
        SELECT printf('b%09d',n),
               'Book title '||printf('%08d',n)||' benchmark token'||(n%997),
               CASE WHEN n%5<>0 THEN 'Series '||printf('%05d',n%10000) END,
               CASE WHEN n%5<>0 THEN (n%20)+1 END,
               'book'||printf('%09d',n)||'.'||lower(CASE n%4 WHEN 0 THEN 'FB2' WHEN 1 THEN 'EPUB' WHEN 2 THEN 'PDF' ELSE 'MOBI' END),
               '/library/'||printf('%04d',n%1000),
               CASE WHEN n%10=0 THEN 'book'||printf('%09d',n)||'.fb2' END,
               CASE n%4 WHEN 0 THEN 'uk' WHEN 1 THEN 'en' WHEN 2 THEN 'bg' ELSE 'ru' END,
               100000+n%1000000,
               'keyword'||(n%200)||', topic'||(n%50),
               'Synthetic annotation '||n||' benchmark corpus',
               n%6, CASE WHEN n%13=0 THEN 100 ELSE n%100 END,
               '2026-08-25', '978000'||printf('%06d',n%1000000), 0,
               CASE WHEN n%4=0 THEN 1 ELSE 0 END,
               CASE WHEN n%101=0 THEN 'review' END,
               '/library', CASE n%4 WHEN 0 THEN 'FB2' WHEN 1 THEN 'EPUB' WHEN 2 THEN 'PDF' ELSE 'MOBI' END,
               char(65+((n%?)%26))||'uthor'||printf('%07d',n%?)||' First'||((n%?)%1000),
               'Publisher '||(n%200), 1950+(n%77), 'LIB-'||printf('%09d',n)
        FROM benchmark_nums WHERE n < ?''', (authors, authors, authors, books))
    conn.execute('''INSERT INTO book_authors(book_id,author_id)
        SELECT printf('b%09d',n), printf('a%07d',n%?) FROM benchmark_nums WHERE n < ?''', (authors, books))
    conn.execute('''INSERT INTO book_genres(book_id,genre_code)
        SELECT printf('b%09d',n), 'g'||printf('%02d',n%40) FROM benchmark_nums WHERE n < ?''', (books,))
    conn.commit()
    conn.execute('PRAGMA optimize')
    conn.commit()
    fixture_ms = now_ms() - start
    return {
        'authors': authors,
        'fixture_generation_ms': round(fixture_ms, 3),
        'fixture_books_per_sec': round(books / (fixture_ms / 1000.0), 1) if fixture_ms else None,
    }


def import_probe(conn: sqlite3.Connection, base_books: int, rows: int = 20_000) -> dict:
    """Prepared batched writes against the indexed real schema; rollback keeps the measured catalogue size stable."""
    authors = max(1, base_books // 3)
    insert_book = '''INSERT INTO books(
        id,title,file_name,folder,language,file_size,keywords,annotation,rate,progress,update_date,deleted,local,
        collection_root,format,author_sort,publisher,year,lib_id)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)'''
    insert_ba = 'INSERT INTO book_authors(book_id,author_id) VALUES(?,?)'
    start_id = base_books + 10_000_000
    conn.execute('BEGIN')
    start = now_ms()
    for lo in range(0, rows, 1000):
        hi = min(rows, lo + 1000)
        books_batch, ba_batch = [], []
        for offset in range(lo, hi):
            i = start_id + offset
            aid = i % authors
            initial = chr(ord('A') + (aid % 26))
            author_sort = f'{initial}uthor{aid:07d} First{aid%1000}'
            books_batch.append((
                f'p{i:09d}', f'Import probe {i}', f'probe{i}.fb2', '/probe', 'uk', 123456,
                'benchmark,import', 'Import throughput probe', 0, 0, '2026-08-25', 0, 0,
                '/probe', 'FB2', author_sort, 'Benchmark Publisher', 2026, f'PROBE-{i:09d}'
            ))
            ba_batch.append((f'p{i:09d}', f'a{aid:07d}'))
        conn.executemany(insert_book, books_batch)
        conn.executemany(insert_ba, ba_batch)
    elapsed = now_ms() - start
    conn.rollback()
    return {
        'rows': rows,
        'elapsed_ms': round(elapsed, 3),
        'books_per_sec': round(rows / (elapsed / 1000.0), 1) if elapsed else None,
    }


def scalar(conn: sqlite3.Connection, sql: str, params=()):
    return conn.execute(sql, params).fetchone()[0]


def run_queries(conn: sqlite3.Connection, repeats: int = 7) -> dict:
    author_initial = '''
        SELECT a.id, a.first_name, a.middle_name, a.last_name, COUNT(DISTINCT b.id)
        FROM authors a
        JOIN book_authors ba ON ba.author_id=a.id
        JOIN books b ON b.id=ba.book_id
        WHERE b.deleted=0
          AND SUBSTR((CASE WHEN TRIM(COALESCE(a.last_name,''))<>'' THEN TRIM(a.last_name)
                           WHEN TRIM(COALESCE(a.first_name,''))<>'' THEN TRIM(a.first_name)
                           ELSE TRIM(COALESCE(a.middle_name,'')) END),1,1) IN (?,?)
        GROUP BY a.id,a.first_name,a.middle_name,a.last_name
        ORDER BY COALESCE(a.last_name,'') COLLATE NOCASE, COALESCE(a.first_name,'') COLLATE NOCASE, a.id
    '''
    language_facet = '''
        SELECT LOWER(TRIM(b.language)), COUNT(*)
        FROM books b
        WHERE b.deleted=0 AND b.language IS NOT NULL AND TRIM(b.language)<>''
        GROUP BY LOWER(TRIM(b.language)) ORDER BY LOWER(TRIM(b.language))
    '''
    filtered_author = author_initial.replace('GROUP BY', "AND LOWER(TRIM(b.language))='uk' AND b.local=1 GROUP BY")
    page_title = "SELECT id,title,author_sort FROM books WHERE deleted=0 ORDER BY title ASC,id ASC LIMIT 100 OFFSET 0"
    deep_page = "SELECT id,title,author_sort FROM books WHERE deleted=0 ORDER BY title ASC,id ASC LIMIT 100 OFFSET 50000"
    filtered_page = "SELECT id,title FROM books WHERE deleted=0 AND LOWER(TRIM(language))='uk' AND local=1 AND year BETWEEN 1990 AND 2026 ORDER BY title,id LIMIT 100"
    text_like = "SELECT id,title FROM books WHERE deleted=0 AND LOWER(title) LIKE ? ORDER BY title,id LIMIT 100"
    libid = "SELECT id FROM books WHERE lib_id=?"
    return {
        'navigation_authors_A': timed(lambda: conn.execute(author_initial, ('A', 'a')).fetchall(), repeats=repeats, warmups=1),
        'navigation_authors_A_filtered': timed(lambda: conn.execute(filtered_author, ('A', 'a')).fetchall(), repeats=repeats, warmups=1),
        'navigation_languages': timed(lambda: conn.execute(language_facet).fetchall(), repeats=repeats, warmups=1),
        'catalog_first_page': timed(lambda: conn.execute(page_title).fetchall(), repeats=repeats, warmups=1),
        'catalog_deep_page_offset_50k': timed(lambda: conn.execute(deep_page).fetchall(), repeats=repeats, warmups=1),
        'catalog_filtered_page': timed(lambda: conn.execute(filtered_page).fetchall(), repeats=repeats, warmups=1),
        'sql_text_fallback_search': timed(lambda: conn.execute(text_like, ('%benchmark token42%',)).fetchall(), repeats=min(repeats, 5), warmups=1),
        'libid_lookup': timed(lambda: conn.execute(libid, ('LIB-000050000',)).fetchall(), repeats=repeats, warmups=1),
    }


def plans(conn: sqlite3.Connection) -> dict:
    sqls = {
        'author_initial': '''SELECT a.id FROM authors a JOIN book_authors ba ON ba.author_id=a.id JOIN books b ON b.id=ba.book_id
            WHERE b.deleted=0 AND SUBSTR((CASE WHEN TRIM(COALESCE(a.last_name,''))<>'' THEN TRIM(a.last_name)
            WHEN TRIM(COALESCE(a.first_name,''))<>'' THEN TRIM(a.first_name) ELSE TRIM(COALESCE(a.middle_name,'')) END),1,1) IN ('A','a') GROUP BY a.id''',
        'title_page': "SELECT id,title FROM books WHERE deleted=0 ORDER BY title,id LIMIT 100",
        'libid': "SELECT id FROM books WHERE lib_id='LIB-000050000'",
    }
    out = {}
    for name, sql in sqls.items():
        out[name] = [' | '.join(str(x) for x in row) for row in conn.execute('EXPLAIN QUERY PLAN ' + sql)]
    return out


def benchmark_size(size: int, keep_db: Path | None) -> dict:
    if keep_db:
        keep_db.mkdir(parents=True, exist_ok=True)
        db = keep_db / f'synthetic-{size}.db'
        if db.exists():
            db.unlink()
        td = None
    else:
        td = tempfile.TemporaryDirectory()
        db = Path(td.name) / f'synthetic-{size}.db'
    conn = sqlite3.connect(db)
    configure_fixture(conn)
    migration_ms = migrate(conn)
    fixture = synthesize(conn, size)
    import_write_probe = import_probe(conn, size)
    conn.commit()
    conn.close()

    startup_runs = []
    for _ in range(7):
        start = now_ms()
        c = sqlite3.connect(db)
        c.execute('PRAGMA query_only=ON')
        c.execute('SELECT COUNT(*) FROM books WHERE deleted=0').fetchone()
        c.close()
        startup_runs.append(now_ms() - start)

    conn = sqlite3.connect(db)
    configure_queries(conn)
    query = run_queries(conn, repeats=3 if size >= 1_000_000 else 5)
    plan = plans(conn)
    integrity = scalar(conn, 'PRAGMA quick_check') if size <= 500_000 else 'skipped-large-performance-fixture'
    db_bytes = db.stat().st_size
    pages = scalar(conn, 'PRAGMA page_count')
    page_size = scalar(conn, 'PRAGMA page_size')
    conn.close()

    peak_rss_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    result = {
        'books': size,
        'migrations_ms': round(migration_ms, 3),
        'fixture_generation': fixture,
        'import_write_probe': import_write_probe,
        'startup_like_open_count': {
            'min_ms': round(min(startup_runs), 3),
            'median_ms': round(statistics.median(startup_runs), 3),
            'p95_ms': round(percentile(startup_runs, 0.95), 3),
            'max_ms': round(max(startup_runs), 3),
        },
        'queries': query,
        'plans': plan,
        'database': {'bytes': db_bytes, 'pages': pages, 'page_size': page_size, 'integrity': integrity},
        'process_peak_rss_kb': peak_rss_kb,
    }
    if td:
        td.cleanup()
    gc.collect()
    return result


def evaluate_thresholds(results: list[dict]) -> dict:
    # CI regression guardrails; intentionally looser than this machine's baseline.
    thresholds = {
        '100000': {'startup_p95_ms': 250, 'author_A_p95_ms': 500, 'first_page_p95_ms': 250, 'libid_p95_ms': 50, 'min_import_books_s': 3_000},
        '500000': {'startup_p95_ms': 500, 'author_A_p95_ms': 1_500, 'first_page_p95_ms': 500, 'libid_p95_ms': 75, 'min_import_books_s': 2_500},
        '1000000': {'startup_p95_ms': 900, 'author_A_p95_ms': 3_000, 'first_page_p95_ms': 900, 'libid_p95_ms': 100, 'min_import_books_s': 2_000},
    }
    checks = []
    for r in results:
        key = str(r['books'])
        t = thresholds.get(key)
        if not t:
            continue
        observed = {
            'startup_p95_ms': r['startup_like_open_count']['p95_ms'],
            'author_A_p95_ms': r['queries']['navigation_authors_A']['p95_ms'],
            'first_page_p95_ms': r['queries']['catalog_first_page']['p95_ms'],
            'libid_p95_ms': r['queries']['libid_lookup']['p95_ms'],
            'min_import_books_s': r['import_write_probe']['books_per_sec'],
        }
        for metric, limit in t.items():
            actual = observed[metric]
            ok = actual >= limit if metric.startswith('min_') else actual <= limit
            checks.append({'books': r['books'], 'metric': metric, 'actual': actual, 'threshold': limit, 'pass': ok})
    return {'thresholds': thresholds, 'checks': checks, 'pass': all(c['pass'] for c in checks)}


def main() -> None:
    ap = argparse.ArgumentParser(description='Stage 24 deterministic SQLite scale baseline')
    ap.add_argument('--sizes', default=','.join(str(x) for x in DEFAULT_SIZES))
    ap.add_argument('--out', default=str(ROOT / 'docs/performance-baseline.json'))
    ap.add_argument('--keep-db-dir')
    args = ap.parse_args()
    sizes = [int(x.strip()) for x in args.sizes.split(',') if x.strip()]
    keep = Path(args.keep_db_dir) if args.keep_db_dir else None
    started = time.time()
    results = []
    for size in sizes:
        print(f'[stage24] benchmarking {size:,} books...', flush=True)
        results.append(benchmark_size(size, keep))
        q = results[-1]['queries']
        print(f"  import-probe={results[-1]['import_write_probe']['books_per_sec']:,.0f} books/s, "
              f"authors(A) p95={q['navigation_authors_A']['p95_ms']:.1f} ms, "
              f"page p95={q['catalog_first_page']['p95_ms']:.1f} ms", flush=True)

    report = {
        'schema': 1,
        'kind': 'offline-sqlite-scale-baseline',
        'note': 'SQL/import scale baseline executed with CPython sqlite3. JVM heap/GC, Lucene, and reader metrics belong to the Java benchmark suite and are not inferred from this process.',
        'environment': {
            'python': platform.python_version(),
            'sqlite': sqlite3.sqlite_version,
            'os': platform.platform(),
            'machine': platform.machine(),
            'cpu_count': os.cpu_count(),
        },
        'migration_count': len(migration_files()),
        'duration_seconds': round(time.time() - started, 3),
        'results': results,
    }
    report['guardrails'] = evaluate_thresholds(results)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'[stage24] guardrails: {"PASS" if report["guardrails"]["pass"] else "FAIL"}')
    print(f'[stage24] report: {out}')
    raise SystemExit(0 if report['guardrails']['pass'] else 2)


if __name__ == '__main__':
    main()
