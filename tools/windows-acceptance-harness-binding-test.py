#!/usr/bin/env python3
"""Offline regression for acceptance harness candidate binding."""
from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("harness_binding", HERE / "windows-acceptance-harness-binding.py")
assert SPEC and SPEC.loader
mod = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mod
SPEC.loader.exec_module(mod)


def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td) / "repo"
        root.mkdir()
        for rel in mod.CRITICAL_FILES:
            src = mod.ROOT / rel
            dst = root / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
        manifest = Path(td) / "acceptance-harness.sha256"
        manifest_sha = mod.write_manifest(manifest, root)
        assert len(manifest_sha) == 64
        assert len(mod.verify_manifest(manifest, root)) == len(mod.CRITICAL_FILES)

        victim = root / mod.CRITICAL_FILES[-1]
        victim.write_bytes(victim.read_bytes() + b"\n# tamper\n")
        try:
            mod.verify_manifest(manifest, root)
        except mod.BindingError as exc:
            assert "hash mismatch" in str(exc).lower()
        else:
            raise AssertionError("tampered local acceptance harness unexpectedly passed")

    print("Windows acceptance harness binding regression tests: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
