#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "myhomelib-ui" / "src" / "main" / "java"
OWNER = JAVA_ROOT / "com" / "myhomelibcorp" / "ui" / "service" / "FileChooserService.java"
PATTERN = re.compile(r"\bnew\s+(FileChooser|DirectoryChooser)\s*\(")
violations = []
for path in sorted(JAVA_ROOT.rglob("*.java")):
    if path == OWNER:
        continue
    text = path.read_text(encoding="utf-8")
    for m in PATTERN.finditer(text):
        line = text.count("\n", 0, m.start()) + 1
        violations.append(f"{path.relative_to(ROOT)}:{line}: {m.group(1)}")
if violations:
    print("FAIL: native JavaFX chooser creation must be owned by FileChooserService")
    for v in violations:
        print(" -", v)
    sys.exit(1)
owner_text = OWNER.read_text(encoding="utf-8")
if not PATTERN.search(owner_text):
    print("FAIL: FileChooserService no longer owns a native chooser implementation")
    sys.exit(1)
print("PASS: FileChooserService is the sole native JavaFX chooser owner")
