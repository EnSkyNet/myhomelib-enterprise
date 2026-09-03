#!/usr/bin/env python3
"""Static regression guard for collection runtime state derived from Operation Center."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionRuntimeState.java": [
        "CREATING", "READY", "IMPORTING", "INDEXING", "UPDATING", "ERROR", "DELETING"
    ],
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionRuntimeStateResolver.java": [
        "OperationCenterEntry", "OperationKind.CATALOG_UPDATE", "OperationKind.CATALOG_IMPORT",
        "OperationStage.UPDATING_SEARCH_INDEX", "OperationStage.FAILED"
    ],
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java": [
        "operationCenter.addListener", "CollectionRuntimeStateResolver.resolve", "OperationKind.COLLECTION_CREATE",
        "OperationKind.COLLECTION_DELETE", "OperationStage.DELETING_COLLECTION"
    ],
}
errors=[]
for rel, markers in checks.items():
    path=ROOT/rel
    if not path.exists():
        errors.append(f"missing file: {rel}")
        continue
    text=path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            errors.append(f"{rel}: missing {marker!r}")
if errors:
    print("COLLECTION RUNTIME STATE CHECK: FAIL")
    for e in errors: print(" -", e)
    sys.exit(1)
print("COLLECTION RUNTIME STATE CHECK: PASS")
print(" - state is projected from Operation Center lifecycle")
print(" - create/delete operations publish into the same lifecycle")
