#!/usr/bin/env python3
"""Offline Stage 3 navigation semantics check using the stdlib SQLite driver."""
from __future__ import annotations

import re
import sqlite3
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration"


def apply_migrations(connection: sqlite3.Connection) -> None:
    migrations = sorted(
        MIGRATIONS.glob("V*__*.sql"),
        key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)),
    )
    for migration in migrations:
        connection.executescript(migration.read_text(encoding="utf-8"))
    connection.commit()


def seed(connection: sqlite3.Connection) -> None:
    rows = [
        ("b1", "A", "a.fb2", "/lib/a/books.zip", "one.fb2", "uk", "/lib", 2024, 0),
        ("b2", "B", "b.fb2", "/lib/a/books.zip", "two.fb2", "uk", "/lib", 2024, 0),
        ("b3", "C", "c.fb2", "", "", "en", "/lib", 1999, 0),
        ("b4", "D", "d.fb2", "/other/books.zip", "four.fb2", "EN-us", "/other", 2020, 0),
        ("b5", "Deleted", "x.fb2", "/lib/a/books.zip", "gone.fb2", "uk", "/lib", 2025, 1),
    ]
    connection.executemany(
        """
        INSERT INTO books(id,title,file_name,folder,archive_entry,language,collection_root,year,deleted)
        VALUES(?,?,?,?,?,?,?,?,?)
        """,
        rows,
    )
    connection.commit()


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="myhomelib-stage3-") as td:
        db = sqlite3.connect(Path(td) / "library.sqlite")
        try:
            apply_migrations(db)
            seed(db)

            years = db.execute(
                """
                SELECT year, COUNT(*) FROM books
                WHERE deleted=0 AND year IS NOT NULL AND year>0
                GROUP BY year ORDER BY year DESC
                """
            ).fetchall()
            assert years == [(2024, 2), (2020, 1), (1999, 1)], years

            languages = db.execute(
                """
                SELECT LOWER(TRIM(language)), COUNT(*) FROM books
                WHERE deleted=0 AND language IS NOT NULL AND TRIM(language)<>''
                GROUP BY LOWER(TRIM(language)) ORDER BY LOWER(TRIM(language))
                """
            ).fetchall()
            assert languages == [("en", 1), ("en-us", 1), ("uk", 2)], languages

            archives = db.execute(
                """
                SELECT MIN(COALESCE(collection_root,'')), MIN(folder), COUNT(*)
                FROM books
                WHERE deleted=0 AND COALESCE(archive_entry,'')<>'' AND folder IS NOT NULL AND TRIM(folder)<>''
                GROUP BY LOWER(REPLACE(COALESCE(collection_root,''),'\\','/')),
                         LOWER(REPLACE(folder,'\\','/'))
                ORDER BY LOWER(REPLACE(folder,'\\','/'))
                """
            ).fetchall()
            assert archives == [
                ("/lib", "/lib/a/books.zip", 2),
                ("/other", "/other/books.zip", 1),
            ], archives

            assert db.execute("SELECT COUNT(*) FROM books WHERE deleted=0 AND year=?", (2024,)).fetchone()[0] == 2
            assert db.execute(
                "SELECT COUNT(*) FROM books WHERE deleted=0 AND LOWER(TRIM(language))=LOWER(?)", ("uk",)
            ).fetchone()[0] == 2
            assert db.execute(
                """
                SELECT COUNT(*) FROM books
                WHERE deleted=0 AND COALESCE(archive_entry,'')<>''
                  AND LOWER(REPLACE(COALESCE(folder,''),'\\','/'))=LOWER(?)
                  AND LOWER(REPLACE(COALESCE(collection_root,''),'\\','/'))=LOWER(?)
                """,
                ("/lib/a/books.zip", "/lib"),
            ).fetchone()[0] == 2
        finally:
            db.close()

    print("STAGE 3 NAVIGATION CHECK: PASS")
    print(" - year facets/counts: PASS")
    print(" - language normalization/counts: PASS")
    print(" - archive container grouping/counts: PASS")
    print(" - year/language/archive filters: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
