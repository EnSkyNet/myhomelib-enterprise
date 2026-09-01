#!/usr/bin/env python3
"""Read-only SQLite size report for a MyHomeLib collection database."""
from __future__ import annotations
import argparse
import sqlite3
from pathlib import Path


def human(n: int) -> str:
    units = ['B','KiB','MiB','GiB','TiB']
    x=float(max(0,n))
    for u in units:
        if x < 1024 or u == units[-1]:
            return f'{x:.1f} {u}' if u != 'B' else f'{int(x)} B'
        x/=1024
    return f'{x:.1f} TiB'


def main() -> int:
    ap=argparse.ArgumentParser(description='MyHomeLib SQLite size report (read-only)')
    ap.add_argument('database', type=Path)
    ap.add_argument('--top', type=int, default=25)
    args=ap.parse_args()
    db=args.database.expanduser().resolve()
    if not db.is_file():
        raise SystemExit(f'File not found: {db}')
    uri='file:' + db.as_posix() + '?mode=ro'
    conn=sqlite3.connect(uri, uri=True)
    try:
        page_size=int(conn.execute('PRAGMA page_size').fetchone()[0])
        page_count=int(conn.execute('PRAGMA page_count').fetchone()[0])
        freelist=int(conn.execute('PRAGMA freelist_count').fetchone()[0])
        logical=page_size*page_count
        free=page_size*freelist
        print(f'Database: {db}')
        print(f'File: {human(db.stat().st_size)}')
        print(f'Pages: {page_count:,} x {page_size:,} = {human(logical)}')
        print(f'Freelist: {freelist:,} pages = {human(free)} ({(100*freelist/page_count if page_count else 0):.1f}%)')
        for suffix in ('-wal','-shm'):
            p=Path(str(db)+suffix)
            if p.exists(): print(f'{p.name}: {human(p.stat().st_size)}')
        print('\nLargest SQLite objects (dbstat):')
        try:
            rows=conn.execute('''SELECT name, SUM(pgsize) AS bytes, COUNT(*) AS pages
                                 FROM dbstat GROUP BY name ORDER BY bytes DESC LIMIT ?''',
                              (max(1,args.top),)).fetchall()
            for name, size, pages in rows:
                print(f'  {human(int(size or 0)):>11}  {int(pages):>8,} pages  {name}')
        except sqlite3.DatabaseError as e:
            print(f'  dbstat unavailable: {e}')
        print('\nLarge-table row counts:')
        names={r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for table in ('books','authors','book_authors','book_genres','catalog_book_state','book_search_state',
                      'catalog_record_provenance','book_source_relations','artifact_occurrences'):
            if table in names:
                count=conn.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
                print(f'  {table:<28} {count:>12,}')
    finally:
        conn.close()
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
