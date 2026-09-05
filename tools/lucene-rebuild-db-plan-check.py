#!/usr/bin/env python3
from pathlib import Path
import sqlite3
import sys

ROOT = Path(__file__).resolve().parents[1]
repo = (ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteBookQueryRepository.java").read_text(encoding="utf-8")
migration = (ROOT / "myhomelib-infrastructure/src/main/resources/db/migration/V48__active_books_count_index.sql").read_text(encoding="utf-8")

checks = []
checks.append(("Lucene base projection filters tombstones in SQL", "WHERE deleted = 0 AND id > ?" in repo))
checks.append(("Lucene stream page size is 5000", "SEARCH_STREAM_PAGE_SIZE = 5_000" in repo))
checks.append(("first Lucene page is lazy/telemetry-visible", "private SearchSnapshotIterator(int pageSize) {\n            this.pageSize" in repo))
checks.append(("active partial index exists", "idx_books_active_id" in migration and "WHERE deleted = 0" in migration))
checks.append(("author range order follows relation PK", "ORDER BY ba.book_id, ba.author_id" in repo))
checks.append(("genre range order follows relation PK", "ORDER BY bg.book_id, bg.genre_code" in repo))

con = sqlite3.connect(":memory:")
con.executescript("""
CREATE TABLE books(id TEXT PRIMARY KEY, title TEXT, deleted INTEGER NOT NULL DEFAULT 0);
CREATE TABLE authors(id TEXT PRIMARY KEY, first_name TEXT, middle_name TEXT, last_name TEXT);
CREATE TABLE genres(code TEXT PRIMARY KEY, name TEXT);
CREATE TABLE book_authors(book_id TEXT NOT NULL, author_id TEXT NOT NULL, PRIMARY KEY(book_id, author_id));
CREATE TABLE book_genres(book_id TEXT NOT NULL, genre_code TEXT NOT NULL, PRIMARY KEY(book_id, genre_code));
CREATE INDEX idx_books_active_id ON books(id) WHERE deleted = 0;
""")

def plan(sql, args):
    return " ".join(str(row) for row in con.execute("EXPLAIN QUERY PLAN " + sql, args))

base = plan("SELECT id,title FROM books WHERE deleted=0 AND id>? ORDER BY id LIMIT ?", ("", 5000))
authors = plan("""SELECT ba.book_id,a.id,a.first_name,a.middle_name,a.last_name
FROM book_authors ba JOIN authors a ON a.id=ba.author_id
WHERE ba.book_id>=? AND ba.book_id<=?
ORDER BY ba.book_id,ba.author_id""", ("a", "z"))
genres = plan("""SELECT bg.book_id,g.code,g.name
FROM book_genres bg JOIN genres g ON g.code=bg.genre_code
WHERE bg.book_id>=? AND bg.book_id<=?
ORDER BY bg.book_id,bg.genre_code""", ("a", "z"))
checks.append(("base plan uses active partial index", "idx_books_active_id" in base))
checks.append(("author relation plan avoids temporary sort", "TEMP B-TREE" not in authors))
checks.append(("genre relation plan avoids temporary sort", "TEMP B-TREE" not in genres))

print("LUCENE REBUILD DB PLAN CHECK")
failed = []
for name, ok in checks:
    print(f" - {name}: {'PASS' if ok else 'FAIL'}")
    if not ok: failed.append(name)
if failed:
    sys.exit(1)
print("LUCENE REBUILD DB PLAN CHECK: PASS")
