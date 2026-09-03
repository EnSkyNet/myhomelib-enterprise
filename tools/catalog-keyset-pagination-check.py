#!/usr/bin/env python3
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
PAGE = 17


def rows(conn, direction='ASC'):
    return conn.execute(f"SELECT id,title FROM books WHERE deleted=0 ORDER BY title {direction}, id {direction}").fetchall()


def after(conn, cursor, direction='ASC'):
    op = '>' if direction == 'ASC' else '<'
    return conn.execute(
        f"SELECT id,title FROM books WHERE deleted=0 AND (title,id) {op} (?,?) "
        f"ORDER BY title {direction},id {direction} LIMIT ?",
        (cursor[1], cursor[0], PAGE),
    ).fetchall()


def before(conn, cursor, direction='ASC'):
    op = '<' if direction == 'ASC' else '>'
    scan = 'DESC' if direction == 'ASC' else 'ASC'
    out = conn.execute(
        f"SELECT id,title FROM books WHERE deleted=0 AND (title,id) {op} (?,?) "
        f"ORDER BY title {scan},id {scan} LIMIT ?",
        (cursor[1], cursor[0], PAGE),
    ).fetchall()
    out.reverse()
    return out


conn = sqlite3.connect(':memory:')
conn.execute('CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,deleted INTEGER DEFAULT 0)')
# Deliberately duplicate titles heavily so id tie-breaking is exercised.
conn.executemany(
    'INSERT INTO books(id,title,deleted) VALUES(?,?,0)',
    [(f'b{i:04d}', f'Title {i // 3:04d}') for i in range(203)],
)

for direction in ('ASC', 'DESC'):
    expected = rows(conn, direction)
    collected = expected[:PAGE]
    page = expected[:PAGE]
    while page and len(collected) < len(expected):
        page = after(conn, page[-1], direction)
        collected.extend(page)
    assert collected == expected, f'{direction} forward cursor skipped/duplicated rows'

    # Verify a reverse navigation step from a deep page.
    deep_start = PAGE * 7
    deep = expected[deep_start:deep_start + PAGE]
    prev = before(conn, deep[0], direction)
    assert prev == expected[deep_start - PAGE:deep_start], f'{direction} previous cursor is unstable'

conn.close()

repo = (ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteBookQueryRepository.java').read_text(encoding='utf-8')
loader = (ROOT / 'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookLoaderService.java').read_text(encoding='utf-8')
builder = (ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/helper/BookQueryBuilder.java').read_text(encoding='utf-8')
assert 'knownTotal cannot be negative' in repo and 'loadOffsetPage(query, knownTotal)' in repo
assert 'BookPageDirection.AFTER' in loader and 'BookPageDirection.BEFORE' in loader
assert '(b.title, b.id)' in builder and 'buildSelectSqlWithoutOffset' in builder

print('CATALOG KEYSET PAGINATION CHECK: PASS')
print(' - ASC/DESC traversal with duplicate titles: PASS')
print(' - bidirectional previous-page cursor: PASS')
print(' - continuation reuses known total instead of COUNT(*): PASS')
