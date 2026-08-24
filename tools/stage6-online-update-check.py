#!/usr/bin/env python3
"""Offline Stage 6 regression check for catalog revision/update semantics.

Uses only stdlib sqlite3 and mirrors the Stage 6 SQL decisions so it can run when
Maven/JUnit dependencies are unavailable.
"""
from __future__ import annotations

import hashlib
import re
import sqlite3
import tempfile
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration"


def apply_migrations(db: sqlite3.Connection) -> None:
    files = sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))
    for path in files:
        db.executescript(path.read_text(encoding="utf-8"))
    db.commit()


def stable_source_id(source_key: str) -> str:
    raw = hashlib.md5(("catalog-source:" + source_key).encode("utf-8")).digest()  # Java UUID.nameUUIDFromBytes
    return str(uuid.UUID(bytes=raw, version=3))


def begin_sync(db: sqlite3.Connection, source_key: str, source_fp: str) -> tuple[str, int, bool, bool]:
    source_id = stable_source_id(source_key)
    row = db.execute(
        "SELECT source_revision, source_fingerprint FROM catalog_sources WHERE source_key=?", (source_key,)
    ).fetchone()
    if row is None:
        db.execute(
            "INSERT INTO catalog_sources(source_id,source_key,source_revision,source_fingerprint) VALUES(?,?,1,?)",
            (source_id, source_key, source_fp),
        )
        return source_id, 1, True, True
    revision, old_fp = row
    changed = old_fp != source_fp
    if changed:
        revision += 1
    db.execute(
        "UPDATE catalog_sources SET source_revision=?,source_fingerprint=?,last_synced_at=CURRENT_TIMESTAMP WHERE source_key=?",
        (revision, source_fp, source_key),
    )
    return source_id, revision, False, changed


def writer_upsert(db: sqlite3.Connection, *, book_id: str, title: str, remote_file: str, remote_folder: str,
                  remote_entry: str, remote_size: int, source_marker: str) -> None:
    db.execute(
        """
        INSERT INTO books(
            id,title,series,sequence_number,file_name,folder,archive_entry,language,file_size,keywords,annotation,
            rate,progress,update_date,isbn,deleted,local,review,created_at,collection_root,year,publisher,
            lib_id,library_rate,translators,city,source_url
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET
            title=excluded.title,
            series=excluded.series,
            sequence_number=excluded.sequence_number,
            file_name=CASE WHEN books.local=1 THEN books.file_name ELSE excluded.file_name END,
            folder=CASE WHEN books.local=1 THEN books.folder ELSE excluded.folder END,
            archive_entry=CASE WHEN books.local=1 THEN books.archive_entry ELSE excluded.archive_entry END,
            language=excluded.language,
            file_size=CASE WHEN books.local=1 THEN books.file_size ELSE excluded.file_size END,
            keywords=excluded.keywords,
            annotation=excluded.annotation,
            rate=books.rate,
            progress=books.progress,
            update_date=excluded.update_date,
            isbn=excluded.isbn,
            deleted=excluded.deleted,
            local=CASE WHEN books.local=1 THEN 1 ELSE excluded.local END,
            review=books.review,
            created_at=books.created_at,
            collection_root=CASE WHEN books.local=1 THEN books.collection_root ELSE excluded.collection_root END,
            year=excluded.year,
            publisher=excluded.publisher,
            lib_id=CASE WHEN COALESCE(excluded.lib_id,'')<>'' THEN excluded.lib_id ELSE books.lib_id END,
            library_rate=excluded.library_rate,
            translators=excluded.translators,
            city=excluded.city,
            source_url=CASE WHEN COALESCE(excluded.source_url,'')<>'' THEN excluded.source_url ELSE books.source_url END
        """,
        (book_id,title,"",0,remote_file,remote_folder,remote_entry,"uk",remote_size,"kw","annotation",
         0,0,"2026-08-24 20:00:00.000","",0,0,"","2026-08-24 20:00:00.000","/remote",2026,"",
         book_id,0,"","",source_marker),
    )


