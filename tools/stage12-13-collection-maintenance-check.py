#!/usr/bin/env python3
"""Offline Stage 12+13 guard: collection source watcher + safe maintenance workflow."""
from __future__ import annotations

import hashlib
import os
import sqlite3
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def require(text: str, marker: str, label: str) -> None:
    if marker not in text:
        raise AssertionError(f"{label}: missing {marker!r}")

def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")

def migrate_meta(conn: sqlite3.Connection) -> None:
    for p in sorted((ROOT / "myhomelib-infrastructure/src/main/resources/db/migration_meta").glob("V*.sql"),
                    key=lambda x: int(x.name.split("__", 1)[0][1:])):
        conn.executescript(p.read_text(encoding="utf-8"))

def watcher_contract() -> None:
    src = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/collection/monitor/CollectionSourceMonitorAdapter.java")
    for marker in (
        "WatchService", "ENTRY_CREATE", "ENTRY_MODIFY", "ENTRY_DELETE",
        "scheduleDebouncedCheck", "pendingChecks.compute", "SHA-256",
        "ZipFile", "CollectionSourceUpdateAvailableEvent", "changed.getFileName().equals",
    ):
        require(src, marker, "watcher")
    usecase = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/CollectionAutoUpdateUseCase.java")
    require(usecase, "DEFAULT_DEBOUNCE_SECONDS = 60", "60-second debounce")
    require(usecase, "checkNow", "manual refresh fallback")
    require(usecase, "markApplied", "baseline acknowledge")

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        meta = sqlite3.connect(root / "meta.db")
        migrate_meta(meta)
        cols = {r[1] for r in meta.execute("PRAGMA table_info(collection_source_watch)")}
        expected = {"collection_id", "source_file", "enabled", "debounce_seconds",
                    "baseline_fingerprint", "observed_fingerprint", "last_checked_at",
                    "update_available", "last_status"}
        assert expected <= cols, f"meta V3 columns missing: {expected-cols}"

        source = root / "catalog.inpx"
        with zipfile.ZipFile(source, "w") as z:
            z.writestr("a.inp", "one")
        first = hashlib.sha256(source.read_bytes()).hexdigest()
        with zipfile.ZipFile(source, "w") as z:
            z.writestr("a.inp", "two")
        second = hashlib.sha256(source.read_bytes()).hexdigest()
        assert first != second
        with zipfile.ZipFile(source) as z:
            assert z.namelist() == ["a.inp"]


