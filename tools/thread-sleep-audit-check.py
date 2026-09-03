#!/usr/bin/env python3
"""Allow Thread.sleep only in reviewed infrastructure retry/backoff paths."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/config/DataSourceConfig.java",
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/HttpOnlineBookDownloadAdapter.java",
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/HttpRemoteCatalogDownloadAdapter.java",
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/ConnectionScriptExecutor.java",
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteBusyRetryExecutor.java",
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneIndexWriterFactory.java",
}
found = set()
errors = []
for path in ROOT.glob("myhomelib-*/src/main/java/**/*.java"):
    text = path.read_text(encoding="utf-8", errors="ignore")
    if "Thread.sleep(" not in text:
        continue
    rel = path.relative_to(ROOT).as_posix()
    found.add(rel)
    if rel not in ALLOWED:
        errors.append(f"unreviewed Thread.sleep: {rel}")
    if "/myhomelib-ui/" in f"/{rel}":
        errors.append(f"Thread.sleep in UI module: {rel}")
missing = ALLOWED - found
if missing:
    errors.extend(f"allowlist is stale (no Thread.sleep now): {rel}" for rel in sorted(missing))
if errors:
    print("THREAD.SLEEP AUDIT: FAIL")
    for error in errors: print(" -", error)
    sys.exit(1)
print("THREAD.SLEEP AUDIT: PASS")
print(f" - reviewed production locations: {len(found)}")
print(" - JavaFX/UI locations: 0")
