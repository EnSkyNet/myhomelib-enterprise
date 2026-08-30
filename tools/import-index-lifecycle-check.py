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
EXPECTED = ('idx_books_title', 'idx_books_series', 'idx_authors_last_name')

errors: list[str] = []

def need(cond: bool, message: str) -> None:
    if not cond:
        errors.append(message)

def migration_files() -> list[Path]:
    def version(path: Path) -> int:
        m = re.match(r'V(\d+)__', path.name)
        return int(m.group(1)) if m else 10**9
    return sorted(MIGRATIONS.glob('V*__*.sql'), key=version)

pipeline = PIPELINE.read_text(encoding='utf-8')
source = LIFECYCLE.read_text(encoding='utf-8')
need('importIndexLifecycle.suspendForFullSnapshot()' in pipeline and 'importIndexLifecycle.restore(suspendedIndexes)' in pipeline, 'pipeline must delegate index lifecycle to focused component')
need('sqlite_master' in source, 'index lifecycle must capture live definitions from sqlite_master')
need('definition.createSql()' in source, 'index lifecycle must restore the exact captured CREATE INDEX SQL')
need('CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)' not in source,
     'index lifecycle must not hard-code the restore SQL for idx_books_title')
need('idx_book_authors_book_author' not in source and 'idx_book_genres_book_genre' not in source,
     'obsolete named relation indexes must not be managed by the import path')

# Extract only the explicit bulk-suspend list, not unrelated string occurrences.
m = re.search(r'BULK_IMPORT_SUSPEND_INDEXES\s*=\s*List\.of\((.*?)\);', source, re.S)
if not m:
    errors.append('BULK_IMPORT_SUSPEND_INDEXES list missing')
    configured: tuple[str, ...] = ()
else:
    configured = tuple(re.findall(r'"([^"]+)"', m.group(1)))
    need(configured == EXPECTED, f'bulk-suspend indexes changed unexpectedly: {configured!r}')

conn = sqlite3.connect(':memory:')
try:
    for migration in migration_files():
        conn.executescript(migration.read_text(encoding='utf-8'))
    conn.commit()

    definitions: dict[str, str] = {}
    for name in configured:
        row = conn.execute("SELECT sql FROM sqlite_master WHERE type='index' AND name=?", (name,)).fetchone()
        need(row is not None and row[0], f'configured index is absent from migrated V1-V41 schema: {name}')
        if row and row[0]:
            definitions[name] = row[0]

    # Relation PKs are auto-indexed; creating duplicate named book_id-leading indexes is unnecessary.
    ba = conn.execute("PRAGMA index_list('book_authors')").fetchall()
    bg = conn.execute("PRAGMA index_list('book_genres')").fetchall()
    need(any(str(row[1]).startswith('sqlite_autoindex_book_authors') for row in ba),
         'book_authors PK auto-index missing')
    need(any(str(row[1]).startswith('sqlite_autoindex_book_genres') for row in bg),
         'book_genres PK auto-index missing')

    # Prove that exact sqlite_master SQL can restore every suspended index.
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
print(' - live sqlite_master definitions are captured/restored exactly')
print(' - managed indexes exist in migrated V1-V41 schema')
print(' - redundant named relation indexes are not recreated')
