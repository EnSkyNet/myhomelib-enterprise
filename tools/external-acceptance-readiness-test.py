#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import re
import shutil
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
SPEC = importlib.util.spec_from_file_location("external_acceptance_readiness", HERE / "external-acceptance-readiness.py")
assert SPEC and SPEC.loader
mod = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mod
SPEC.loader.exec_module(mod)


def expect_fail(fn, contains: str) -> None:
    try:
        fn()
    except mod.ReadinessError as exc:
        assert contains.lower() in str(exc).lower(), (contains, str(exc))
    else:
        raise AssertionError(f"expected ReadinessError containing {contains!r}")


def main() -> int:
    checks = mod.structural_checks(ROOT)
    ids = {c.id for c in checks}
    assert "release-identity" in ids
    assert "windows-harness-fingerprint" in ids
    assert mod.project_version(ROOT) == "8.0.0"
    sha, count = mod.harness_manifest_fingerprint(ROOT)
    assert len(sha) == 64 and count >= 10
    payload = mod.build_payload(ROOT, checks, False)
    assert payload["overall"] == "READY_FOR_LIVE_EVIDENCE"
    assert all(row["status"] == "OPEN_EXTERNAL" for row in payload["externalGates"])

    with tempfile.TemporaryDirectory(prefix="mhl-readiness-test-") as td:
        copy = Path(td) / "repo"
        shutil.copytree(ROOT, copy, ignore=shutil.ignore_patterns("target"))
        (copy / ".github/workflows/github-acceptance.yml").unlink()
        expect_fail(lambda: mod.structural_checks(copy), "github-acceptance.yml")

    with tempfile.TemporaryDirectory(prefix="mhl-readiness-command-test-") as td:
        copy = Path(td) / "repo"
        shutil.copytree(ROOT, copy, ignore=shutil.ignore_patterns("target"))
        runbook = copy / "docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md"
        runbook.write_text(
            runbook.read_text(encoding="utf-8").replace('-Repo "OWNER/REPO"', '-Repository "OWNER/REPO"'),
            encoding="utf-8",
        )
        expect_fail(lambda: mod.structural_checks(copy), "must use -Repo")

    with tempfile.TemporaryDirectory(prefix="mhl-readiness-current-validation-test-") as td:
        copy = Path(td) / "repo"
        shutil.copytree(ROOT, copy, ignore=shutil.ignore_patterns("target"))
        validation = copy / "docs/release/CURRENT-VALIDATION.md"
        text = validation.read_text(encoding="utf-8")
        text, changed = re.subn(
            r"((?:current Maven baseline aggregate(?:[^:]*):|exact-current R\d+ root Maven `?clean verify`?:)"
            r"\s*\*\*)(\d+)( tests /)",
            lambda m: m.group(1) + str(int(m.group(2)) - 1) + m.group(3),
            text, count=1, flags=re.IGNORECASE,
        )
        assert changed == 1
        validation.write_text(text, encoding="utf-8")
        expect_fail(lambda: mod.structural_checks(copy), "does not match")

    with tempfile.TemporaryDirectory(prefix="mhl-readiness-evidence-binding-test-") as td:
        copy = Path(td) / "repo"
        shutil.copytree(ROOT, copy, ignore=shutil.ignore_patterns("target"))
        validation = copy / "docs/release/CURRENT-VALIDATION.md"
        text = validation.read_text(encoding="utf-8")
        text, changed = re.subn(
            r"(Detailed exact-current evidence:\s*`docs/release/)POST-R\d+-R\d+(-VALIDATION-[^`]+`)",
            r"\1POST-R1-R2\2", text, count=1,
        )
        assert changed == 1
        validation.write_text(text, encoding="utf-8")
        expect_fail(lambda: mod.structural_checks(copy), "does not match current candidate")

    print("External acceptance readiness regression tests: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