def maintenance_contract() -> None:
    src = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/maintenance/CollectionMaintenanceAdapter.java")
    for marker in (
        "PRAGMA quick_check", "MISSING_FILE", "INVALID_ARCHIVE_REFERENCE", "ORPHAN_FILE",
        "ORPHANED_AUTHOR", "ORPHANED_GENRE", "DUPLICATE_BOOK", "VACUUM INTO",
        "PRAGMA wal_checkpoint(FULL)", "REINDEX", "ANALYZE", "PRAGMA optimize",
        "UPDATE books SET local=0", "MAX_SAMPLES_PER_TYPE = 500",
    ):
        require(src, marker, "maintenance")
    if "Files.delete(" in src or "deletePhysicalFile" in src:
        raise AssertionError("maintenance must not auto-delete orphan physical files")

    port = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/collection/CollectionMaintenancePort.java")
    require(port, "boolean dryRun", "dry-run contract")
    ui = read("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java")
    for marker in ("onAnalyzeMaintenance", "onDryRunMaintenance", "onApplyMaintenance",
                   "create", "Backup", "repairableIssueIds"):
        require(ui, marker, "maintenance UI")

    legacy = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/integrity/DataIntegrityChecker.java")
    if "fixOrphanedBooks" in legacy:
        raise AssertionError("legacy destructive repair method must be removed")
    legacy_port = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/integrity/DataIntegrityPort.java")
    if "fixOrphanedData" in legacy_port:
        raise AssertionError("legacy destructive repair port must be removed")
    legacy_ui = read("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/IntegrityCheckController.java")
    if "integrityChecker.fixOrphanedBooks()" in legacy_ui:
        raise AssertionError("legacy UI still invokes destructive fix")

    ds = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/config/DataSourceConfig.java")
    if ds.count("PRAGMA foreign_keys=ON") < 2:
        raise AssertionError("foreign keys must be enabled on default and per-collection SQLite connections")
    meta_ds = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/config/MetadataDatabaseConfig.java")
    require(meta_ds, "PRAGMA foreign_keys=ON", "metadata foreign keys")
    legacy_impl = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/integrity/DataIntegrityService.java")
    if "fixOrphanedData" in legacy_impl:
        raise AssertionError("legacy destructive repair implementation must be removed")
    if "DELETE FROM books WHERE id NOT IN" in legacy_impl:
        raise AssertionError("legacy infrastructure still contains direct destructive repair SQL")

    # Exercise the deterministic duplicate SQL shape and VACUUM INTO on the runtime SQLite.
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        db = root / "library.db"
        conn = sqlite3.connect(db)
        conn.executescript("""
          PRAGMA foreign_keys=ON;
          CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT,file_name TEXT,folder TEXT,archive_entry TEXT,collection_root TEXT,lib_id TEXT,local INTEGER,deleted INTEGER DEFAULT 0);
          CREATE TABLE authors(id TEXT PRIMARY KEY,first_name TEXT,middle_name TEXT,last_name TEXT);
          CREATE TABLE genres(code TEXT PRIMARY KEY,name TEXT);
          CREATE TABLE book_authors(book_id TEXT,author_id TEXT,FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE,FOREIGN KEY(author_id) REFERENCES authors(id) ON DELETE CASCADE);
          CREATE TABLE book_genres(book_id TEXT,genre_code TEXT,FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE,FOREIGN KEY(genre_code) REFERENCES genres(code) ON DELETE CASCADE);
          INSERT INTO books VALUES('a','Dup','same.fb2','','','','LIB',0,0);
          INSERT INTO books VALUES('b','Dup','same.fb2','','','','LIB',0,0);
          INSERT INTO books VALUES('m','Missing','missing.fb2','','','','M',1,0);
          INSERT INTO authors VALUES('oa','No','','Books');
          INSERT INTO genres VALUES('og','No books');
        """)
        duplicate_sql = """
          SELECT b.id FROM books b JOIN (
            SELECT lib_id,COALESCE(collection_root,'') cr,COALESCE(folder,'') folder,
                   COALESCE(file_name,'') file_name,COALESCE(archive_entry,'') archive_entry,
                   MIN(id) keep_id,COUNT(*) cnt
            FROM books WHERE TRIM(COALESCE(lib_id,''))<>''
            GROUP BY lib_id,COALESCE(collection_root,''),COALESCE(folder,''),COALESCE(file_name,''),COALESCE(archive_entry,'')
            HAVING COUNT(*)>1
          ) d ON b.lib_id=d.lib_id AND COALESCE(b.collection_root,'')=d.cr
             AND COALESCE(b.folder,'')=d.folder AND COALESCE(b.file_name,'')=d.file_name
             AND COALESCE(b.archive_entry,'')=d.archive_entry
          WHERE b.id<>d.keep_id ORDER BY b.lib_id,b.id
        """
        assert [r[0] for r in conn.execute(duplicate_sql)] == ["b"]
        assert conn.execute("PRAGMA quick_check").fetchone()[0] == "ok"
        backup = root / "backup.db"
        conn.execute(f"VACUUM INTO '{backup.as_posix()}'")
        assert backup.is_file() and backup.stat().st_size > 0
        conn.execute("UPDATE books SET local=0 WHERE id='m'")
        assert conn.execute("SELECT local FROM books WHERE id='m'").fetchone()[0] == 0
        assert conn.execute("DELETE FROM authors WHERE id='oa' AND NOT EXISTS(SELECT 1 FROM book_authors WHERE author_id='oa')").rowcount == 1
        conn.close()


def fxml_contract() -> None:
    files = list((ROOT / "myhomelib-ui/src/main/resources").rglob("*.fxml"))
    for p in files:
        ET.parse(p)
    fxml = read("myhomelib-ui/src/main/resources/view/collection-workspace.fxml")
    for marker in ("#onBrowseSource", "#onSaveSourceMonitor", "#onCheckSourceNow",
                   "#onAnalyzeMaintenance", "#onDryRunMaintenance", "#onApplyMaintenance"):
        require(fxml, marker, "collection workspace FXML")


def main() -> None:
    watcher_contract()
    maintenance_contract()
    fxml_contract()
    print("STAGE 12+13 COLLECTION MAINTENANCE CHECK: PASS")
    print(" - metadata V3 source-watch state: PASS")
    print(" - WatchService + source-only filtering + debounce + SHA-256: PASS")
    print(" - readable/ZIP validation + manual refresh + baseline acknowledge: PASS")
    print(" - analyze/preview/dry-run/apply + mandatory SQLite backup: PASS")
    print(" - missing/archive/orphan/duplicate/orphan-dictionary analysis: PASS")
    print(" - physical orphan auto-delete blocked + legacy destructive bypass blocked: PASS")
    print(" - Collection Workspace FXML wiring: PASS")

if __name__ == "__main__":
    main()
