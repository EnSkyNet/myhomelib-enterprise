#!/usr/bin/env python3
"""Ratchet managed background execution/backpressure rules for production Java code."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []


def java_sources():
    yield from ROOT.glob("myhomelib-*/src/main/java/**/*.java")


def strip_comments(text: str) -> str:
    # Enough for policy tokens; preserve strings because async calls can contain lambdas/strings.
    import re
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def call_has_top_level_comma(text: str, marker: str, start: int) -> bool:
    open_pos = text.find("(", start + len(marker) - 1)
    if open_pos < 0:
        return False
    depth = 1
    in_string = False
    in_char = False
    escape = False
    i = open_pos + 1
    while i < len(text) and depth:
        ch = text[i]
        if escape:
            escape = False
        elif ch == "\\" and (in_string or in_char):
            escape = True
        elif ch == '"' and not in_char:
            in_string = not in_string
        elif ch == "'" and not in_string:
            in_char = not in_char
        elif not in_string and not in_char:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            elif ch == "," and depth == 1:
                return True
        i += 1
    return False


for path in java_sources():
    rel = path.relative_to(ROOT).as_posix()
    text = strip_comments(path.read_text(encoding="utf-8", errors="ignore"))

    if "new ThreadPoolExecutor.CallerRunsPolicy" in text or "new CallerRunsPolicy" in text:
        ERRORS.append(f"CallerRunsPolicy is forbidden in production: {rel}")

    for marker in ("CompletableFuture.supplyAsync(", "CompletableFuture.runAsync("):
        pos = 0
        while True:
            pos = text.find(marker, pos)
            if pos < 0:
                break
            if not call_has_top_level_comma(text, marker, pos):
                line = text.count("\n", 0, pos) + 1
                ERRORS.append(f"unqualified commonPool async call: {rel}:{line} ({marker[:-1]})")
            pos += len(marker)

    for forbidden in ("Executors.newFixedThreadPool(", "Executors.newCachedThreadPool(",
                      "Executors.newWorkStealingPool(", "ForkJoinPool.commonPool("):
        if forbidden in text:
            ERRORS.append(f"unmanaged shared executor primitive '{forbidden[:-1]}' in {rel}")

# Acceptance-specific guards: startup/statistics/folder sync must use reviewed managed executors.
bootstrap = (ROOT / "myhomelib-bootstrap/src/main/java/com/myhomelibcorp/MyHomeLibApp.java").read_text(encoding="utf-8")
if "startupExecutor.submit(" not in bootstrap:
    ERRORS.append("startup backend initialization is not routed through ExecutorPort")

statistics = (ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/StatisticsController.java").read_text(encoding="utf-8")
if "backgroundExecutor.submit(" not in statistics:
    ERRORS.append("StatisticsController is not routed through UiBackgroundExecutor")

folder_sync = (ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/FolderSyncService.java").read_text(encoding="utf-8")
if '@Qualifier("ioExecutor") Executor ioExecutor' not in folder_sync or "ioExecutor.execute(" not in folder_sync:
    ERRORS.append("FolderSyncService async path is not routed through managed ioExecutor")

if ERRORS:
    print("MANAGED EXECUTOR CHECK: FAIL")
    for error in ERRORS:
        print(" -", error)
    sys.exit(1)

print("MANAGED EXECUTOR CHECK: PASS")
print(" - production CompletableFuture async calls use explicit executors")
print(" - no CallerRunsPolicy/commonPool/unmanaged fixed/cached pools")
print(" - startup, statistics and FolderSync use managed executor paths")
