#!/usr/bin/env python3
"""Regression check for metadata-vs-collection transaction-manager wiring."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
files = {
    "meta": ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/config/MetadataDatabaseConfig.java",
    "collection_repo": ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteCollectionRepository.java",
    "author_repo": ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteAuthorRepository.java",
    "group_batch": ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/AddToGroupBatchUseCase.java",
}
for name, path in files.items():
    if not path.exists():
        print(f"FAIL: missing {name}: {path}")
        sys.exit(1)

meta = files["meta"].read_text(encoding="utf-8")
collection_repo = files["collection_repo"].read_text(encoding="utf-8")
author_repo = files["author_repo"].read_text(encoding="utf-8")
group_batch = files["group_batch"].read_text(encoding="utf-8")

requirements = [
    ("explicit metadata transaction-manager bean", '@Bean(name = "metadataTransactionManager")' in meta),
    ("metadata transaction manager uses metadataDataSource", '@Qualifier("metadataDataSource") DataSource metadataDataSource' in meta),
    ("metadata transaction manager is primary/default", '@Primary\n    @Bean(name = "metadataTransactionManager")' in meta),
    ("collection save uses metadata transaction manager", '@Transactional(transactionManager = "metadataTransactionManager")' in collection_repo),
    ("author reads use collection transaction manager", '@Transactional(transactionManager = "collectionTransactionManager", readOnly = true)' in author_repo),
    ("group batch uses collection transaction manager", '@Transactional(transactionManager = "collectionTransactionManager")' in group_batch),
]

failed = [name for name, ok in requirements if not ok]
if failed:
    for name in failed:
        print(f"FAIL: {name}")
    sys.exit(1)

# Catch the exact regression that caused startup to fail: metadata repository using a bare
# @Transactional while the only explicit manager is collection-scoped.
if "@Transactional\n    public Collection save" in collection_repo:
    print("FAIL: SqliteCollectionRepository.save() still has an unqualified @Transactional")
    sys.exit(1)

print("PASS: metadata/collection transaction managers are explicitly separated")
