#!/usr/bin/env python3
"""Regression: static release source metrics must ignore generated target sources."""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from source_archive_policy import source_files

ROOT = Path(__file__).resolve().parents[1]
COUNT_RE = re.compile(r"Java sources: (\d+); test sources: (\d+)")

def counts(tree: Path) -> tuple[int, int]:
    cp = subprocess.run([sys.executable, str(tree / "tools/static_release_check.py")], cwd=tree,
                        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True,
                        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
    m = COUNT_RE.search(cp.stdout)
    assert m, cp.stdout
    return int(m.group(1)), int(m.group(2))

def main() -> None:
    # Keep generated fixtures out of the working tree, including on failure.
    with tempfile.TemporaryDirectory(prefix="mhl-source-count-") as directory:
        tree = Path(directory)
        for source in source_files(ROOT):
            destination = tree / source.relative_to(ROOT)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        before = counts(tree)
        dummy = tree / "myhomelib-domain/target/generated-sources/regression/DummyGeneratedForStaticCount.java"
        dummy.parent.mkdir(parents=True, exist_ok=True)
        dummy.write_text("final class DummyGeneratedForStaticCount {}\n", encoding="utf-8")
        after = counts(tree)
        assert after == before, f"target/generated source changed static metrics: before={before}, after={after}"
    print(f"PASS: static source metrics ignore target/generated-sources ({before[0]} Java, {before[1]} tests)")


if __name__ == "__main__":
    main()
