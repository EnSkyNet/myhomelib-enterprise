#!/usr/bin/env python3
"""Offline regression tests for github-connected-acceptance.py pure evidence policy."""
from __future__ import annotations

import importlib.util
import hashlib
import io
import json
import tempfile
import zipfile
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("gha", HERE / "github-connected-acceptance.py")
assert SPEC and SPEC.loader
mod = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mod
SPEC.loader.exec_module(mod)


def expect_fail(fn, contains: str) -> None:
    try:
        fn()
    except mod.AcceptanceError as exc:
        assert contains.lower() in str(exc).lower(), (contains, str(exc))
    else:
        raise AssertionError(f"expected AcceptanceError containing {contains!r}")


def artifact_zip(candidate_sha: str = "a" * 40) -> bytes:
    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "serialNumber": "urn:uuid:00000000-0000-0000-0000-000000000001",
        "version": 1,
        "components": [{"type": "library", "name": "sqlite-jdbc", "version": "1"}],
    }
    bom_xml = b'''<?xml version="1.0" encoding="UTF-8"?>
<bom xmlns="http://cyclonedx.org/schema/bom/1.6" version="1"><components><component type="library"><name>sqlite-jdbc</name><version>1</version></component></components></bom>'''
    dep = {"dependencies": [{"fileName": "sqlite.jar", "vulnerabilities": []}]}
    codeql_gate = {
        "schemaVersion": 2,
        "scenario": "github-connected-acceptance",
        "candidateSha": candidate_sha,
        "timestamp": "2026-09-06T09:00:00Z",
        "githubApiVersion": "2026-03-10",
        "repository": "owner/repo",
        "branch": "main",
        "overall": "PASS",
        "checks": [{
            "id": "MHL-019-release-gate", "status": "PASS", "summary": "candidate CodeQL PASS",
            "details": {"candidateSha": candidate_sha},
        }],
    }
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("target/bom.json", json.dumps(bom))
        zf.writestr("target/bom.xml", bom_xml)
        zf.writestr("target/github-release-codeql-gate/github-connected-acceptance.json", json.dumps(codeql_gate))
        zf.writestr("module/target/dependency-check-report.json", json.dumps(dep))
        zf.writestr("module/target/dependency-check-report.sarif", "{}")
        zf.writestr("module/target/dependency-check-report.html", "<html>ok</html>")
    return out.getvalue()


def windows_artifact_zip(version: str = "7.1.0") -> tuple[bytes, str, str, str]:
    files = {
        f"MyHomeLib-{version}.msi": b"synthetic-msi-candidate",
        f"MyHomeLib-{version}.exe": b"synthetic-exe-candidate",
        f"myhomelib-{version}-windows-amd64.zip": b"synthetic-portable-candidate",
    }
    lines = []
    for name, data in files.items():
        lines.append(f"{hashlib.sha256(data).hexdigest()}  {name}")
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in files.items():
            zf.writestr(name, data)
        zf.writestr("SHA256SUMS", "\n".join(lines) + "\n")
    return (
        out.getvalue(),
        hashlib.sha256(files[f"MyHomeLib-{version}.msi"]).hexdigest(),
        hashlib.sha256(files[f"MyHomeLib-{version}.exe"]).hexdigest(),
        hashlib.sha256(files[f"myhomelib-{version}-windows-amd64.zip"]).hexdigest(),
    )


