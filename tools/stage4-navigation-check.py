#!/usr/bin/env python3
"""Offline Stage 4 navigation semantics check using stdlib SQLite."""
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


def seed(connection: sqlite3.Connection) -> tuple[int, int]:
    rows = [
        ("b1", "Alpha", "a.fb2", "space, future; AI", 5, "Excellent", 0),
        ("b2", "Beta", "b.fb2", "Space|history, AI", 3, "", 0),
        ("b3", "Gamma", "c.fb2", "history", 0, "Thoughtful", 0),
        ("b4", "Delta", "d.fb2", "spacecraft", 0, "", 0),
        ("b5", "Deleted", "x.fb2", "space, AI", 5, "Hidden", 1),
    ]
    connection.executemany(
        """
        INSERT INTO books(id,title,file_name,keywords,rate,review,deleted)
        VALUES(?,?,?,?,?,?,?)
        """,
        rows,
    )
    connection.execute("INSERT INTO groups(name, allow_delete) VALUES('Sci-Fi shelf', 1)")
    sci_group = connection.execute("SELECT id FROM groups WHERE name='Sci-Fi shelf'").fetchone()[0]
    favorites = connection.execute("SELECT id FROM groups WHERE name='Favorites'").fetchone()[0]
    connection.executemany(
        "INSERT INTO book_groups(book_id, group_id) VALUES(?,?)",
        [("b1", favorites), ("b2", favorites), ("b1", sci_group), ("b5", sci_group)],
    )
    connection.commit()
    return favorites, sci_group


KEYWORD_FACETS_SQL = """
WITH RECURSIVE keyword_source(book_id, rest) AS (
    SELECT id, REPLACE(REPLACE(COALESCE(keywords, ''), ';', ','), '|', ',') || ','
    FROM books
    WHERE deleted = 0 AND keywords IS NOT NULL AND TRIM(keywords) <> ''
), split(book_id, token, rest) AS (
    SELECT book_id, '', rest FROM keyword_source
    UNION ALL
    SELECT book_id,
           TRIM(SUBSTR(rest, 1, INSTR(rest, ',') - 1)),
           SUBSTR(rest, INSTR(rest, ',') + 1)
    FROM split WHERE rest <> ''
)
SELECT LOWER(token), MIN(token), COUNT(DISTINCT book_id)
FROM split
WHERE token <> ''
GROUP BY LOWER(token)
ORDER BY LOWER(token)
"""

KEYWORD_FILTER_SQL = """
SELECT COUNT(*) FROM books b
WHERE b.deleted = 0
  AND EXISTS (
      WITH RECURSIVE split(rest, token) AS (
          VALUES(REPLACE(REPLACE(COALESCE(b.keywords, ''), ';', ','), '|', ',') || ',', '')
          UNION ALL
          SELECT SUBSTR(rest, INSTR(rest, ',') + 1),
                 TRIM(SUBSTR(rest, 1, INSTR(rest, ',') - 1))
          FROM split WHERE rest <> ''
      )
      SELECT 1 FROM split WHERE LOWER(token) = LOWER(?) LIMIT 1
  )
"""


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="myhomelib-stage4-") as td:
        db = sqlite3.connect(Path(td) / "library.sqlite")
        try:
            apply_migrations(db)
            favorites, sci_group = seed(db)

            keywords = db.execute(KEYWORD_FACETS_SQL).fetchall()
            assert keywords == [
                ("ai", "AI", 2),
                ("future", "future", 1),
                ("history", "history", 2),
                ("space", "Space", 2),
                ("spacecraft", "spacecraft", 1),
            ], keywords

            assert db.execute(KEYWORD_FILTER_SQL, ("space",)).fetchone()[0] == 2
            assert db.execute(KEYWORD_FILTER_SQL, ("SPACE",)).fetchone()[0] == 2
            assert db.execute(KEYWORD_FILTER_SQL, ("spacecraft",)).fetchone()[0] == 1

            groups = db.execute(
                """
                SELECT g.id, g.name, COUNT(b.id)
                FROM groups g
                LEFT JOIN book_groups bg ON bg.group_id = g.id
                LEFT JOIN books b ON b.id = bg.book_id AND b.deleted = 0
                GROUP BY g.id, g.name ORDER BY LOWER(g.name), g.id
                """
            ).fetchall()
            group_counts = {row[0]: row[2] for row in groups}
            assert group_counts[favorites] == 2, groups
            assert group_counts[sci_group] == 1, groups
            assert any(name == "To Read" and count == 0 for _, name, count in groups), groups

            rated = db.execute("SELECT COUNT(*) FROM books WHERE deleted=0 AND COALESCE(rate,0)>0").fetchone()[0]
            reviewed = db.execute(
                "SELECT COUNT(*) FROM books WHERE deleted=0 AND review IS NOT NULL AND TRIM(review)<>''"
            ).fetchone()[0]
            both = db.execute(
                """SELECT COUNT(*) FROM books WHERE deleted=0 AND COALESCE(rate,0)>0
                   AND review IS NOT NULL AND TRIM(review)<>''"""
            ).fetchone()[0]
            assert (rated, reviewed, both) == (2, 2, 1), (rated, reviewed, both)
        finally:
            db.close()

    print("STAGE 4 NAVIGATION CHECK: PASS")
    print(" - keyword facets and exact token filtering: PASS")
    print(" - group/favorites active-book counts: PASS")
    print(" - rated/reviewed subset counts: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
