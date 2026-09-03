#!/usr/bin/env python3
"""Regression guard for collection-scoped UI async requests."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java": ["UiAsyncRequestGuard", "UiAsyncRequestToken"],
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java": ["UiAsyncRequestGuard", "UiAsyncRequestToken"],
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java": ["UiAsyncRequestGuard", "UiAsyncRequestToken"],
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java": ["UiAsyncRequestGuard", "UiAsyncRequestToken", "currentBookCollectionId"],
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/dashboard/DashboardController.java": ["UiAsyncRequestGuard", "UiAsyncRequestToken"],
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/StatisticsController.java": ["UiAsyncRequestGuard", "UiAsyncRequestToken"],
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/CollectionUpdateUiService.java": ["isActiveCollection(collection.getId())"],
}

errors = []
for relative, markers in REQUIRED.items():
    path = ROOT / relative
    if not path.exists():
        errors.append(f"missing file: {relative}")
        continue
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            errors.append(f"{relative}: missing {marker!r}")

helper = ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/util/UiAsyncRequestGuard.java"
if helper.exists():
    text = helper.read_text(encoding="utf-8")
    for marker in ("token.requestId() == generation.get()", "Objects.equals(token.collectionId(), currentCollectionId(state))"):
        if marker not in text:
            errors.append(f"UiAsyncRequestGuard: missing invariant {marker!r}")

if errors:
    print("ASYNC GENERATION AUDIT: FAIL")
    for error in errors:
        print(" -", error)
    sys.exit(1)

print("ASYNC GENERATION AUDIT: PASS")
print(f"Protected UI async boundaries: {len(REQUIRED)}")
