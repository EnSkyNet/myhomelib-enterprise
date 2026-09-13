#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def text(path):
    p = ROOT / path
    assert p.is_file(), f"missing {path}"
    return p.read_text(encoding="utf-8")

migration = text("myhomelib-infrastructure/src/main/resources/db/migration/V56__artifact_integrity_audit.sql")
assert "artifact_integrity_state" in migration
assert "baseline_sha256" in migration and "last_modified_millis" in migration
assert "CORRUPT_ARCHIVE" in migration and "HASH_CHANGED" in migration

scanner = text("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/integrity/ArtifactIntegrityService.java")
for token in ["auditIncremental", "Files.getLastModifiedTime", "MessageDigest", "listArchiveEntries", "readBookData", "artifact_integrity_state", "!/" ]:
    assert token in scanner, f"scanner missing {token}"
assert "UPDATE book_artifacts" not in scanner, "audit must not rewrite catalogue artifact baseline"
assert "DELETE FROM book_artifacts" not in scanner, "audit must not destructively repair artifacts"

health = text("myhomelib-application/src/main/java/com/myhomelibcorp/application/health/LibraryHealthService.java")
for token in ["MISSING_ARTIFACTS", "CORRUPT_ARTIFACTS", "CHANGED_ARTIFACTS", "DUPLICATES", "METADATA_GAPS", "STALE_SEARCH_INDEX", "BACKUP_AGE", "INTEGRITY_AUDIT"]:
    assert token in health, f"health service missing {token}"

ui = text("myhomelib-ui/src/main/resources/view/integrity-check.fxml")
ui_controller = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/IntegrityCheckController.java")
for token in ["Library Health", "onShowMissing", "onShowCorrupt", "onShowChanged", "onShowDuplicates", "onShowMetadata", "onShowIndex", "onShowBackup", "onExportReport", "issueTable", "detailArea"]:
    assert token in ui, f"dashboard missing {token}"
assert "executor.submit(healthService::refresh)" in ui_controller, "Library Health refresh must stay off the FX thread"

benchmark = text("myhomelib-benchmark/src/main/java/com/myhomelibcorp/benchmark/search/SmartCollectionLuceneBenchmark.java")
for token in ["DEFAULT_DOCUMENTS = 500_000", "cold_first_page", "warm_first_page", "and_5_rules", "or_20_rules", "numeric_range", "docvalues_sort", "bounded_max_results", "rssBytes", "gcCollections"]:
    assert token in benchmark, f"benchmark missing {token}"

print("stage34 artifact-integrity/library-health check: PASS")
