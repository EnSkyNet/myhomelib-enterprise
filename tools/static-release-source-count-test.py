#!/usr/bin/env python3
"""Regression: static release source metrics must ignore generated target sources."""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET_ROOT = ROOT / "myhomelib-domain/target"
DUMMY = TARGET_ROOT / "generated-sources/regression/DummyGeneratedForStaticCount.java"
COUNT_RE = re.compile(r"Java sources: (\d+); test sources: (\d+)")

def counts() -> tuple[int, int]:
    cp = subprocess.run([sys.executable, str(ROOT / "tools/static_release_check.py")], cwd=ROOT,
                        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True,
                        env={**__import__('os').environ, 'PYTHONDONTWRITEBYTECODE':'1'})
    m = COUNT_RE.search(cp.stdout)
    assert m, cp.stdout
    return int(m.group(1)), int(m.group(2))

before = counts()
target_existed = TARGET_ROOT.exists()
DUMMY.parent.mkdir(parents=True, exist_ok=True)
DUMMY.write_text("final class DummyGeneratedForStaticCount {}\n", encoding="utf-8")
try:
    after = counts()
finally:
    DUMMY.unlink(missing_ok=True)
    # remove only empty directories created by this regression fixture
    for p in [DUMMY.parent, DUMMY.parent.parent]:
        try:
            p.rmdir()
        except OSError:
            pass
    if not target_existed:
        try:
            TARGET_ROOT.rmdir()
        except OSError:
            pass
assert after == before, f"target/generated source changed static metrics: before={before}, after={after}"
print(f"PASS: static source metrics ignore target/generated-sources ({before[0]} Java, {before[1]} tests)")
