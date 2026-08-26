#!/usr/bin/env python3
from pathlib import Path
import sqlite3
import sys

ROOT = Path(__file__).resolve().parents[1]
writer = ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/JdbcBatchWriter.java"
migration = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration/V7__add_unique_constraint_to_authors.sql"
config = ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/config/DataSourceConfig.java"
network_update = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/UpdateCollectionFromNetworkUseCase.java"
create_collection = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/CreateCollectionUseCase.java"

w = writer.read_text(encoding="utf-8")
m = migration.read_text(encoding="utf-8")
c = config.read_text(encoding="utf-8")
nu = network_update.read_text(encoding="utf-8")
cc = create_collection.read_text(encoding="utf-8")

checks = []
checks.append(("author resolver uses raw indexed columns", "(first_name = ? AND last_name = ?)" in w))
checks.append(("author resolver does not wrap lookup columns in COALESCE", "COALESCE(first_name,'') = ?" not in w and "COALESCE(last_name,'') = ?" not in w))
checks.append(("author pair is structured (pipe-safe)", "private record AuthorPair" in w and "pair.indexOf('|')" not in w))
checks.append(("unique author lookup index exists", "idx_authors_unique_name ON authors(first_name, last_name)" in m))
checks.append(("long import does not trigger 10s Hikari false-positive", "LEAK_DETECTION_THRESHOLD_MS = 300_000L" in c))
checks.append(("online catalog update uses 5000-row batches", ".batchSize(5000)" in nu and ".batchSize(1000)" not in nu))
checks.append(("create-with-source uses 5000-row batches", ".batchSize(5000)" in cc))

# Verify SQLite's planner can use the exact project index for the new predicate.
con = sqlite3.connect(":memory:")
con.execute("CREATE TABLE authors(id TEXT PRIMARY KEY, first_name TEXT, middle_name TEXT, last_name TEXT)")
con.execute("CREATE UNIQUE INDEX idx_authors_unique_name ON authors(first_name, last_name)")
plan = " ".join(str(row) for row in con.execute(
    "EXPLAIN QUERY PLAN SELECT id FROM authors WHERE first_name=? AND last_name=?", ("A", "B")
))
checks.append(("SQLite query plan uses idx_authors_unique_name", "idx_authors_unique_name" in plan))

failed = [name for name, ok in checks if not ok]
print("INPX IMPORT HOT-PATH CHECK")
for name, ok in checks:
    print(f" - {name}: {'PASS' if ok else 'FAIL'}")
if failed:
    sys.exit(1)
print("INPX IMPORT HOT-PATH CHECK: PASS")
