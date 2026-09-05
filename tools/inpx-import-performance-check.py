#!/usr/bin/env python3
from pathlib import Path
import sqlite3
import sys

ROOT = Path(__file__).resolve().parents[1]
writer = ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/JdbcBatchWriter.java"
migration = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration/V34__author_external_identity.sql"
config = ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/config/DataSourceConfig.java"
network_update = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/UpdateCollectionFromNetworkUseCase.java"
create_collection = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/CreateCollectionUseCase.java"
catalog_import = ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/catalog/importing/JdbcCatalogImportAdapter.java"
pipeline = ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxImportPipeline.java"

w = writer.read_text(encoding="utf-8")
m = migration.read_text(encoding="utf-8")
c = config.read_text(encoding="utf-8")
nu = network_update.read_text(encoding="utf-8")
cc = create_collection.read_text(encoding="utf-8")
ci = catalog_import.read_text(encoding="utf-8")
ip = pipeline.read_text(encoding="utf-8")

checks = []
checks.append(("author resolver uses raw indexed columns", "(first_name = ? AND middle_name = ? AND last_name = ?)" in w))
checks.append(("author resolver does not wrap lookup columns in COALESCE", "COALESCE(first_name,'') = ?" not in w and "COALESCE(last_name,'') = ?" not in w))
checks.append(("author pair is structured (pipe-safe)", "private record AuthorName" in w and "indexOf('|')" not in w))
checks.append(("indexed author lookup exists", "idx_authors_name_lookup" in m and "first_name, middle_name, last_name" in m))
checks.append(("long atomic import uses a 30-minute Hikari leak threshold", "LEAK_DETECTION_THRESHOLD_MS = 1_800_000L" in c))
checks.append(("online catalog caller remains compatible with generic 1000-row request", ".batchSize(1000)" in nu))
checks.append(("online INPX pipeline raises effective batch to configured 5000",
               "app.import.online-batch-size:5000" in ip
               and "Math.max(requestedBatch, Math.max(1_000, Math.min(onlineBatchSize, 10_000)))" in ip))
checks.append(("create-with-source uses benchmark-selected 1000-row batches", ".batchSize(1000)" in cc and ".batchSize(5000)" not in cc))
checks.append(("generic catalog fallback batch is 1000", "DEFAULT_BATCH = 1_000" in ci and "DEFAULT_BATCH = 5_000" not in ci))

# Verify SQLite's planner can use the exact project index for the new predicate.
con = sqlite3.connect(":memory:")
con.execute("CREATE TABLE authors(id TEXT PRIMARY KEY, first_name TEXT, middle_name TEXT, last_name TEXT)")
con.execute("CREATE INDEX idx_authors_name_lookup ON authors(first_name, middle_name, last_name)")
plan = " ".join(str(row) for row in con.execute(
    "EXPLAIN QUERY PLAN SELECT id FROM authors WHERE first_name=? AND middle_name=? AND last_name=?", ("A", "", "B")
))
checks.append(("SQLite query plan uses idx_authors_name_lookup", "idx_authors_name_lookup" in plan))

failed = [name for name, ok in checks if not ok]
print("INPX IMPORT HOT-PATH CHECK")
for name, ok in checks:
    print(f" - {name}: {'PASS' if ok else 'FAIL'}")
if failed:
    sys.exit(1)
print("INPX IMPORT HOT-PATH CHECK: PASS")
