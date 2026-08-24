#!/usr/bin/env python3
"""Offline Stage 5 Recent / AlreadyRead / History semantics check."""
from __future__ import annotations

import re
import sqlite3
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration"


def migrations() -> list[Path]:
    return sorted(
        MIGRATIONS.glob("V*__*.sql"),
        key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)),
    )


def apply(connection: sqlite3.Connection, *, through: int | None = None, from_version: int = 1) -> None:
    for migration in migrations():
        version = int(re.match(r"V(\d+)__", migration.name).group(1))
        if version < from_version:
            continue
        if through is not None and version > through:
            continue
        connection.executescript(migration.read_text(encoding="utf-8"))
    connection.commit()


def seed_pre_stage5(connection: sqlite3.Connection) -> None:
    connection.executemany(
        """
        INSERT INTO books(id,title,file_name,progress,deleted)
        VALUES(?,?,?,?,?)
        """,
        [
            ("b1", "Finished old", "b1.fb2", 100, 0),
            ("b2", "In progress", "b2.fb2", 55, 0),
            ("b3", "Finished newest", "b3.fb2", 100, 0),
            ("b4", "Deleted finished", "b4.fb2", 100, 1),
        ],
    )
    connection.executemany(
        """
        INSERT INTO reading_progress(book_id,paragraph_id,char_offset,percent,updated_at,anchor_id,paragraph_index)
        VALUES(?,?,?,?,?,?,?)
        """,
        [
            ("b1", "p1", 10, 100.0, "2026-08-20 10:00:00.123", "p1", 1),
            ("b2", "p2", 20, 55.0, "2026-08-23T11:00:00.456", "p2", 2),
            ("b4", "p4", 40, 100.0, "2026-08-24 12:00:00", "p4", 4),
        ],
    )
    connection.execute(
        """
        INSERT INTO reading_stats(
            book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,
            start_percent,end_percent,current_percent,completed_at
        ) VALUES(?,?,?,?,?,?,?,?,?)
        """,
        ("b3", "2026-08-21 09:00:00", "2026-08-24 09:30:00.789", 600, 2, 0, 100, 100, "2026-08-24 09:30:00.789"),
    )
    connection.commit()


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="myhomelib-stage5-") as td:
        db = sqlite3.connect(Path(td) / "library.sqlite")
        try:
            apply(db, through=29)
            seed_pre_stage5(db)
            apply(db, from_version=30, through=30)

            already_read = db.execute(
                "SELECT id FROM books WHERE deleted=0 AND progress=100 ORDER BY id"
            ).fetchall()
            assert already_read == [("b1",), ("b3",)], already_read

            # V30 must preserve pre-existing reading signal, including deleted rows for FK integrity.
            migrated = db.execute(
                "SELECT book_id,last_opened_at,open_count FROM reading_history ORDER BY book_id"
            ).fetchall()
            assert migrated == [
                ("b1", "2026-08-20 10:00:00", 1),
                ("b2", "2026-08-23 11:00:00", 1),
                ("b3", "2026-08-24 09:30:00", 1),
                ("b4", "2026-08-24 12:00:00", 1),
            ], migrated

            visible_history = db.execute(
                """
                SELECT b.id,rh.last_opened_at
                FROM reading_history rh JOIN books b ON b.id=rh.book_id
                WHERE b.deleted=0
                ORDER BY rh.last_opened_at DESC, rh.book_id ASC
                """
            ).fetchall()
            assert visible_history == [
                ("b3", "2026-08-24 09:30:00"),
                ("b2", "2026-08-23 11:00:00"),
                ("b1", "2026-08-20 10:00:00"),
            ], visible_history

            # Same UPSERT contract as SqliteReadingHistoryAdapter, with a deterministic timestamp.
            db.execute(
                """
                INSERT INTO reading_history(book_id,last_opened_at,open_count)
                VALUES (?, ?, 1)
                ON CONFLICT(book_id) DO UPDATE SET
                    last_opened_at=excluded.last_opened_at,
                    open_count=reading_history.open_count+1
                """,
                ("b1", "2026-08-24 22:00:00"),
            )
            assert db.execute(
                "SELECT last_opened_at,open_count FROM reading_history WHERE book_id='b1'"
            ).fetchone() == ("2026-08-24 22:00:00", 2)

            progress_before = db.execute("SELECT COUNT(*) FROM reading_progress").fetchone()[0]
            read_before = db.execute("SELECT COUNT(*) FROM books WHERE deleted=0 AND progress=100").fetchone()[0]
            db.execute("DELETE FROM reading_history")
            db.commit()
            assert db.execute("SELECT COUNT(*) FROM reading_history").fetchone()[0] == 0
            assert db.execute("SELECT COUNT(*) FROM reading_progress").fetchone()[0] == progress_before
            assert db.execute("SELECT COUNT(*) FROM books WHERE deleted=0 AND progress=100").fetchone()[0] == read_before
        finally:
            db.close()

    print("STAGE 5 HISTORY CHECK: PASS")
    print(" - V30 backfill from legacy reading state: PASS")
    print(" - AlreadyRead uses progress=100 and excludes deleted books: PASS")
    print(" - History ordering / deleted filtering / repeat-open UPSERT: PASS")
    print(" - clear history preserves reading progress and AlreadyRead state: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
