#!/usr/bin/env python3
"""Fail-closed aggregator for live/platform/provider acceptance after V34 External Test R5.

This checker never creates acceptance evidence. It validates candidate-bound reports produced on
real Windows/device/provider/browser/corpus environments and emits one consolidated decision.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from evidence_contracts import validate_record

SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA64 = re.compile(r"^[0-9a-f]{64}$")
SESSION_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.IGNORECASE)
WINDOWS_SCENARIOS = {"windows-post-r5-functional", "windows-memory-soak"}
FINAL_EXTERNAL_EVIDENCE = {"github", "github-ingest", "harness-binding", "windows", "windows-archive", "candidate-binding"}
FINAL_EXTERNAL_BACKLOG = {"MHL-010", "MHL-011", "MHL-012", "MHL-017", "MHL-018", "MHL-019"}

SCENARIOS: dict[str, tuple[str, tuple[str, ...]]] = {
    "windows-post-r5-functional": (
        "windows-post-r5-functional/windows-post-r5-functional.json",
        tuple([f"WBD-{i:02d}" for i in range(1, 7)]
              + [f"CAL-{i:02d}" for i in range(1, 10)]
              + [f"WIN-{i:02d}" for i in range(1, 13)]),
    ),
    "physical-kobo": (
        "physical-kobo/kobo-acceptance.json",
        tuple(f"KOB-{i:02d}" for i in range(1, 10)),
    ),
    "real-readwise": (
        "real-readwise/readwise-acceptance.json",
        tuple(f"RW-{i:02d}" for i in range(1, 8)),
    ),
    "pwa-real-browser": (
        "pwa-real-browser/pwa-acceptance.json",
        tuple(f"PWA-{i:02d}" for i in range(1, 7)),
    ),
    "real-comic-corpus": (
        "real-comic-corpus/comic-corpus-acceptance.json",
        tuple(f"COMIC-{i:02d}" for i in range(1, 9)),
    ),
    "windows-memory-soak": (
        "windows-memory-soak/windows-memory-soak.json",
        tuple(f"MEM-{i:02d}" for i in range(1, 11)),
    ),
}


class AcceptanceError(RuntimeError):
    pass


@dataclass
class ScenarioDecision:
    scenario: str
    status: str
    report: str
    sha256: str
    checks: int


def need(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceError(message)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    need(path.is_file(), f"missing acceptance report: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception as exc:  # noqa: BLE001
        raise AcceptanceError(f"invalid JSON {path}: {exc}") from exc
    need(isinstance(data, dict), f"{path}: JSON root must be an object")
    return data


def safe_evidence(report: Path, raw: Any) -> Path:
    value = str(raw or "").strip().replace("\\", "/")
    need(value, f"{report}: PASS row has no evidence reference")
    candidate = Path(value)
    need(not candidate.is_absolute() and ".." not in candidate.parts,
         f"{report}: unsafe evidence reference {value!r}")
    target = (report.parent / candidate).resolve()
    try:
        target.relative_to(report.parent.resolve())
    except ValueError as exc:
        raise AcceptanceError(f"{report}: evidence escapes report directory: {value!r}") from exc
    need(target.is_file(), f"{report}: referenced evidence is missing: {value}")
    need(target.stat().st_size > 0, f"{report}: referenced evidence is empty: {value}")
    return target


def validate_windows_identity(path: Path, data: dict[str, Any], expected_sha: str | None = None) -> dict[str, str]:
    candidate_sha = str(data.get("candidateSha") or "").strip().lower()
    need(SHA40.fullmatch(candidate_sha) is not None, f"{path}: candidateSha must be a full Git SHA")
    if expected_sha is not None:
        need(candidate_sha == expected_sha, f"{path}: candidateSha mismatch ({candidate_sha} != {expected_sha})")
    session = str(data.get("acceptanceSessionId") or "").strip().lower()
    host_fp = str(data.get("hostFingerprintSha256") or "").strip().lower()
    user_fp = str(data.get("userFingerprintSha256") or "").strip().lower()
    need(SESSION_RE.fullmatch(session) is not None, f"{path}: acceptanceSessionId missing/invalid")
    need(SHA64.fullmatch(host_fp) is not None, f"{path}: hostFingerprintSha256 missing/invalid")
    need(SHA64.fullmatch(user_fp) is not None, f"{path}: userFingerprintSha256 missing/invalid")
    identity = {
        "candidateSha": candidate_sha,
        "acceptanceSessionId": session,
        "hostFingerprintSha256": host_fp,
        "userFingerprintSha256": user_fp,
        "osVersion": str(data.get("osVersion") or "").strip(),
        "osBuild": str(data.get("osBuild") or "").strip(),
        "osArchitecture": str(data.get("osArchitecture") or "").strip().lower(),
    }
    for key in ("osVersion", "osBuild", "osArchitecture"):
        need(identity[key], f"{path}: {key} missing")
    return identity


def windows_path_key(raw: Any) -> str:
    value = str(raw or "").strip()
    need(value, "Windows runtime executable path missing")
    return value.replace("\\", "/").rstrip("/").lower()


def validate_windows_cohesion(functional_path: Path, memory_path: Path, expected_sha: str | None) -> dict[str, str]:
    functional = load_json(functional_path)
    memory = load_json(memory_path)
    need(functional.get("scenario") == "windows-post-r5-functional", f"{functional_path}: unexpected scenario")
    need(memory.get("scenario") == "windows-memory-soak", f"{memory_path}: unexpected scenario")
    a = validate_windows_identity(functional_path, functional, expected_sha)
    b = validate_windows_identity(memory_path, memory, expected_sha)
    for key in ("acceptanceSessionId", "hostFingerprintSha256", "userFingerprintSha256", "osVersion", "osBuild", "osArchitecture"):
        need(a[key] == b[key], f"Windows functional/memory evidence mismatch for {key}")

    launcher_sha = str(functional.get("launcherSha256") or "").strip().lower()
    need(SHA64.fullmatch(launcher_sha) is not None, f"{functional_path}: launcherSha256 missing/invalid")
    launcher_path = windows_path_key(functional.get("launcher"))

    desktop_digest = str(functional.get("desktopAcceptanceSha256") or "").strip().lower()
    need(SHA64.fullmatch(desktop_digest) is not None, f"{functional_path}: desktopAcceptanceSha256 missing/invalid")
    desktop_path = safe_evidence(functional_path, functional.get("desktopAcceptanceEvidence"))
    need(sha256_file(desktop_path) == desktop_digest, f"{functional_path}: desktop acceptance evidence SHA-256 mismatch")
    desktop = load_json(desktop_path)
    need(int(desktop.get("schemaVersion", 0)) == 1, f"{desktop_path}: schemaVersion must be 1")
    need(desktop.get("scenario") == "windows-release-desktop-acceptance", f"{desktop_path}: unexpected scenario")
    need(desktop.get("overall") == "PASS", f"{desktop_path}: desktop acceptance is not PASS")
    desktop_identity = validate_windows_identity(desktop_path, desktop, expected_sha)
    for key in ("acceptanceSessionId", "hostFingerprintSha256", "userFingerprintSha256", "osVersion", "osBuild", "osArchitecture"):
        need(a[key] == desktop_identity[key], f"Windows desktop/functional evidence mismatch for {key}")
    desktop_launcher_sha = str(desktop.get("launcherSha256") or "").strip().lower()
    need(desktop_launcher_sha == launcher_sha, f"{desktop_path}: installed launcher SHA-256 does not match functional report")
    need(windows_path_key(desktop.get("launcher")) == launcher_path, f"{desktop_path}: installed launcher path does not match functional report")

    process_sha = str(memory.get("processSha256") or "").strip().lower()
    need(SHA64.fullmatch(process_sha) is not None, f"{memory_path}: processSha256 missing/invalid")
    need(process_sha == launcher_sha, f"{memory_path}: memory process SHA-256 does not match candidate-bound launcher")
    need(windows_path_key(memory.get("processPath")) == launcher_path, f"{memory_path}: memory process path does not match candidate-bound launcher")
    functional_digest = str(memory.get("functionalAcceptanceSha256") or "").strip().lower()
    need(SHA64.fullmatch(functional_digest) is not None, f"{memory_path}: functionalAcceptanceSha256 missing/invalid")
    need(functional_digest == sha256_file(functional_path), f"{memory_path}: functional acceptance SHA-256 does not match the actual report")
    return a


def validate_common(path: Path, scenario: str, ids: tuple[str, ...], expected_sha: str | None) -> tuple[str, ScenarioDecision]:
    data = load_json(path)
    need(int(data.get("schemaVersion", 0)) == 1, f"{path}: schemaVersion must be 1")
    need(data.get("scenario") == scenario, f"{path}: scenario must be {scenario!r}")
    need(data.get("overall") == "PASS", f"{path}: overall must be PASS")
    candidate_sha = str(data.get("candidateSha") or "").strip().lower()
    need(SHA40.fullmatch(candidate_sha) is not None, f"{path}: candidateSha must be a full Git SHA")
    if expected_sha is not None:
        need(candidate_sha == expected_sha, f"{path}: candidateSha mismatch ({candidate_sha} != {expected_sha})")
    need(str(data.get("timestamp") or "").strip(), f"{path}: timestamp missing")
    if scenario in WINDOWS_SCENARIOS:
        validate_windows_identity(path, data, expected_sha)

    rows = data.get("results")
    need(isinstance(rows, list), f"{path}: results must be a list")
    by_id: dict[str, dict[str, Any]] = {}
    evidence_hashes: set[str] = set()
    for row in rows:
        need(isinstance(row, dict), f"{path}: result row must be an object")
        cid = str(row.get("id") or row.get("Id") or "").strip()
        need(cid, f"{path}: result id missing")
        need(cid not in by_id, f"{path}: duplicate result id {cid}")
        by_id[cid] = row

    need(set(by_id) == set(ids),
         f"{path}: required check set mismatch; missing={sorted(set(ids)-set(by_id))}, extra={sorted(set(by_id)-set(ids))}")
    for cid in ids:
        row = by_id[cid]
        outcome = str(row.get("outcome") or row.get("Outcome") or "").strip().upper()
        need(outcome == "PASS", f"{path}: {cid} is not PASS ({outcome or 'blank'})")
        note = str(row.get("note") or row.get("Note") or "").strip()
        need(note, f"{path}: {cid} PASS requires a non-empty evidence note")
        evidence = row.get("evidence") if "evidence" in row else row.get("Evidence")
        ev = safe_evidence(path, evidence)
        digest = sha256_file(ev)
        expected_digest = str(row.get("evidenceSha256") or row.get("EvidenceSha256") or "").strip().lower()
        need(SHA64.fullmatch(expected_digest) is not None,
             f"{path}: {cid} PASS requires a valid evidenceSha256")
        need(digest == expected_digest,
             f"{path}: {cid} evidence SHA-256 mismatch ({digest} != {expected_digest})")
        need(digest not in evidence_hashes, f"{path}: duplicate evidence content reused for multiple PASS rows ({cid})")
        evidence_hashes.add(digest)

    return candidate_sha, ScenarioDecision(scenario, "PASS", str(path), sha256_file(path), len(ids))


def parse_evidence_timestamp(report: Path, label: str, raw: Any) -> datetime:
    value = str(raw or "").strip()
    need(value, f"{report}: {label} timestamp missing")
    try:
        stamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise AcceptanceError(f"{report}: {label} timestamp invalid: {value!r}") from exc
    need(stamp.tzinfo is not None, f"{report}: {label} timestamp must include timezone")
    return stamp


def validate_mem10_raw_evidence(path: Path, row: dict[str, Any], soak_seconds: int, sample_interval_seconds: int) -> None:
    evidence_value = row.get("evidence") if "evidence" in row else row.get("Evidence")
    evidence_path = safe_evidence(path, evidence_value)
    raw = load_json(evidence_path)
    need(str(raw.get("id") or "").strip() == "MEM-10", f"{evidence_path}: id must be MEM-10")
    need(str(raw.get("note") or "").strip(), f"{evidence_path}: note missing")
    started = parse_evidence_timestamp(evidence_path, "started", raw.get("started"))
    completed = parse_evidence_timestamp(evidence_path, "completed", raw.get("completed"))
    wall_seconds = (completed - started).total_seconds()
    need(wall_seconds >= soak_seconds,
         f"{evidence_path}: actual wall-clock soak {wall_seconds:.3f}s is shorter than declared {soak_seconds}s")

    samples = raw.get("samples")
    need(isinstance(samples, list), f"{evidence_path}: samples must be a list")
    metrics = row.get("metrics")
    need(isinstance(metrics, dict), f"{path}: MEM-10 metrics object missing")
    summary_count = metrics.get("sampleCount")
    need(isinstance(summary_count, int) and not isinstance(summary_count, bool),
         f"{path}: MEM-10 sampleCount invalid")
    need(len(samples) == summary_count,
         f"{evidence_path}: raw sample count {len(samples)} does not match summary {summary_count}")
    need(len(samples) >= 30, f"{evidence_path}: MEM-10 requires >=30 raw samples")

    sample_metric_keys = (
        "workingSetBytes", "heapUsedBytes", "heapCommittedBytes", "threadCount", "handleCount",
        "elapsedMs", "gcCollectionCount", "gcTotalTimeMs",
    )
    parsed: list[dict[str, Any]] = []
    previous_timestamp: datetime | None = None
    previous_elapsed: float | None = None
    previous_gc_count: float | None = None
    previous_gc_time: float | None = None
    for index, sample in enumerate(samples):
        need(isinstance(sample, dict), f"{evidence_path}: sample {index} must be an object")
        timestamp = parse_evidence_timestamp(evidence_path, f"sample {index}", sample.get("timestamp"))
        if previous_timestamp is not None:
            need(timestamp > previous_timestamp, f"{evidence_path}: sample timestamps must be strictly increasing")
        previous_timestamp = timestamp
        checked = {"timestamp": timestamp}
        for key in sample_metric_keys:
            value = sample.get(key)
            need(isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0,
                 f"{evidence_path}: sample {index} invalid metric {key}")
            checked[key] = value
        if previous_elapsed is not None:
            need(checked["elapsedMs"] >= previous_elapsed, f"{evidence_path}: elapsedMs must be monotonic")
            need(checked["gcCollectionCount"] >= previous_gc_count, f"{evidence_path}: gcCollectionCount must be monotonic")
            need(checked["gcTotalTimeMs"] >= previous_gc_time, f"{evidence_path}: gcTotalTimeMs must be monotonic")
        previous_elapsed = checked["elapsedMs"]
        previous_gc_count = checked["gcCollectionCount"]
        previous_gc_time = checked["gcTotalTimeMs"]
        parsed.append(checked)

    first = parsed[0]
    last = parsed[-1]
    need(first["timestamp"] >= started, f"{evidence_path}: first sample predates soak start")
    need(last["timestamp"] <= completed, f"{evidence_path}: last sample is after recorded completion")
    # With the producer's loop, the final sample is taken at/after the requested deadline.
    tolerance = max(1, sample_interval_seconds)
    sampled_span = (last["timestamp"] - first["timestamp"]).total_seconds()
    need(sampled_span >= soak_seconds - tolerance,
         f"{evidence_path}: sampled span {sampled_span:.3f}s is inconsistent with declared {soak_seconds}s soak")

    expected_summary = {
        "workingSetBytes": last["workingSetBytes"],
        "heapUsedBytes": last["heapUsedBytes"],
        "heapCommittedBytes": last["heapCommittedBytes"],
        "threadCount": last["threadCount"],
        "handleCount": last["handleCount"],
        "elapsedMs": last["elapsedMs"],
        "gcCollectionCount": last["gcCollectionCount"],
        "gcTotalTimeMs": last["gcTotalTimeMs"],
        "sampleCount": len(parsed),
        "workingSetPeakBytes": max(x["workingSetBytes"] for x in parsed),
        "heapUsedPeakBytes": max(x["heapUsedBytes"] for x in parsed),
        "heapCommittedPeakBytes": max(x["heapCommittedBytes"] for x in parsed),
        "workingSetDeltaBytes": last["workingSetBytes"] - first["workingSetBytes"],
        "heapUsedDeltaBytes": last["heapUsedBytes"] - first["heapUsedBytes"],
        "heapCommittedDeltaBytes": last["heapCommittedBytes"] - first["heapCommittedBytes"],
        "threadDelta": last["threadCount"] - first["threadCount"],
        "handleDelta": last["handleCount"] - first["handleCount"],
        "gcCollectionDelta": last["gcCollectionCount"] - first["gcCollectionCount"],
        "gcTotalTimeDeltaMs": last["gcTotalTimeMs"] - first["gcTotalTimeMs"],
    }
    for key, expected in expected_summary.items():
        need(metrics.get(key) == expected,
             f"{path}: MEM-10 summary {key}={metrics.get(key)!r} does not match raw evidence {expected!r}")


def validate_memory(path: Path, expected_sha: str | None) -> tuple[str, ScenarioDecision]:
    scenario = "windows-memory-soak"
    ids = SCENARIOS[scenario][1]
    candidate_sha, decision = validate_common(path, scenario, ids, expected_sha)
    data = load_json(path)
    soak = int(data.get("soakDurationSeconds", 0))
    need(soak >= 3600, f"{path}: soakDurationSeconds must be >= 3600")
    sample_interval = int(data.get("sampleIntervalSeconds", 0))
    need(sample_interval >= 5, f"{path}: sampleIntervalSeconds must be >= 5")
    rows = data["results"]
    required_metrics = (
        "workingSetBytes", "heapUsedBytes", "heapCommittedBytes", "threadCount", "handleCount",
        "elapsedMs", "gcCollectionCount", "gcTotalTimeMs",
    )
    mem10_nonnegative = (
        "workingSetPeakBytes", "heapUsedPeakBytes", "heapCommittedPeakBytes",
        "gcCollectionDelta", "gcTotalTimeDeltaMs",
    )
    mem10_signed = (
        "workingSetDeltaBytes", "heapUsedDeltaBytes", "heapCommittedDeltaBytes",
        "threadDelta", "handleDelta",
    )
    for row in rows:
        cid = str(row.get("id") or row.get("Id"))
        metrics = row.get("metrics")
        need(isinstance(metrics, dict), f"{path}: {cid} metrics object missing")
        for key in required_metrics:
            value = metrics.get(key)
            need(isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0,
                 f"{path}: {cid} invalid metric {key}")
        if cid == "MEM-10":
            samples = metrics.get("sampleCount")
            need(isinstance(samples, int) and not isinstance(samples, bool) and samples >= 30,
                 f"{path}: MEM-10 requires >=30 soak samples")
            for key in mem10_nonnegative:
                value = metrics.get(key)
                need(isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0,
                     f"{path}: MEM-10 invalid metric {key}")
            for key in mem10_signed:
                value = metrics.get(key)
                need(isinstance(value, (int, float)) and not isinstance(value, bool),
                     f"{path}: MEM-10 invalid metric {key}")
            validate_mem10_raw_evidence(path, row, soak, sample_interval)
    return candidate_sha, decision


def validate_external_final(path: Path, expected_sha: str) -> ScenarioDecision:
    data = load_json(path)
    try:
        validate_record(data, "myhomelib-7.1-final-external-acceptance", label=str(path))
    except ValueError as exc:
        raise AcceptanceError(str(exc)) from exc
    need(data.get("overall") == "PASS", f"{path}: exact-candidate external final is not PASS")
    need(data.get("failure") in (None, ""), f"{path}: external final contains a failure")
    backlog = data.get("backlogItems")
    need(isinstance(backlog, list) and set(map(str, backlog)) == FINAL_EXTERNAL_BACKLOG,
         f"{path}: external final backlog set is incomplete/unexpected")
    evidence = data.get("evidence")
    need(isinstance(evidence, list), f"{path}: external final evidence must be a list")
    by_name: dict[str, dict[str, Any]] = {}
    for row in evidence:
        need(isinstance(row, dict), f"{path}: external final evidence row must be an object")
        name = str(row.get("name") or "").strip()
        need(name and name not in by_name, f"{path}: duplicate/blank external final evidence name")
        need(row.get("status") == "PASS", f"{path}: external final evidence {name!r} is not PASS")
        details = row.get("details")
        need(isinstance(details, dict), f"{path}: external final evidence {name!r} details missing")
        by_name[name] = row
    need(set(by_name) == FINAL_EXTERNAL_EVIDENCE,
         f"{path}: external final evidence set mismatch; missing={sorted(FINAL_EXTERNAL_EVIDENCE-set(by_name))}, extra={sorted(set(by_name)-FINAL_EXTERNAL_EVIDENCE)}")
    for name, row in by_name.items():
        sha = str(row["details"].get("candidateSha") or "").strip().lower()
        need(sha == expected_sha, f"{path}: external final {name} candidate SHA mismatch")
    binding = by_name["candidate-binding"]["details"]
    need(str(binding.get("windowsIntegrityCandidateSha") or "").strip().lower() == expected_sha,
         f"{path}: Windows integrity candidate SHA mismatch")
    for key in (
        "windowsMsiSha256", "windowsExeSha256", "windowsPortableSha256", "windowsChecksumsSha256",
        "windowsIntegritySha256", "windowsIntegritySourceTreeSha256", "windowsIntegrityDistManifestSha256",
        "acceptanceHarnessManifestSha256", "hostFingerprintSha256", "userFingerprintSha256",
    ):
        value = str(binding.get(key) or "").strip().lower()
        need(SHA64.fullmatch(value) is not None, f"{path}: candidate-binding {key} missing/invalid")
    session = str(binding.get("acceptanceSessionId") or "").strip().lower()
    need(SESSION_RE.fullmatch(session) is not None, f"{path}: candidate-binding acceptanceSessionId missing/invalid")
    for key in ("releaseRunId", "acceptanceRunId"):
        value = binding.get(key)
        need(isinstance(value, int) and not isinstance(value, bool) and value > 0, f"{path}: candidate-binding {key} missing/invalid")
    for key in ("releaseRunUrl", "acceptanceRunUrl"):
        need(str(binding.get(key) or "").startswith("https://"), f"{path}: candidate-binding {key} missing/invalid")
    need(str(binding.get("windowsIntegrityProjectVersion") or "").strip(),
         f"{path}: candidate-binding windowsIntegrityProjectVersion missing")
    return ScenarioDecision("exact-candidate-ci-security-distribution", "PASS", str(path), sha256_file(path), len(by_name))


def write_outputs(out_dir: Path, candidate_sha: str, decisions: list[ScenarioDecision]) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    payload = {
        "schemaVersion": 1,
        "scenario": "post-r5-live-acceptance",
        "timestamp": stamp,
        "overall": "PASS",
        "candidateSha": candidate_sha,
        "decisions": [asdict(item) for item in decisions],
    }
    (out_dir / "post-r5-live-acceptance.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# MyHomeLib — post-R5 live acceptance", "",
        f"- Timestamp: `{stamp}`", f"- Candidate SHA: `{candidate_sha}`", "- Overall: **PASS**", "",
        "| Scenario | Status | Checks | Report SHA-256 |", "|---|---:|---:|---|",
    ]
    for item in decisions:
        lines.append(f"| {item.scenario} | {item.status} | {item.checks} | `{item.sha256}` |")
    (out_dir / "post-r5-live-acceptance.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True, help="Root containing post-R5 live evidence directories")
    parser.add_argument("--candidate-sha", default="")
    parser.add_argument("--external-final", type=Path)
    parser.add_argument("--out-dir", type=Path, default=Path("target/post-r5-live-acceptance"))
    args = parser.parse_args(argv or sys.argv[1:])
    try:
        expected = args.candidate_sha.strip().lower() or None
        if expected is not None:
            need(SHA40.fullmatch(expected) is not None, "--candidate-sha must be a full 40-character Git SHA")
        decisions: list[ScenarioDecision] = []
        observed: str | None = expected
        for scenario, (rel, ids) in SCENARIOS.items():
            path = args.root / rel
            if scenario == "windows-memory-soak":
                sha, decision = validate_memory(path, observed)
            else:
                sha, decision = validate_common(path, scenario, ids, observed)
            if observed is None:
                observed = sha
            decisions.append(decision)
        assert observed is not None
        validate_windows_cohesion(
            args.root / SCENARIOS["windows-post-r5-functional"][0],
            args.root / SCENARIOS["windows-memory-soak"][0],
            observed,
        )
        if args.external_final is not None:
            decisions.append(validate_external_final(args.external_final, observed))
        write_outputs(args.out_dir, observed, decisions)
        print("Post-R5 live acceptance: PASS")
        print(f"- candidate SHA: {observed}")
        print(f"- scenarios: {len(decisions)}")
        return 0
    except (AcceptanceError, OSError, ValueError) as exc:
        print(f"Post-R5 live acceptance: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