def main() -> int:
    rules = [{"type": "required_status_checks", "parameters": {"required_status_checks": [{"context": "Fast gate"}]}}]
    contexts = mod.required_status_contexts_from_branch_rules(rules)
    assert contexts == ["Fast gate"] and mod.has_fast_gate_context(contexts)
    assert mod.has_fast_gate_context(mod.required_status_contexts_from_legacy({"checks": [{"context": "Fast gate"}]}))
    assert not mod.has_fast_gate_context(["Dependency vulnerability scan"])

    jobs = []
    for i, seconds in enumerate([240, 300, 360, 420, 480], start=1):
        jobs.append((i, [{
            "id": i * 10,
            "name": "Fast gate",
            "status": "completed",
            "conclusion": "success",
            "started_at": "2026-09-01T10:00:00Z",
            "completed_at": f"2026-09-01T10:{seconds // 60:02d}:00Z",
        }]))
    rows = mod.fast_gate_durations(jobs)
    assert mod.evaluate_fast_gate_runtime(rows, 5, 600) == 360.0
    expect_fail(lambda: mod.evaluate_fast_gate_runtime(rows[:4], 5, 600), "at least 5")
    slow = [dict(x, durationSeconds=601) for x in rows]
    expect_fail(lambda: mod.evaluate_fast_gate_runtime(slow, 5, 600), "exceeds")

    validated = mod.validate_supply_chain_artifact(artifact_zip(), "a" * 40)
    assert validated["cycloneDxVersion"] == "1.6"
    assert validated["bomJsonComponents"] == 1
    assert validated["dependencyCount"] == 1
    assert validated["codeqlReleaseGateCandidateSha"] == "a" * 40
    expect_fail(lambda: mod.validate_supply_chain_artifact(artifact_zip(), "b" * 40), "does not match")
    expect_fail(lambda: mod.validate_supply_chain_artifact(b"not a zip"), "valid zip")

    win_blob, expected_msi, expected_exe, expected_portable = windows_artifact_zip()
    with tempfile.TemporaryDirectory() as candidate_td:
        candidate_dir = Path(candidate_td)
        win_info = mod.validate_windows_release_artifact(win_blob, "7.1.0", candidate_dir)
        assert (candidate_dir / "MyHomeLib-7.1.0.msi").is_file()
        assert (candidate_dir / "MyHomeLib-7.1.0.exe").is_file()
        assert (candidate_dir / "myhomelib-7.1.0-windows-amd64.zip").is_file()
        candidate_manifest = (candidate_dir / "candidate-windows.sha256").read_text()
        assert "MyHomeLib-7.1.0.msi" in candidate_manifest
        assert "MyHomeLib-7.1.0.exe" in candidate_manifest
    assert win_info["windowsMsiSha256"] == expected_msi
    assert win_info["windowsExeSha256"] == expected_exe
    assert win_info["windowsPortableSha256"] == expected_portable
    expect_fail(lambda: mod.validate_windows_release_artifact(b"not a zip", "7.1.0"), "valid zip")
    expect_fail(lambda: mod.normalize_sha("abc"), "40-character")
    assert mod.normalize_sha("A" * 40) == "a" * 40

    now = datetime(2026, 9, 6, tzinfo=timezone.utc)
    analyses = [{
        "id": 100,
        "ref": "refs/heads/main",
        "commit_sha": "a" * 40,
        "created_at": "2026-09-05T00:00:00Z",
        "rules_count": 120,
        "results_count": 0,
        "error": "",
        "tool": {"name": "CodeQL", "version": "2"},
    }]
    info = mod.evaluate_codeql_analyses(analyses, "main", 14, now)
    assert info["analysisId"] == 100
    expect_fail(lambda: mod.evaluate_codeql_analyses(analyses, "develop", 14, now), "no successful codeql")

    bound_info = mod.evaluate_codeql_analyses(analyses, "main", 14, now, "a" * 40)
    assert bound_info["commitSha"] == "a" * 40
    expect_fail(lambda: mod.evaluate_codeql_analyses(analyses, "main", 14, now, "b" * 40), "candidate")

    supply_blob = artifact_zip()
    windows_blob, _, _, _ = windows_artifact_zip()
    supply_digest = "sha256:" + hashlib.sha256(supply_blob).hexdigest()
    windows_digest = "sha256:" + hashlib.sha256(windows_blob).hexdigest()
    assert mod.verify_github_artifact_digest(supply_blob, supply_digest, "supply") == hashlib.sha256(supply_blob).hexdigest()
    expect_fail(lambda: mod.verify_github_artifact_digest(supply_blob, "sha256:" + "0" * 64, "supply"), "does not match")

    class FakeReleaseClient:
        repo = "owner/repo"
        def json(self, path):
            if path.endswith("/actions/runs/321"):
                return {
                    "id": 321, "status": "completed", "conclusion": "success",
                    "path": ".github/workflows/ci-release.yml", "head_sha": "a" * 40,
                    "run_number": 5, "run_attempt": 1,
                    "html_url": "https://github.com/owner/repo/actions/runs/321",
                    "created_at": "2026-09-06T09:00:00Z", "updated_at": "2026-09-06T09:20:00Z",
                }
            if "/actions/runs/321/artifacts" in path:
                return {"artifacts": [
                    {"id": 1, "name": "myhomelib-supply-chain", "expired": False, "size_in_bytes": len(supply_blob), "digest": supply_digest, "archive_download_url": "https://example.invalid/supply"},
                    {"id": 2, "name": "myhomelib-windows", "expired": False, "size_in_bytes": len(windows_blob), "digest": windows_digest, "archive_download_url": "https://example.invalid/windows"},
                ]}
            raise AssertionError(path)
        def download_artifact(self, url):
            return supply_blob if url.endswith("/supply") else windows_blob

    release_check = mod.check_supply_chain_run(FakeReleaseClient(), 321, "a" * 40, "7.1.0")
    assert release_check.details["headSha"] == "a" * 40
    assert release_check.details["windowsMsiSha256"]
    assert release_check.details["windowsExeSha256"]
    expect_fail(lambda: mod.check_supply_chain_run(FakeReleaseClient(), 321, "b" * 40, "7.1.0"), "does not match")

    alerts = [
        {"number": 1, "state": "open", "rule": {"id": "x", "security_severity_level": "medium"}},
        {"number": 2, "state": "open", "rule": {"id": "y", "security_severity_level": "high"}},
    ]
    blocked = mod.blocking_code_scanning_alerts(alerts)
    assert len(blocked) == 1 and blocked[0]["number"] == 2

    class FakeCodeqlClient:
        repo = "owner/repo"
        def __init__(self, alert_values=None, analysis_values=None):
            self.alert_values = alert_values or []
            self.analysis_values = analysis_values or analyses
        def paged(self, path, max_pages=10):
            if "/code-scanning/analyses" in path:
                return self.analysis_values
            if "/code-scanning/alerts" in path:
                return self.alert_values
            raise AssertionError(path)

    gate = mod.codeql_release_gate(FakeCodeqlClient(), "main", 14, "a" * 40)
    assert gate.status == "PASS" and gate.details["analysis"]["commitSha"] == "a" * 40
    expect_fail(lambda: mod.codeql_release_gate(FakeCodeqlClient(alert_values=[alerts[1]]), "main", 14, "a" * 40), "release blocked")
    expect_fail(lambda: mod.codeql_release_gate(FakeCodeqlClient(), "main", 14, "b" * 40), "candidate")

    print("GitHub connected acceptance regression tests: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
