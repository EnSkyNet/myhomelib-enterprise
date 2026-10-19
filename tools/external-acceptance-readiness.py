#!/usr/bin/env python3
"""Offline readiness gate for the six remaining external acceptance items.

This tool never closes MHL-010/011/012/017/018/019.  It only verifies that the
repository contains a coherent, candidate-bound GitHub/Windows evidence harness
and optionally executes its offline regression tests.  Real GitHub Actions and
real Windows evidence remain mandatory for PASS.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

from evidence_contracts import current_schema

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
EXTERNAL_GATES = ("MHL-010", "MHL-011", "MHL-012", "MHL-017", "MHL-018", "MHL-019")

REQUIRED_FILES = (
    ".github/workflows/ci-pr.yml",
    ".github/workflows/ci-release.yml",
    ".github/workflows/codeql.yml",
    ".github/workflows/github-acceptance.yml",
    "tools/github-connected-acceptance.py",
    "tools/evidence_contracts.py",
    "tools/release-candidate-integrity.py",
    "tools/github-acceptance-artifact-ingest.py",
    "tools/windows-acceptance-harness-binding.py",
    "tools/windows-acceptance-host.ps1",
    "tools/v71-windows-acceptance-start.ps1",
    "tools/windows-bound-packaging-acceptance.ps1",
    "tools/windows-installer-acceptance.ps1",
    "tools/windows-release-desktop-acceptance.ps1",
    "tools/windows-post-r5-functional-acceptance.ps1",
    "tools/windows-target-memory-soak.ps1",
    "tools/windows-ui-acceptance.ps1",
    "tools/windows-acceptance-evidence-check.py",
    "tools/windows-final-evidence-pack.ps1",
    "tools/v71-finalize-external-acceptance.ps1",
    "tools/v71-final-external-acceptance-check.py",
    "tools/v71-final-evidence-bundle-check.py",
    "docs/release/CURRENT-VALIDATION.md",
    "docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md",
)

REGRESSION_SCRIPTS = (
    "tools/evidence-contracts-test.py",
    "tools/release-candidate-integrity-test.py",
    "tools/github-connected-acceptance-test.py",
    "tools/github-acceptance-artifact-ingest-test.py",
    "tools/windows-acceptance-evidence-check-test.py",
    "tools/windows-acceptance-harness-binding-test.py",
    "tools/post-r5-live-acceptance-check-test.py",
    "tools/zip-evidence-safety-test.py",
    "tools/v71-final-external-acceptance-check-test.py",
    "tools/v71-final-evidence-bundle-check-test.py",
)


class ReadinessError(RuntimeError):
    pass


@dataclass(frozen=True)
class Check:
    id: str
    status: str
    summary: str
    details: dict[str, object]


def need(condition: bool, message: str) -> None:
    if not condition:
        raise ReadinessError(message)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read(root: Path, rel: str) -> str:
    path = root / rel
    need(path.is_file(), f"missing required file: {rel}")
    return path.read_text(encoding="utf-8-sig")


def require_tokens(root: Path, rel: str, tokens: Iterable[str]) -> Check:
    text = read(root, rel)
    missing = [token for token in tokens if token not in text]
    need(not missing, f"{rel}: missing required token(s): {', '.join(missing)}")
    return Check(rel, "PASS", "required acceptance structure present", {"requiredTokens": list(tokens)})


def _metric_from_validation(text: str, label: str) -> int:
    match = re.search(rf"\*\*(\d+)\s+{re.escape(label)}\*\*", text, re.IGNORECASE)
    need(match is not None, f"current evidence document: cannot find bold {label} metric")
    return int(match.group(1))


def validate_current_validation(root: Path) -> Check:
    """Require an explicit *unverified* current candidate and an honest historical baseline.

    Historical R18 test counts can be cross-checked against their original report,
    but must never be presented as exact-current validation of the active branch.
    Passing this structural check only means the external evidence harness can run;
    it is not a Maven/Windows/release acceptance verdict.
    """
    rel = "docs/release/CURRENT-VALIDATION.md"
    text = read(root, rel)
    header = text.split("## Implemented scope", 1)[0]

    candidate = re.search(r"\*\*Current source candidate:\*\*.*?V34 External Test R(\d+)\b", header)
    need(candidate is not None, f"{rel}: current V34 External Test R<revision> candidate missing from header")
    revision = int(candidate.group(1))

    date = re.search(r"\*\*Date:\*\*\s*(\d{4}-\d{2}-\d{2})", header)
    need(date is not None, f"{rel}: current validation date missing from header")

    status = re.search(r"\*\*Status:\*\*\s*\*\*(.+?)\*\*", header, re.IGNORECASE)
    need(status is not None, f"{rel}: current status line missing from header")
    status_text = status.group(1)
    need("CURRENT CANDIDATE NOT VERIFIED" in status_text.upper(),
         f"{rel}: current candidate must remain explicitly unverified without exact-current root evidence")
    status_upper = status_text.upper()
    explicit_external_status = (
        "REMAIN EXTERNAL" in status_upper
        or "REMAINS EXTERNAL" in status_upper
        or "LIVE ACCEPTANCE REMAINS REQUIRED" in status_upper
        or "LIVE ACCEPTANCE REMAIN REQUIRED" in status_upper
    )
    need(explicit_external_status, f"{rel}: current status must explicitly keep live/platform acceptance external or required")

    historical_aggregate = re.search(
        r"Historical R18 Maven baseline aggregate:\s*"
        r"\*\*(\d+) tests / (\d+) failures / (\d+) errors / (\d+) skipped\*\*",
        header, re.IGNORECASE,
    )
    need(historical_aggregate is not None, f"{rel}: historical R18 Maven aggregate missing")
    historical_counts = tuple(int(historical_aggregate.group(i)) for i in range(1, 5))

    detailed = re.search(r"Detailed historical R18 evidence:\s*`([^`]+)`", header)
    need(detailed is not None, f"{rel}: detailed historical R18 evidence path missing")
    detailed_rel = detailed.group(1)
    expected_fragment = f"POST-R{revision - 1}-R{revision}-VALIDATION-"
    need(expected_fragment in Path(detailed_rel).name,
         f"{rel}: detailed evidence {detailed_rel!r} does not match current candidate R{revision}")
    detailed_text = read(root, detailed_rel)
    detailed_counts = (
        _metric_from_validation(detailed_text, "tests"),
        _metric_from_validation(detailed_text, "failures"),
        _metric_from_validation(detailed_text, "errors"),
        _metric_from_validation(detailed_text, "skipped"),
    )
    need(historical_counts == detailed_counts,
         f"{rel}: historical Maven aggregate {historical_counts} does not match {detailed_rel} {detailed_counts}")

    return Check(
        rel, "PASS", "current candidate explicitly unverified; historical R18 evidence synchronized",
        {
            "candidateRevision": revision,
            "date": date.group(1),
            "evidenceStatus": "HISTORICAL_ONLY",
            "historicalEvidence": detailed_rel,
            "historicalMavenAggregate": {
                "tests": historical_counts[0], "failures": historical_counts[1],
                "errors": historical_counts[2], "skipped": historical_counts[3],
            },
        },
    )


def project_version(root: Path) -> str:
    pom = root / "pom.xml"
    need(pom.is_file(), "pom.xml missing")
    version = ET.parse(pom).getroot().findtext("m:version", namespaces=NS)
    need(bool(version and version.strip()), "project version missing from pom.xml")
    return version.strip()


def harness_manifest_fingerprint(root: Path) -> tuple[str, int]:
    script = root / "tools/windows-acceptance-harness-binding.py"
    spec = importlib.util.spec_from_file_location("mhl_acceptance_binding_readiness", script)
    need(spec is not None and spec.loader is not None, "cannot load windows acceptance harness binding module")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    entries = module.build_manifest(root)
    text = module.manifest_text(entries).encode("utf-8")
    return sha256_bytes(text), len(entries)


def structural_checks(root: Path) -> list[Check]:
    missing = [rel for rel in REQUIRED_FILES if not (root / rel).is_file()]
    need(not missing, "missing external acceptance handoff file(s): " + ", ".join(missing))

    # Keep the human runbook command aligned with the real PowerShell entrypoint.
    # The script parameter is -Repo; a stale -Repository instruction blocks the Windows handoff.
    for rel in ("docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md", "docs/release/EXTERNAL-TEST-PLAN.md"):
        command_doc = read(root, rel)
        need('-Repo "OWNER/REPO"' in command_doc, f"{rel}: Windows acceptance command must use -Repo")
        need('-Repository "OWNER/REPO"' not in command_doc, f"{rel}: stale -Repository parameter must not be documented")

    checks = [
        require_tokens(root, ".github/workflows/ci-pr.yml", (
            "pull_request:", "name: Fast gate", "Dependency vulnerability scan",
            "Windows DPAPI secret-store gate", "External acceptance readiness",
        )),
        require_tokens(root, ".github/workflows/ci-release.yml", (
            "Supply-chain release gate", "-Psbom,dependency-check", "myhomelib-supply-chain",
            "name: myhomelib-${{ matrix.platform }}", "windows-installer-acceptance.ps1",
            "release-candidate-integrity.py", '--candidate-sha "$GITHUB_SHA"',
        )),
        require_tokens(root, "tools/evidence_contracts.py", (
            "CONTRACTS", "accepted_versions", "unsupported schemaVersion",
            "github-connected-acceptance", "myhomelib-7.1-final-external-acceptance",
        )),
        require_tokens(root, "tools/release-candidate-integrity.py", (
            "release-candidate-integrity", "sourceTreeSha256", "distManifestSha256",
            "dist file set does not exactly match SHA256SUMS",
        )),
        require_tokens(root, "tools/github-connected-acceptance.py", (
            "release-candidate-integrity-windows.json", "windowsIntegrityCandidateSha",
            "does not match CI Release head SHA",
        )),
        require_tokens(root, "tools/github-acceptance-artifact-ingest.py", (
            "release-windows-SHA256SUMS", "release-candidate-integrity-windows.json",
            "windowsIntegritySha256", "candidate integrity hash does not match GitHub evidence",
        )),
        require_tokens(root, "tools/v71-finalize-external-acceptance.ps1", (
            "release-windows-SHA256SUMS", "release-candidate-integrity-windows.json",
            "Verify completed reviewer evidence bundle",
        )),
        require_tokens(root, "tools/v71-final-evidence-bundle-check.py", (
            "release-windows-SHA256SUMS", "release-candidate-integrity-windows.json",
            "candidate integrity record SHA does not match GitHub evidence",
        )),
        require_tokens(root, "tools/v71-final-evidence-bundle-check.py", (
            "render_github_markdown", "GitHub connected acceptance Markdown",
            "human-readable Markdown does not exactly match machine-readable JSON evidence",
        )),
        require_tokens(root, "tools/v71-final-evidence-bundle-check.py", (
            "render_final_markdown", "Final external acceptance Markdown",
            "human-readable Markdown does not exactly match machine-readable JSON evidence",
        )),
        require_tokens(root, "tools/windows-release-desktop-acceptance.ps1", (
            "launcherSha256", "Installed launcher SHA-256",
            "Desktop acceptance cannot PASS without a SHA-256-bound installed launcher",
        )),
        require_tokens(root, "tools/windows-post-r5-functional-acceptance.ps1", (
            "DesktopAcceptancePath", "launcherSha256", "desktopAcceptanceEvidence",
            "desktopAcceptanceSha256", "Installed launcher SHA-256 does not match",
        )),
        require_tokens(root, "tools/windows-target-memory-soak.ps1", (
            "FunctionalAcceptancePath", "processPath", "processSha256",
            "functionalAcceptanceSha256", "Process executable SHA-256 does not match",
        )),
        require_tokens(root, "tools/post-r5-live-acceptance-check.py", (
            "evidenceSha256", "validate_mem10_raw_evidence", "actual wall-clock soak",
            "raw sample count", "workingSetDeltaBytes", "desktopAcceptanceSha256",
            "processSha256", "functionalAcceptanceSha256",
        )),
        require_tokens(root, ".github/workflows/codeql.yml", (
            "github/codeql-action/init@", "github/codeql-action/analyze@", "pull_request:", "schedule:",
        )),
        require_tokens(root, ".github/workflows/github-acceptance.yml", (
            "workflow_dispatch:", "--expected-sha", "github-connected-acceptance.py",
            "MHL-010 / 017 / 018 / 019 connected acceptance",
        )),
        validate_current_validation(root),
        require_tokens(root, "docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md", (
            "MHL-010", "MHL-011", "MHL-012", "MHL-017", "MHL-018", "MHL-019",
            "GitHub connected acceptance", "v71-windows-acceptance-start.ps1",
            "v71-finalize-external-acceptance.ps1", "Do not mark",
        )),
    ]

    version = project_version(root)
    checks.append(Check("release-identity", "PASS", "release identity read from pom.xml", {"version": version}))
    manifest_sha, count = harness_manifest_fingerprint(root)
    checks.append(Check(
        "windows-harness-fingerprint", "PASS", "candidate-bindable Windows harness is internally complete",
        {"manifestSha256": manifest_sha, "criticalFileCount": count},
    ))
    return checks


def run_regressions(root: Path, timeout_seconds: int = 90) -> list[Check]:
    results: list[Check] = []
    for rel in REGRESSION_SCRIPTS:
        path = root / rel
        need(path.is_file(), f"missing acceptance regression: {rel}")
        try:
            proc = subprocess.run(
                [sys.executable, rel], cwd=root, text=True, capture_output=True,
                timeout=timeout_seconds, check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise ReadinessError(f"acceptance regression timed out: {rel}") from exc
        need(proc.returncode == 0, f"acceptance regression failed: {rel}\n{proc.stdout}\n{proc.stderr}")
        results.append(Check(
            rel, "PASS", "offline evidence-policy regression passed",
            {"stdoutTail": "\n".join(proc.stdout.strip().splitlines()[-4:])},
        ))
    return results


def build_payload(root: Path, checks: list[Check], regressions_run: bool) -> dict[str, object]:
    version = project_version(root)
    return {
        "schemaVersion": current_schema("external-acceptance-readiness"),
        "scenario": "external-acceptance-readiness",
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "overall": "READY_FOR_LIVE_EVIDENCE",
        "releaseVersion": version,
        "externalGates": [{"id": gate, "status": "OPEN_EXTERNAL"} for gate in EXTERNAL_GATES],
        "regressionsRun": regressions_run,
        "checks": [asdict(c) for c in checks],
        "closureRule": "Real GitHub/Windows evidence is mandatory; this readiness result cannot close any external gate.",
    }


def write_outputs(payload: dict[str, object], out_json: Path | None, out_md: Path | None) -> None:
    if out_json:
        out_json.parent.mkdir(parents=True, exist_ok=True)
        out_json.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if out_md:
        out_md.parent.mkdir(parents=True, exist_ok=True)
        gate_rows = payload["externalGates"]
        lines = [
            "# External acceptance readiness",
            "",
            f"- Overall: **{payload['overall']}**",
            f"- Release identity: `{payload['releaseVersion']}`",
            f"- Offline regressions executed: `{payload['regressionsRun']}`",
            "- Closure rule: real GitHub/Windows evidence remains mandatory.",
            "",
            "| Gate | Status |",
            "|---|---|",
        ]
        for row in gate_rows:  # type: ignore[assignment]
            lines.append(f"| {row['id']} | {row['status']} |")
        lines += ["", "This document is a readiness record, not external acceptance evidence.", ""]
        out_md.write_text("\n".join(lines), encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--run-regressions", action="store_true")
    parser.add_argument("--out-json", type=Path)
    parser.add_argument("--out-md", type=Path)
    args = parser.parse_args(argv or sys.argv[1:])
    try:
        root = args.root.resolve()
        checks = structural_checks(root)
        if args.run_regressions:
            checks.extend(run_regressions(root))
        payload = build_payload(root, checks, args.run_regressions)
        write_outputs(payload, args.out_json, args.out_md)
        print("External acceptance readiness: READY_FOR_LIVE_EVIDENCE")
        print(f"- release identity: {payload['releaseVersion']}")
        print(f"- external gates still OPEN: {', '.join(EXTERNAL_GATES)}")
        print(f"- offline regressions run: {args.run_regressions}")
        return 0
    except (ReadinessError, OSError, ET.ParseError) as exc:
        print(f"External acceptance readiness: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
