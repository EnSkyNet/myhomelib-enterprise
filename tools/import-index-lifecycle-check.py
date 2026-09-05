#!/usr/bin/env python3
from __future__ import annotations

import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / 'myhomelib-infrastructure/src/main/resources/db/migration'
PIPELINE = ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxImportPipeline.java'
LIFECYCLE = ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/ImportIndexLifecycle.java'
CORE = ('idx_books_title', 'idx_books_series', 'idx_authors_last_name')
INITIAL_EXTRA = (
    'idx_books_language', 'idx_books_created_at', 'idx_books_update_date', 'idx_books_rate',
    'idx_books_title_lower', 'idx_books_format', 'idx_books_author_sort', 'idx_books_collection_root',
    'idx_books_publisher', 'idx_books_year', 'idx_books_lib_id', 'idx_books_library_rate',
    'idx_books_missing_since', 'idx_books_active_language_title', 'idx_books_active_id',
    'idx_book_authors_author_id', 'idx_book_genres_genre_code',
)

errors: list[str] = []

def need(cond: bool, message: str) -> None:
    if not cond:
        errors.append(message)

def migration_files() -> list[Path]:
    def version(path: Path) -> int:
        m = re.match(r'V(\d+)__', path.name)
        return int(m.group(1)) if m else 10**9
    return sorted(MIGRATIONS.glob('V*__*.sql'), key=version)

def list_literal(source: str, field: str) -> tuple[str, ...]:
    m = re.search(rf'{field}\s*=\s*List\.of\((.*?)\);', source, re.S)
    if not m:
        errors.append(f'{field} list missing')
        return ()
    return tuple(re.findall(r'"([^"]+)"', m.group(1)))

pipeline = PIPELINE.read_text(encoding='utf-8')
source = LIFECYCLE.read_text(encoding='utf-8')
need('importIndexLifecycle.suspendForFullSnapshot(fastInitialBaseline)' in pipeline
     and 'importIndexLifecycle.restore(suspendedIndexes)' in pipeline,
     'pipeline must delegate index lifecycle with initial-baseline scope')
need('sqlite_master' in source, 'index lifecycle must capture live definitions from sqlite_master')
need('definition.createSql()' in source, 'index lifecycle must restore the exact captured CREATE INDEX SQL')
need('CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)' not in source,
     'index lifecycle must not hard-code restore SQL')
need('idx_authors_name_lookup' not in INITIAL_EXTRA,
     'exact author lookup index must remain live for author-cache eviction fallback')
need('idx_keyword_books_book_id' not in INITIAL_EXTRA,
     'keyword book-id index must remain live for idempotent keyword replacement')

core = list_literal(source, 'FULL_SNAPSHOT_SUSPEND_INDEXES')
extra = list_literal(source, 'INITIAL_BASELINE_EXTRA_SUSPEND_INDEXES')
need(core == CORE, f'full-snapshot suspend indexes changed unexpectedly: {core!r}')
need(extra == INITIAL_EXTRA, f'initial-baseline extra indexes changed unexpectedly: {extra!r}')
configured = core + extra
need(len(configured) == len(set(configured)), 'suspend lists must not contain duplicates')

conn = sqlite3.connect(':memory:')
try:
    for migration in migration_files():
        conn.executescript(migration.read_text(encoding='utf-8'))
    conn.commit()

    definitions: dict[str, str] = {}
    for name in configured:
        row = conn.execute("SELECT sql FROM sqlite_master WHERE type='index' AND name=?", (name,)).fetchone()
        need(row is not None and row[0], f'configured index is absent from migrated V48 schema: {name}')
        if row and row[0]:
            definitions[name] = row[0]

    # Composite relation PK auto-indexes remain live while the reverse lookup indexes may be suspended.
    ba = conn.execute("PRAGMA index_list('book_authors')").fetchall()
    bg = conn.execute("PRAGMA index_list('book_genres')").fetchall()
    need(any(str(row[1]).startswith('sqlite_autoindex_book_authors') for row in ba),
         'book_authors PK auto-index missing')
    need(any(str(row[1]).startswith('sqlite_autoindex_book_genres') for row in bg),
         'book_genres PK auto-index missing')

    for name in definitions:
        conn.execute(f'DROP INDEX IF EXISTS "{name}"')
    conn.commit()
    for name, sql in definitions.items():
        need(conn.execute("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", (name,)).fetchone() is None,
             f'index did not drop: {name}')
        conn.execute(sql)
    conn.commit()
    for name, original_sql in definitions.items():
        row = conn.execute("SELECT sql FROM sqlite_master WHERE type='index' AND name=?", (name,)).fetchone()
        need(row is not None and row[0] == original_sql, f'exact index definition was not restored: {name}')
finally:
    conn.close()

if errors:
    print('IMPORT INDEX LIFECYCLE CHECK: FAILED')
    for error in errors:
        print(' -', error)
    sys.exit(1)

print('IMPORT INDEX LIFECYCLE CHECK: PASS')
print(' - existing full snapshots suspend only the conservative 3-index set')
print(' - empty initial baselines additionally suspend 17 pure write-amplification indexes')
print(' - author/keyword lookup indexes stay live and all captured definitions restore exactly')
