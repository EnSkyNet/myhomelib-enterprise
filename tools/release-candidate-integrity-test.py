#!/usr/bin/env python3
from __future__ import annotations
import importlib.util
import json
import tempfile
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("rc_integrity", HERE / "release-candidate-integrity.py")
assert spec and spec.loader
mod = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)

def expect_fail(fn, text: str):
    try:
        fn()
    except Exception as exc:
        assert text.lower() in str(exc).lower(), (text, str(exc))
    else:
        raise AssertionError("expected failure")

def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        (root / ".github/workflows").mkdir(parents=True)
        (root / "tools").mkdir()
        (root / "pom.xml").write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>x</artifactId><version>7.1.0</version></project>''', encoding="utf-8")
        for rel in mod.CRITICAL_POLICY_PATHS[1:]:
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(rel + "\n", encoding="utf-8")
        (root / "src.txt").write_text("hello\n", encoding="utf-8")
        (root / "target").mkdir()
        (root / "target/ignored.txt").write_text("ignored", encoding="utf-8")
        dist = root / "dist"
        dist.mkdir()
        (dist / "app.zip").write_bytes(b"candidate")
        import hashlib
        digest = hashlib.sha256(b"candidate").hexdigest()
        (dist / "SHA256SUMS").write_text(f"{digest}  app.zip\n", encoding="utf-8")
        out = root / "target" / "record.json"
        sha = "a" * 40
        record = mod.build_record(root, dist, "windows", sha)
        assert record["candidateSha"] == sha and record["platform"] == "windows"
        assert all("target/" not in row["path"] for row in record["criticalPolicyFiles"])
        mod.write_record(record, out)
        mod.verify_record(out, root, dist, "windows", sha)
        first = json.loads(out.read_text())
        second = mod.build_record(root, dist, "windows", sha)
        assert first == second
        (dist / "extra.bin").write_bytes(b"not checksummed")
        expect_fail(lambda: mod.build_record(root, dist, "windows", sha), "exactly match")
        (dist / "extra.bin").unlink()
        (root / "src.txt").write_text("changed\n", encoding="utf-8")
        expect_fail(lambda: mod.verify_record(out, root, dist, "windows", sha), "does not match")
        expect_fail(lambda: mod.build_record(root, dist, "windows", "abc"), "40-character")
    print("release candidate integrity regression tests: PASS")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