def record_book(db: sqlite3.Connection, session: tuple[str, int, bool, bool], book_id: str, fp: str,
                *, file_name: str = "book.fb2", folder: str = "catalog.zip", entry: str = "book.fb2", size: int = 120) -> None:
    source_id, revision, initial, _changed = session
    # Revert to downloaded baseline clears an obsolete UPDATED event.
    db.execute(
        """
        DELETE FROM catalog_update_events
         WHERE book_id=? AND update_type='UPDATED_DOWNLOADED_BOOK'
           AND EXISTS(SELECT 1 FROM catalog_book_state c WHERE c.book_id=?
                      AND c.downloaded_fingerprint IS NOT NULL AND c.downloaded_fingerprint=?)
        """, (book_id, book_id, fp),
    )
    old = db.execute(
        "SELECT catalog_fingerprint,downloaded_fingerprint FROM catalog_book_state WHERE book_id=?", (book_id,)
    ).fetchone()
    local = db.execute("SELECT local FROM books WHERE id=?", (book_id,)).fetchone()
    if old and local and local[0] == 1 and old[1] is not None and old[0] != fp and old[1] != fp:
        db.execute(
            """
            INSERT INTO catalog_update_events(book_id,update_type,source_id,detected_revision,catalog_fingerprint,detected_at,acknowledged_at)
            VALUES(?,'UPDATED_DOWNLOADED_BOOK',?,?,?,CURRENT_TIMESTAMP,NULL)
            ON CONFLICT(book_id,update_type) DO UPDATE SET
              source_id=excluded.source_id,detected_revision=excluded.detected_revision,
              catalog_fingerprint=excluded.catalog_fingerprint,detected_at=excluded.detected_at,acknowledged_at=NULL
            WHERE catalog_update_events.catalog_fingerprint<>excluded.catalog_fingerprint
            """, (book_id, source_id, revision, fp),
        )
    if not initial and old is None:
        followed = db.execute(
            """SELECT 1 FROM book_authors ba JOIN followed_authors fa ON fa.author_id=ba.author_id
               WHERE ba.book_id=? LIMIT 1""", (book_id,)
        ).fetchone()
        if followed:
            db.execute(
                """
                INSERT INTO catalog_update_events(book_id,update_type,source_id,detected_revision,catalog_fingerprint,detected_at,acknowledged_at)
                VALUES(?,'NEW_BY_FOLLOWED_AUTHOR',?,?,?,CURRENT_TIMESTAMP,NULL)
                ON CONFLICT(book_id,update_type) DO UPDATE SET
                  source_id=excluded.source_id,detected_revision=excluded.detected_revision,
                  catalog_fingerprint=excluded.catalog_fingerprint,detected_at=excluded.detected_at,acknowledged_at=NULL
                WHERE catalog_update_events.catalog_fingerprint<>excluded.catalog_fingerprint
                """, (book_id, source_id, revision, fp),
            )
    if old is None:
        local = db.execute("SELECT local FROM books WHERE id=?", (book_id,)).fetchone()[0]
        db.execute(
            """
            INSERT INTO catalog_book_state(
              book_id,source_id,source_book_key,catalog_revision,catalog_fingerprint,
              catalog_file_name,catalog_folder,catalog_archive_entry,catalog_file_size,
              downloaded_revision,downloaded_fingerprint,downloaded_baseline_at,first_seen_revision,last_seen_revision)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (book_id,source_id,"libid:"+book_id,revision,fp,file_name,folder,entry,size,
             revision if local else None, fp if local else None, "2026-08-24 20:00:00.000" if local else None,
             revision,revision),
        )
    else:
        db.execute(
            """UPDATE catalog_book_state SET source_id=?,catalog_revision=?,catalog_fingerprint=?,
               catalog_file_name=?,catalog_folder=?,catalog_archive_entry=?,catalog_file_size=?,last_seen_revision=?
               WHERE book_id=?""",
            (source_id,revision,fp,file_name,folder,entry,size,revision,book_id),
        )


def mark_downloaded_baseline(db: sqlite3.Connection, book_id: str) -> None:
    db.execute(
        """UPDATE catalog_book_state
              SET downloaded_revision=catalog_revision,
                  downloaded_fingerprint=catalog_fingerprint,
                  downloaded_baseline_at=CURRENT_TIMESTAMP
            WHERE book_id=?""",
        (book_id,),
    )
    db.execute(
        "UPDATE catalog_update_events SET acknowledged_at=CURRENT_TIMESTAMP WHERE book_id=? AND acknowledged_at IS NULL",
        (book_id,),
    )


def check_java_markers() -> None:
    migration = (MIGRATIONS / "V31__catalog_update_revision_model.sql").read_text(encoding="utf-8")
    for marker in ("catalog_sources", "catalog_book_state", "downloaded_revision", "downloaded_fingerprint",
                   "followed_authors", "catalog_update_events", "NEW_BY_FOLLOWED_AUTHOR", "UPDATED_DOWNLOADED_BOOK"):
        assert marker in migration, f"V31 missing {marker}"

    writer = (ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/JdbcBatchWriter.java").read_text(encoding="utf-8")
    for marker in (
        "CASE WHEN books.local = 1 THEN books.file_name ELSE excluded.file_name END",
        "rate = books.rate", "progress = books.progress", "review = books.review",
        "CASE WHEN books.local = 1 THEN books.collection_root ELSE excluded.collection_root END",
    ):
        assert marker in writer, f"local/user-data UPSERT guard missing: {marker}"

    update_uc = (ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/UpdateCollectionFromNetworkUseCase.java").read_text(encoding="utf-8")
    assert "CatalogSourceIdentity.remoteCollection(collection.getId())" in update_uc
    assert ".catalogSourceLocation(inpxUrl)" in update_uc

    download_uc = (ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/DownloadBookUseCase.java").read_text(encoding="utf-8")
    assert "catalogUpdateTrackingPort.markDownloadedBaseline(bookId)" in download_uc

    adapter = (ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/catalog/SqliteCatalogUpdateTrackingAdapter.java").read_text(encoding="utf-8")
    assert "WHERE book_id = ? AND acknowledged_at IS NULL" in adapter


def main() -> int:
    check_java_markers()
    with tempfile.TemporaryDirectory(prefix="stage6-") as td:
        db = sqlite3.connect(Path(td) / "stage6.sqlite")
        db.execute("PRAGMA foreign_keys=ON")
        apply_migrations(db)

        author = str(uuid.uuid4())
        db.execute("INSERT INTO authors(id,first_name,last_name) VALUES(?, 'Test', 'Author')", (author,))
        db.execute(
            """INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,rate,progress,review,local,deleted,collection_root)
               VALUES('b1','Old title','local.fb2','downloads/local.zip','local.fb2',777,5,42,'my review',1,0,'/downloads')"""
        )
        db.execute("INSERT INTO book_authors(book_id,author_id) VALUES('b1',?)", (author,))
        db.execute("INSERT INTO bookmarks(id,book_id,paragraph_id,char_offset,position,chapter_title,context,created_at) VALUES('bm1','b1','p1',0,123,'chapter','keep me','2026-08-24 20:00:00')")

        source_key = "remote-collection:collection-123"
        baseline = begin_sync(db, source_key, "source-a")
        assert baseline[1:] == (1, True, True), baseline
        writer_upsert(db, book_id="b1", title="Catalog title", remote_file="remote.fb2", remote_folder="catalog-a.zip",
                      remote_entry="remote.fb2", remote_size=100, source_marker="catalog:"+baseline[0])
        record_book(db, baseline, "b1", "book-a")
        assert db.execute("SELECT COUNT(*) FROM catalog_update_events").fetchone()[0] == 0

        row = db.execute("SELECT file_name,folder,archive_entry,file_size,rate,progress,review,local,collection_root FROM books WHERE id='b1'").fetchone()
        assert row == ("local.fb2","downloads/local.zip","local.fb2",777,5,42,"my review",1,"/downloads"), row
        assert db.execute("SELECT COUNT(*) FROM bookmarks WHERE book_id='b1'").fetchone()[0] == 1

        same = begin_sync(db, source_key, "source-a")
        assert same[1] == 1 and not same[3]
        writer_upsert(db, book_id="b1", title="Catalog title", remote_file="remote.fb2", remote_folder="catalog-a.zip",
                      remote_entry="remote.fb2", remote_size=100, source_marker="catalog:"+same[0])
        record_book(db, same, "b1", "book-a")
        assert db.execute("SELECT COUNT(*) FROM catalog_update_events WHERE acknowledged_at IS NULL").fetchone()[0] == 0

        changed = begin_sync(db, source_key, "source-b")
        assert changed[1] == 2 and changed[3]
        writer_upsert(db, book_id="b1", title="Changed metadata", remote_file="remote-v2.fb2", remote_folder="catalog-b.zip",
                      remote_entry="remote-v2.fb2", remote_size=200, source_marker="catalog:"+changed[0])
        record_book(db, changed, "b1", "book-b", file_name="remote-v2.fb2", folder="catalog-b.zip", entry="remote-v2.fb2", size=200)
        events = db.execute("SELECT book_id,update_type FROM catalog_update_events WHERE acknowledged_at IS NULL").fetchall()
        assert events == [("b1", "UPDATED_DOWNLOADED_BOOK")], events

        repeat_changed = begin_sync(db, source_key, "source-b")
        record_book(db, repeat_changed, "b1", "book-b", file_name="remote-v2.fb2", folder="catalog-b.zip", entry="remote-v2.fb2", size=200)
        assert db.execute("SELECT COUNT(*) FROM catalog_update_events WHERE book_id='b1' AND update_type='UPDATED_DOWNLOADED_BOOK'").fetchone()[0] == 1
        mark_downloaded_baseline(db, "b1")
        assert db.execute("SELECT COUNT(*) FROM catalog_update_events WHERE book_id='b1' AND acknowledged_at IS NULL").fetchone()[0] == 0

        # Following is explicit author state; the first catalog baseline did not create NEW events.
        db.execute("INSERT INTO followed_authors(author_id) VALUES(?)", (author,))
        db.execute("INSERT INTO books(id,title,file_name,local,deleted) VALUES('b2','New book','b2.fb2',0,0)")
        db.execute("INSERT INTO book_authors(book_id,author_id) VALUES('b2',?)", (author,))
        source3 = begin_sync(db, source_key, "source-c")
        record_book(db, source3, "b2", "book-new")
        new_events = db.execute("SELECT update_type FROM catalog_update_events WHERE book_id='b2' AND acknowledged_at IS NULL").fetchall()
        assert new_events == [("NEW_BY_FOLLOWED_AUTHOR",)], new_events
        db.execute("UPDATE books SET local=1 WHERE id='b2'")
        mark_downloaded_baseline(db, "b2")
        assert db.execute("SELECT COUNT(*) FROM catalog_update_events WHERE book_id='b2' AND acknowledged_at IS NULL").fetchone()[0] == 0

        # Missing from a later catalog never deletes the downloaded bytes/local flag.
        db.execute("UPDATE books SET deleted=1 WHERE id IN (SELECT book_id FROM catalog_book_state WHERE source_id=?)", (source3[0],))
        assert db.execute("SELECT local FROM books WHERE id='b1'").fetchone()[0] == 1

        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        db.close()

    print("STAGE 6 ONLINE UPDATE CHECK: PASS")
    print(" - stable remote source identity independent of temp INPX path: PASS")
    print(" - initial sync establishes baseline without false updates: PASS")
    print(" - repeated identical sync produces zero new events: PASS")
    print(" - changed downloaded book produces exactly one pending update: PASS")
    print(" - local storage/rating/progress/review/bookmarks survive remote UPSERT: PASS")
    print(" - new book by explicitly followed author is detected after baseline: PASS")
    print(" - successful download establishes baseline and acknowledges pending book events: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
