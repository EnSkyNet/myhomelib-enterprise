#!/usr/bin/env python3
"""Offline v7.1 release gate.

This intentionally uses only the Python standard library so it can run before Maven.
It validates migration safety, index plans, XML/FXML syntax and critical source invariants.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration"
META_MIGRATIONS = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration_meta"


def version(path: Path) -> int:
    m = re.match(r"V(\d+)__", path.name)
    if not m:
        raise ValueError(f"Not a Flyway migration: {path}")
    return int(m.group(1))


def migration_files(folder: Path) -> list[Path]:
    return sorted(folder.glob("V*__*.sql"), key=lambda p: (version(p), p.name))


def apply_migrations(conn: sqlite3.Connection, files: list[Path]) -> None:
    conn.execute("PRAGMA foreign_keys=ON")
    for path in files:
        try:
            conn.executescript(path.read_text(encoding="utf-8-sig"))
        except Exception as exc:
            raise AssertionError(f"migration {path.name} failed: {exc}") from exc


def table_columns(conn: sqlite3.Connection, table: str) -> set[str]:
    return {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}


def check_migrations() -> None:
    files = migration_files(MIGRATIONS)
    versions = [version(p) for p in files]
    assert versions == list(range(1, 45)), f"expected sequential V1..V44, got {versions}"

    # v7.1 must append migrations only. Compare the immutable v7 migrations byte-for-byte
    # against the retained release hash manifest so this also works from a source ZIP without .git.
    legacy = json.loads((ROOT / "tools/v7-legacy-migration-sha256.json").read_text(encoding="utf-8"))
    for path in files:
        if version(path) > 36:
            continue
        expected = legacy["catalog"].get(path.name)
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        assert expected == actual, f"immutable v7 migration changed: {path.name}"

    conn = sqlite3.connect(":memory:")
    apply_migrations(conn, files)
    for table in ("books", "authors", "author_identities", "catalog_sources", "catalog_manifests",
                  "book_identities", "book_artifacts", "catalog_dataset_metadata",
                  "catalog_record_provenance", "book_source_relations", "artifact_occurrences", "reader_book_preferences"):
        row = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone()
        assert row, f"missing table {table} after V44"
    manifest_cols = table_columns(conn, "catalog_manifests")
    for column in ("manifest_schema", "importer_version", "source_format", "normalization_version",
                   "fingerprint_model", "fingerprint_version", "processing_flags", "features_enabled"):
        assert column in manifest_cols, f"V39 manifest compatibility column missing: {column}"
    assert "applied_version" in table_columns(conn, "catalog_sources")
    assert "display_name" in table_columns(conn, "authors")
    assert "missing_since" in table_columns(conn, "books"), "V44 missing-file state column missing"

    conn.execute("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES ('a1','John','','Smith')")
    conn.execute("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES ('a2','John','','Smith')")
    assert conn.execute("SELECT count(*) FROM authors WHERE first_name='John' AND last_name='Smith'").fetchone()[0] == 2
    plan = " ".join(str(x) for x in conn.execute(
        "EXPLAIN QUERY PLAN SELECT id FROM authors WHERE first_name=? AND middle_name=? AND last_name=?",
        ("John", "", "Smith")).fetchone())
    assert "idx_authors_name_lookup" in plan, f"author lookup does not use index: {plan}"
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    conn.close()


def check_upgrade_preserves_data() -> None:
    files = migration_files(MIGRATIONS)
    conn = sqlite3.connect(":memory:")
    apply_migrations(conn, [p for p in files if version(p) <= 36])

    conn.execute("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES ('author-v7','Same','','Name')")
    conn.execute("""INSERT INTO books(id,title,file_name,rate,progress,review,deleted,local,lib_id)
                    VALUES ('book-v7-upgrade','Keep Me','keep.fb2',5,42,'user note',0,1,'LIB-V7')""")
    conn.execute("INSERT INTO book_authors(book_id,author_id) VALUES ('book-v7-upgrade','author-v7')")
    conn.execute("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,percent,updated_at) VALUES ('book-v7-upgrade','p1',7,42.0,'2026-01-01')")
    conn.execute("INSERT INTO reading_history(book_id,last_opened_at,open_count) VALUES ('book-v7-upgrade','2026-01-02',3)")
    conn.execute("INSERT INTO bookmarks(id,book_id,paragraph_id,char_offset,position,created_at) VALUES ('bm-v7','book-v7-upgrade','p1',1,0.5,'2026-01-03')")
    group = conn.execute("SELECT id FROM groups ORDER BY id LIMIT 1").fetchone()
    if group:
        conn.execute("INSERT OR IGNORE INTO book_groups(book_id,group_id) VALUES (?,?)", ("book-v7-upgrade", group[0]))
    before = {
        "book": conn.execute("SELECT title,rate,progress,review,local,lib_id FROM books WHERE id='book-v7-upgrade'").fetchone(),
        "authors": conn.execute("SELECT count(*) FROM book_authors WHERE book_id='book-v7-upgrade'").fetchone()[0],
        "progress": conn.execute("SELECT paragraph_id,char_offset,percent FROM reading_progress WHERE book_id='book-v7-upgrade'").fetchone(),
        "history": conn.execute("SELECT last_opened_at,open_count FROM reading_history WHERE book_id='book-v7-upgrade'").fetchone(),
        "bookmark": conn.execute("SELECT book_id,paragraph_id FROM bookmarks WHERE id='bm-v7'").fetchone(),
        "groups": conn.execute("SELECT count(*) FROM book_groups WHERE book_id='book-v7-upgrade'").fetchone()[0],
    }

    apply_migrations(conn, [p for p in files if 37 <= version(p) <= 44])
    after = {
        "book": conn.execute("SELECT title,rate,progress,review,local,lib_id FROM books WHERE id='book-v7-upgrade'").fetchone(),
        "authors": conn.execute("SELECT count(*) FROM book_authors WHERE book_id='book-v7-upgrade'").fetchone()[0],
        "progress": conn.execute("SELECT paragraph_id,char_offset,percent FROM reading_progress WHERE book_id='book-v7-upgrade'").fetchone(),
        "history": conn.execute("SELECT last_opened_at,open_count FROM reading_history WHERE book_id='book-v7-upgrade'").fetchone(),
        "bookmark": conn.execute("SELECT book_id,paragraph_id FROM bookmarks WHERE id='bm-v7'").fetchone(),
        "groups": conn.execute("SELECT count(*) FROM book_groups WHERE book_id='book-v7-upgrade'").fetchone()[0],
    }
    assert before == after, f"v7 user data changed during V37-V44: {before} -> {after}"
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    conn.close()



def check_v41_reading_stats_singleton() -> None:
    files = migration_files(MIGRATIONS)
    conn = sqlite3.connect(":memory:")
    apply_migrations(conn, [p for p in files if version(p) <= 40])
    conn.execute("INSERT INTO books(id,title,file_name) VALUES ('stats-book','Stats','stats.fb2')")
    conn.execute("""INSERT INTO reading_stats(book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,start_percent,end_percent,current_percent,completed_at)
                    VALUES ('stats-book','2026-01-01 00:00:00.000','2026-01-02 00:00:00.000',10,1,0,10,10,NULL)""")
    conn.execute("""INSERT INTO reading_stats(book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,start_percent,end_percent,current_percent,completed_at)
                    VALUES ('stats-book','2026-01-01 00:00:00.000','2026-01-03 00:00:00.000',30,2,0,30,30,NULL)""")
    apply_migrations(conn, [p for p in files if version(p) == 41])
    rows = conn.execute("SELECT last_read_at,total_reading_seconds,reading_sessions,current_percent FROM reading_stats WHERE book_id='stats-book'").fetchall()
    assert rows == [('2026-01-03 00:00:00.000', 30, 2, 30)], f"V41 kept wrong reading_stats snapshot: {rows}"
    try:
        conn.execute("""INSERT INTO reading_stats(book_id,first_read_at,last_read_at)
                        VALUES ('stats-book','2026-02-01 00:00:00.000','2026-02-01 00:00:00.000')""")
        raise AssertionError("V41 UNIQUE(book_id) did not reject duplicate reading_stats")
    except sqlite3.IntegrityError:
        pass
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    conn.close()


def check_v42_reader_book_preferences() -> None:
    files = migration_files(MIGRATIONS)
    conn = sqlite3.connect(":memory:")
    apply_migrations(conn, files)
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("INSERT INTO books(id,title,file_name) VALUES ('reader-pref-book','Reader','reader.fb2')")
    payload = '{"fontFamily":"Mono"}'
    conn.execute("INSERT INTO reader_book_preferences(book_id,preferences_json) VALUES (?,?)", ('reader-pref-book', payload))
    assert conn.execute("SELECT preferences_json FROM reader_book_preferences WHERE book_id='reader-pref-book'").fetchone()[0] == payload
    conn.execute("DELETE FROM books WHERE id='reader-pref-book'")
    assert conn.execute("SELECT count(*) FROM reader_book_preferences WHERE book_id='reader-pref-book'").fetchone()[0] == 0, \
        "V42 reader preference row did not cascade with book deletion"
    conn.close()


def check_v43_group_membership_index() -> None:
    files = migration_files(MIGRATIONS)
    conn = sqlite3.connect(":memory:")
    apply_migrations(conn, files)
    indexes = {row[1] for row in conn.execute("PRAGMA index_list(book_groups)")}
    assert "idx_book_groups_group_book" in indexes, "V43 group-leading membership index missing"
    plan = " ".join(str(row) for row in conn.execute(
        "EXPLAIN QUERY PLAN SELECT book_id FROM book_groups WHERE group_id=? ORDER BY book_id LIMIT 100", (1,)
    ))
    assert "idx_book_groups_group_book" in plan, f"V43 group lookup does not use index: {plan}"
    conn.close()

def check_metadata_migrations() -> None:
    files = migration_files(META_MIGRATIONS)
    assert [version(p) for p in files] == list(range(1, 6)), "expected metadata V1..V5"
    legacy = json.loads((ROOT / "tools/v7-legacy-migration-sha256.json").read_text(encoding="utf-8"))
    for path in files:
        if version(path) <= 3:
            assert hashlib.sha256(path.read_bytes()).hexdigest() == legacy["metadata"].get(path.name), \
                f"immutable v7 metadata migration changed: {path.name}"
    conn = sqlite3.connect(":memory:")
    apply_migrations(conn, files)
    assert conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='collections'").fetchone()
    assert "connection_script" in table_columns(conn, "collections")
    assert conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='online_download_queue'").fetchone()
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    conn.close()


def check_xml() -> None:
    failures: list[str] = []
    for path in ROOT.rglob("*.xml"):
        if any(part in {"target", ".git"} for part in path.parts):
            continue
        try:
            ET.parse(path)
        except Exception as exc:
            failures.append(f"{path.relative_to(ROOT)}: {exc}")
    for path in ROOT.rglob("*.fxml"):
        if any(part in {"target", ".git"} for part in path.parts):
            continue
        try:
            ET.parse(path)
        except Exception as exc:
            failures.append(f"{path.relative_to(ROOT)}: {exc}")
    assert not failures, "XML/FXML parse failures:\n" + "\n".join(failures[:20])


def read(rel: str) -> str:
    path = ROOT / rel
    assert path.exists(), f"missing required file: {rel}"
    return path.read_text(encoding="utf-8-sig")


def require(text: str, needle: str, label: str) -> None:
    assert needle in text, f"missing v7.1 invariant: {label} ({needle!r})"


def forbid(text: str, needle: str, label: str) -> None:
    assert needle not in text, f"forbidden stale invariant: {label} ({needle!r})"


def check_source_invariants() -> None:
    assert not (ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/postgres/PostgresBookRepository.java").exists(), "stale PostgresBookRepository.java present"

    lang = read("myhomelib-domain/src/main/java/com/myhomelibcorp/domain/service/LanguageResolver.java")
    require(lang, 'LanguageCode.of("und")', "unknown language resolves to und")
    require(lang, '"ua", "uk"', "ua language alias")
    require(lang, '"rus", "ru"', "rus language alias")

    isbn = read("myhomelib-domain/src/main/java/com/myhomelibcorp/domain/model/valueobject/Isbn.java")
    require(isbn, "tryParse", "safe ISBN parser")
    require(isbn, "valid10", "ISBN-10 checksum")
    require(isbn, "valid13", "ISBN-13 checksum")

    v34 = read("myhomelib-infrastructure/src/main/resources/db/migration/V34__author_external_identity.sql")
    require(v34, "DROP INDEX IF EXISTS idx_authors_unique_name", "remove name uniqueness")
    require(v34, "CREATE TABLE IF NOT EXISTS author_identities", "external author identity")
    require(v34, "idx_authors_name_lookup", "indexed author fallback")

    writer = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/JdbcBatchWriter.java")
    require(writer, "first_name = ? AND middle_name = ? AND last_name = ?", "exact indexed author lookup")
    forbid(writer, "COALESCE(first_name", "non-indexable author lookup")

    inpx = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxImportPipeline.java")
    require(inpx, "AuthorNameKey", "structured INPX author identity")
    forbid(inpx, 'first + "|"', "delimiter author identity")
    require(inpx, "only an explicit DEL marker may mark a book deleted", "explicit record-driven deletion semantics")
    require(inpx, "row.explicitlyDeleted()", "explicit DEL accounting")
    forbid(inpx, "markTrackedBooksMissing", "absence-based mass deletion during INPX import")

    dictionary_cache = ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/cache/DictionaryCache.java"
    assert not dictionary_cache.exists(), "full-table DictionaryCache must remain removed for 1M+ heap safety"

    profile = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/CatalogSourceProfile.java")
    for marker in ("flibusta_online_fb2.inpx", "flibusta_online_fb2.info", "flibusta_online_fb2.zip", "extra_flibusta_online_fb2.info", "extra_flibusta_online_fb2.zip"):
        require(profile, marker, f"MHL profile {marker}")

    downloader = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/HttpRemoteCatalogDownloadAdapter.java")
    for marker in (".part", "Range", "Content-Range", "ETag", "Last-Modified", "text/html", "atomic"):
        require(downloader, marker, f"downloader {marker}")
    resume_support = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/HttpResumeSupport.java")
    require(resume_support, "Sha256Support", "downloader shared SHA-256")
    sha256 = read("myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util/Sha256Support.java")
    require(sha256, 'MessageDigest.getInstance("SHA-256")', "shared SHA-256 implementation")

    update = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/UpdateCollectionFromNetworkUseCase.java")
    require(update, "CatalogSourceStatePort", "durable source state")
    require(update, "recordApplied", "post-success applied version")
    require(update, "applyIncrementalIndex", "delta Lucene indexing")
    require(update, "beginAtomicUpdate", "atomic delta index")
    forbid(update, "ApplicationSettingsPort", "catalog version in settings")

    lucene = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneSearchService.java")
    lucene_factory = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneIndexWriterFactory.java")
    require(lucene, "rollbackAtomicUpdate", "Lucene rollback")
    require(lucene_factory, "setCommitOnClose(false)", "no hidden close commit")
    require(lucene, "bookQueryRepository.streamAll()", "bounded/keyset rebuild source")
    require(lucene, "SearchIndexPerformanceReport", "Lucene performance telemetry")

    encryption = read("myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util/EncryptionUtil.java")
    require(encryption, "AES/GCM/NoPadding", "AES-GCM credentials")
    require(encryption, "refusing plaintext credential storage", "fail-closed credential storage")
    require(encryption, "private static final SecretKey secretKey = initializeKey();", "eager fail-closed credential key")
    forbid(encryption, "isFallbackMode()", "legacy fallback sentinel API")
    forbid(encryption, "plaintext fallback", "plaintext encryption fallback")

    metabib = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/catalog/reader/MetabibCatalogReader.java")
    require(metabib, 'DATASET_SCHEMA = "metabib.dataset/1"', "metabib header schema")
    require(metabib, "ZstdInputStream", "zstd streaming")
    require(metabib, "GZIPInputStream", "gzip streaming")
    require(metabib, "ZipFile", "zip streaming")

    neutral = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/catalog/importing/JdbcCatalogImportAdapter.java")
    batch_writer = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/catalog/importing/JdbcCatalogBatchWriter.java")
    manifest_store = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/catalog/importing/CatalogImportManifestStore.java")
    require(neutral, "temp_catalog_seen_v7", "memory bounded full snapshot semantics")
    require(batch_writer, "book_identities", "book external identities")
    require(batch_writer, "book_artifacts", "artifact persistence")
    require(manifest_store, "catalog_manifests", "manifest cache")
    require(manifest_store, "MANIFEST_SCHEMA", "manifest compatibility policy")
    forbid(neutral, "List<CatalogRecord> all", "full dataset buffering")
    forbid(batch_writer, "List<CatalogRecord> all", "full dataset buffering")

    import_usecase = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/imports/ImportFileUseCase.java")
    require(import_usecase, "CatalogImportPort", "neutral application import boundary")

    scenario = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/ConnectionScriptExecutor.java")
    for command in ("GET", "POST", "ADD", "CHECK", "REDIR", "PAUSE"):
        require(scenario, command, f"ConnectionScript {command}")
    forbid(scenario, "Runtime.exec", "ConnectionScript dynamic shell execution")

    http_policy = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/OnlineHttpPolicy.java")
    require(http_policy, "TrustManagerFactory", "custom trust-store with JVM validation")
    forbid(http_policy, "X509TrustManager", "trust-all TLS implementation")
    payload_validator = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/DownloadPayloadValidator.java")
    require(payload_validator, "online.archive.highReliabilityValidation", "opt-in archive integrity mode")
    require(payload_validator, "CRC32", "ZIP CRC validation")
    require(payload_validator, "entry.getSize()", "ZIP uncompressed size validation")
    require(payload_validator, "дубльоване", "duplicate archive entry rejection")

    stats = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteStatisticsRepository.java")
    forbid(stats, "catch (Exception", "statistics fake-zero exception swallowing")
    forbid(stats, "long duplicates = 0", "statistics hard-coded duplicate count")
    forbid(stats, "long missingCovers = 0", "statistics hard-coded missing-cover count")
    require(stats, "SUM(cnt - 1)", "statistics physical duplicate aggregation")
    require(stats, "COALESCE(cover_hash,'')", "statistics missing-cover aggregation")
    require(stats, "void invalidate()", "statistics cache invalidation")
    download_usecase = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/DownloadBookUseCase.java")
    remove_local = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/RemoveLocalBookCopyUseCase.java")
    require(download_usecase, "statisticsRepository.invalidate()", "statistics invalidation after online download")
    require(remove_local, "statisticsRepository.invalidate()", "statistics invalidation after local removal")

    queue = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteDownloadQueueAdapter.java")
    require(queue, "IN_PROGRESS", "persistent queue restart recovery")

    for required_doc in (
            "ARCHITECTURE.md",
            "MYHOMELIB-FEATURES.md",
            "MYHOMELIB-OPERATIONS.md",
            "MYHOMELIB-DEVELOPMENT.md",
            "MYHOMELIB-RELEASE.md",
            "docs/history/MYHOMELIB-HISTORY-STAGES.md",
            "docs/history/MYHOMELIB-HISTORY-FIXES.md",
            "docs/history/MYHOMELIB-HISTORY-AUDITS.md",
            "RELEASE_VALIDATION-v7.1.txt"):
        read(required_doc)

    pom = read("pom.xml")
    require(pom, "<version>7.1.0</version>", "v7.1 release version")
    readme = read("README.md")
    require(readme, "MyHomeLib Enterprise 7.1.0", "README release identity")
    forbid(readme, "# MyHomeLib Enterprise 1.0.0", "stale README release identity")
    root_md = sorted(p.name for p in ROOT.glob("*.md"))
    expected_root_md = sorted(["README.md", "ARCHITECTURE.md", "MYHOMELIB-FEATURES.md", "MYHOMELIB-OPERATIONS.md", "MYHOMELIB-DEVELOPMENT.md", "MYHOMELIB-RELEASE.md"])
    if root_md != expected_root_md:
        errors.append(f"root Markdown documentation drift: expected {expected_root_md}, got {root_md}")
    for active_doc in (
            "MYHOMELIB-RELEASE.md",
            "myhomelib-ui/src/main/resources/help/index.md",
            "myhomelib-ui/src/main/resources/help/index.txt",
            "myhomelib-ui/src/main/resources/help/mcp.md",
            "myhomelib-ui/src/main/resources/help/mcp.txt",
            "myhomelib-ui/src/main/resources/help/en/index.md",
            "myhomelib-ui/src/main/resources/help/en/index.txt",
            "myhomelib-ui/src/main/resources/help/en/mcp.md",
            "myhomelib-ui/src/main/resources/help/en/mcp.txt",
            "myhomelib-ui/src/main/resources/help/bg/index.md",
            "myhomelib-ui/src/main/resources/help/bg/index.txt",
            "myhomelib-ui/src/main/resources/help/bg/mcp.md",
            "myhomelib-ui/src/main/resources/help/bg/mcp.txt"):
        content = read(active_doc)
        forbid(content, "1.0.0", f"stale active release identity in {active_doc}")

    mvnw = read("mvnw")
    assert mvnw.startswith("#!/bin/sh"), "Unix mvnw is not a shell script"
    assert (ROOT / "myhomelib-infrastructure/src/main/resources/db/migration/V35__catalog_source_sync_state.sql").exists()
    assert (ROOT / "myhomelib-infrastructure/src/main/resources/db/migration/V36__catalog_manifest_and_artifacts.sql").exists()


def check_no_generated_or_secrets() -> None:
    bad_dirs = []
    for path in ROOT.rglob("*"):
        if path.is_dir() and path.name in {"target", ".idea", ".gradle", "__pycache__"}:
            bad_dirs.append(str(path.relative_to(ROOT)))
    assert not bad_dirs, "generated/IDE directories present: " + ", ".join(bad_dirs[:20])

    suspicious = []
    secret_patterns = [
        re.compile(r"(?i)(password|passwd|api[_-]?key|token|secret)\s*[=:]\s*['\"]?[A-Za-z0-9+/=_-]{16,}"),
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    ]
    for path in ROOT.rglob("*"):
        if not path.is_file() or any(part in {".git"} for part in path.parts):
            continue
        if path.suffix.lower() in {".zip", ".jar", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".pdf", ".db"}:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue
        for pattern in secret_patterns:
            if pattern.search(text):
                suspicious.append(str(path.relative_to(ROOT)))
                break
    # Expected code/config examples may mention keys; only fail on likely real secret-bearing local files.
    suspicious = [p for p in suspicious if not p.endswith((".java", ".md", ".yml", ".yaml", ".properties", ".xml", ".ps1", ".sh", ".cmd"))]
    assert not suspicious, "possible secret-bearing files: " + ", ".join(suspicious[:20])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-tree-cleanliness", action="store_true", help="allow target/IDE dirs while developing")
    args = parser.parse_args()
    checks = [
        ("Flyway V1-V44 + immutable V1-V36", check_migrations),
        ("V41 reading statistics singleton", check_v41_reading_stats_singleton),
        ("V42 reader book preferences", check_v42_reader_book_preferences),
        ("V43 group membership lookup", check_v43_group_membership_index),
        ("existing v7 DB -> v7.1 user-data upgrade", check_upgrade_preserves_data),
        ("metadata migrations", check_metadata_migrations),
        ("XML/FXML", check_xml),
        ("v7.1 source invariants", check_source_invariants),
    ]
    if not args.skip_tree_cleanliness:
        checks.append(("release tree cleanliness", check_no_generated_or_secrets))

    print("MyHomeLib Enterprise v7.1 offline release checks")
    print("=" * 48)
    failed = False
    for name, fn in checks:
        try:
            fn()
            print(f"PASS  {name}")
        except Exception as exc:
            failed = True
            print(f"FAIL  {name}: {exc}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
