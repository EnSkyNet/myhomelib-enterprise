#!/usr/bin/env python3
"""Offline regressions for post-r5-live-acceptance-check.py."""
from __future__ import annotations

import importlib.util
import hashlib
import json
import tempfile
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("post_r5_live", HERE / "post-r5-live-acceptance-check.py")
assert SPEC and SPEC.loader
mod = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mod
SPEC.loader.exec_module(mod)

SHA = "a" * 40
SESSION = "11111111-2222-4333-8444-555555555555"
HOST_FP = "1" * 64
USER_FP = "2" * 64
LAUNCHER_PATH = r"C:\Users\Test\AppData\Local\MyHomeLib\MyHomeLib.exe"
LAUNCHER_SHA = "d" * 64


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_mem10_raw(path: Path, *, duration_seconds: int = 3600, sample_count: int = 60) -> None:
    start = datetime(2026, 10, 4, 6, 0, 0, tzinfo=timezone.utc)
    samples = []
    for i in range(sample_count):
        ratio = i / (sample_count - 1) if sample_count > 1 else 1.0
        when = start + timedelta(seconds=duration_seconds * ratio)
        working = 202_000_000 - int(2_000_000 * ratio)
        heap = 99_000_000 + int(1_000_000 * ratio)
        committed = 300_000_000
        threads = 50
        handles = 130 if i == 0 else 129
        gc_count = 20 + int(42 * ratio)
        gc_time = 100 + int(310 * ratio)
        if i == 1:
            working = 240_000_000
            heap = 150_000_000
            committed = 320_000_000
        samples.append({
            "timestamp": when.isoformat().replace("+00:00", "Z"),
            "workingSetBytes": working,
            "heapUsedBytes": heap,
            "heapCommittedBytes": committed,
            "threadCount": threads,
            "handleCount": handles,
            "elapsedMs": 10_000 + int(duration_seconds * 1000 * ratio),
            "gcCollectionCount": gc_count,
            "gcTotalTimeMs": gc_time,
        })
    payload = {
        "id": "MEM-10",
        "note": "representative one-hour soak",
        "started": start.isoformat().replace("+00:00", "Z"),
        "completed": (start + timedelta(seconds=duration_seconds)).isoformat().replace("+00:00", "Z"),
        "samples": samples,
    }
    write_json(path, payload)


def build_report(root: Path, scenario: str, rel: str, ids: tuple[str, ...]) -> Path:
    report = root / rel
    report.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    for index, cid in enumerate(ids, 1):
        evidence = report.parent / f"evidence-{cid}.txt"
        evidence.write_text(f"{scenario} {cid} unique evidence {index}\n", encoding="utf-8")
        row = {
            "id": cid,
            "check": f"check {cid}",
            "outcome": "PASS",
            "note": f"verified {cid}",
            "evidence": evidence.name,
            "evidenceSha256": sha256_file(evidence),
        }
        if scenario == "windows-memory-soak":
            row["metrics"] = {
                "workingSetBytes": 200_000_000 + index,
                "heapUsedBytes": 100_000_000 + index,
                "heapCommittedBytes": 300_000_000 + index,
                "threadCount": 40 + index,
                "handleCount": 120 + index,
                "elapsedMs": 1000 * index,
                "gcCollectionCount": 10 + index,
                "gcTotalTimeMs": 50 + index,
            }
            if cid == "MEM-10":
                row["metrics"].update({
                    "sampleCount": 60,
                    "workingSetPeakBytes": 240_000_000,
                    "heapUsedPeakBytes": 150_000_000,
                    "heapCommittedPeakBytes": 320_000_000,
                    "workingSetDeltaBytes": -2_000_000,
                    "heapUsedDeltaBytes": 1_000_000,
                    "heapCommittedDeltaBytes": 0,
                    "threadDelta": 0,
                    "handleDelta": -1,
                    "gcCollectionDelta": 42,
                    "gcTotalTimeDeltaMs": 310,
                })
                row["metrics"].update({
                    "workingSetBytes": 200_000_000,
                    "heapUsedBytes": 100_000_000,
                    "heapCommittedBytes": 300_000_000,
                    "threadCount": 50,
                    "handleCount": 129,
                    "elapsedMs": 3_610_000,
                    "gcCollectionCount": 62,
                    "gcTotalTimeMs": 410,
                })
                evidence = report.parent / f"evidence-{cid}.json"
                write_mem10_raw(evidence)
                row["evidence"] = evidence.name
                row["evidenceSha256"] = sha256_file(evidence)
        rows.append(row)
    payload = {
        "schemaVersion": 1,
        "scenario": scenario,
        "timestamp": "2026-10-04T06:00:00Z",
        "overall": "PASS",
        "candidateSha": SHA,
        "results": rows,
    }
    if scenario in {"windows-post-r5-functional", "windows-memory-soak"}:
        payload.update({
            "acceptanceSessionId": SESSION,
            "hostFingerprintSha256": HOST_FP,
            "userFingerprintSha256": USER_FP,
            "osVersion": "10.0.26100",
            "osBuild": "26100",
            "osArchitecture": "x64",
        })
    if scenario == "windows-post-r5-functional":
        desktop = report.parent / "evidence" / "runtime-desktop-acceptance.json"
        desktop_payload = {
            "schemaVersion": 1,
            "scenario": "windows-release-desktop-acceptance",
            "timestamp": "2026-10-04T05:50:00Z",
            "overall": "PASS",
            "candidateSha": SHA,
            "acceptanceSessionId": SESSION,
            "hostFingerprintSha256": HOST_FP,
            "userFingerprintSha256": USER_FP,
            "osVersion": "10.0.26100",
            "osBuild": "26100",
            "osArchitecture": "x64",
            "launcher": LAUNCHER_PATH,
            "launcherSha256": LAUNCHER_SHA,
        }
        write_json(desktop, desktop_payload)
        payload.update({
            "launcher": LAUNCHER_PATH,
            "launcherSha256": LAUNCHER_SHA,
            "desktopAcceptanceEvidence": desktop.relative_to(report.parent).as_posix(),
            "desktopAcceptanceSha256": sha256_file(desktop),
        })
    if scenario == "windows-memory-soak":
        functional = root / mod.SCENARIOS["windows-post-r5-functional"][0]
        payload.update({
            "processPath": LAUNCHER_PATH,
            "processSha256": LAUNCHER_SHA,
            "functionalAcceptanceSha256": sha256_file(functional),
            "soakDurationSeconds": 3600,
            "sampleIntervalSeconds": 60,
        })
    write_json(report, payload)
    return report


def build_external_final(path: Path, candidate_sha: str = SHA) -> Path:
    evidence = [
        {"name": "github", "status": "PASS", "details": {"candidateSha": candidate_sha}},
        {"name": "github-ingest", "status": "PASS", "details": {"candidateSha": candidate_sha}},
        {"name": "harness-binding", "status": "PASS", "details": {"candidateSha": candidate_sha}},
        {"name": "windows", "status": "PASS", "details": {"candidateSha": candidate_sha}},
        {"name": "windows-archive", "status": "PASS", "details": {"candidateSha": candidate_sha}},
        {"name": "candidate-binding", "status": "PASS", "details": {
            "candidateSha": candidate_sha, "windowsMsiSha256": "3" * 64, "windowsExeSha256": "4" * 64,
            "windowsPortableSha256": "5" * 64, "windowsChecksumsSha256": "6" * 64,
            "windowsIntegrityProjectVersion": "7.1.0", "windowsIntegrityCandidateSha": candidate_sha,
            "windowsIntegritySha256": "7" * 64, "windowsIntegritySourceTreeSha256": "8" * 64,
            "windowsIntegrityDistManifestSha256": "9" * 64, "releaseRunId": 123,
            "releaseRunUrl": "https://github.com/owner/repo/actions/runs/123", "acceptanceRunId": 456,
            "acceptanceRunUrl": "https://github.com/owner/repo/actions/runs/456",
            "acceptanceHarnessManifestSha256": "b" * 64, "acceptanceSessionId": SESSION,
            "hostFingerprintSha256": HOST_FP, "userFingerprintSha256": USER_FP,
        }},
    ]
    payload = {"schemaVersion": 1, "scenario": "myhomelib-7.1-final-external-acceptance",
        "timestamp": "2026-10-04T06:00:00Z", "overall": "PASS",
        "backlogItems": ["MHL-010", "MHL-011", "MHL-012", "MHL-017", "MHL-018", "MHL-019"],
        "evidence": evidence, "failure": None}
    write_json(path, payload)
    return path


def build_bundle(root: Path) -> None:
    for scenario, (rel, ids) in mod.SCENARIOS.items():
        build_report(root, scenario, rel, ids)


def expect_failure(label: str, action) -> None:
    try:
        action()
    except mod.AcceptanceError:
        return
    raise AssertionError(f"negative regression unexpectedly passed: {label}")


def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        decisions = []
        expected = SHA
        for scenario, (rel, ids) in mod.SCENARIOS.items():
            if scenario == "windows-memory-soak":
                sha, decision = mod.validate_memory(root / rel, expected)
            else:
                sha, decision = mod.validate_common(root / rel, scenario, ids, expected)
            assert sha == SHA
            decisions.append(decision)
        out = root / "summary"
        mod.write_outputs(out, SHA, decisions)
        assert json.loads((out / "post-r5-live-acceptance.json").read_text(encoding="utf-8"))["overall"] == "PASS"
        mod.validate_windows_cohesion(
            root / mod.SCENARIOS["windows-post-r5-functional"][0],
            root / mod.SCENARIOS["windows-memory-soak"][0], SHA)
        external = build_external_final(root / "v71-final-external-acceptance.json")
        assert mod.validate_external_final(external, SHA).status == "PASS"

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        scenario = "real-readwise"
        rel, ids = mod.SCENARIOS[scenario]
        path = root / rel
        data = json.loads(path.read_text(encoding="utf-8"))
        data["results"] = data["results"][:-1]
        write_json(path, data)
        expect_failure("missing required row", lambda: mod.validate_common(path, scenario, ids, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        scenario = "physical-kobo"
        rel, ids = mod.SCENARIOS[scenario]
        path = root / rel
        data = json.loads(path.read_text(encoding="utf-8"))
        data["candidateSha"] = "b" * 40
        write_json(path, data)
        expect_failure("candidate mismatch", lambda: mod.validate_common(path, scenario, ids, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        rel, _ = mod.SCENARIOS["windows-memory-soak"]
        path = root / rel
        data = json.loads(path.read_text(encoding="utf-8"))
        data["soakDurationSeconds"] = 3599
        write_json(path, data)
        expect_failure("short soak", lambda: mod.validate_memory(path, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        rel, _ = mod.SCENARIOS["windows-memory-soak"]
        path = root / rel
        data = json.loads(path.read_text(encoding="utf-8"))
        del data["results"][0]["metrics"]["gcTotalTimeMs"]
        write_json(path, data)
        expect_failure("missing GC evidence", lambda: mod.validate_memory(path, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        rel, _ = mod.SCENARIOS["windows-memory-soak"]
        path = root / rel
        data = json.loads(path.read_text(encoding="utf-8"))
        mem10 = next(row for row in data["results"] if row["id"] == "MEM-10")
        del mem10["metrics"]["workingSetPeakBytes"]
        write_json(path, data)
        expect_failure("missing MEM-10 drift/peak evidence", lambda: mod.validate_memory(path, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        rel, _ = mod.SCENARIOS["windows-memory-soak"]
        path = root / rel
        data = json.loads(path.read_text(encoding="utf-8"))
        mem10 = next(row for row in data["results"] if row["id"] == "MEM-10")
        raw = path.parent / mem10["evidence"]
        write_mem10_raw(raw, duration_seconds=120)
        expect_failure("MEM-10 raw evidence shorter than declared soak", lambda: mod.validate_memory(path, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        rel, _ = mod.SCENARIOS["windows-memory-soak"]
        path = root / rel
        data = json.loads(path.read_text(encoding="utf-8"))
        mem10 = next(row for row in data["results"] if row["id"] == "MEM-10")
        mem10["metrics"]["workingSetDeltaBytes"] = 123
        write_json(path, data)
        expect_failure("MEM-10 summary does not match raw samples", lambda: mod.validate_memory(path, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        scenario = "real-readwise"
        rel, ids = mod.SCENARIOS[scenario]
        path = root / rel
        data = json.loads(path.read_text(encoding="utf-8"))
        evidence = path.parent / data["results"][0]["evidence"]
        evidence.write_text("evidence replaced after report creation\n", encoding="utf-8")
        expect_failure("PASS evidence changed after report creation", lambda: mod.validate_common(path, scenario, ids, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        scenario = "pwa-real-browser"
        rel, ids = mod.SCENARIOS[scenario]
        path = root / rel
        outside = root / "outside.txt"
        outside.write_text("outside", encoding="utf-8")
        data = json.loads(path.read_text(encoding="utf-8"))
        data["results"][0]["evidence"] = "../outside.txt"
        write_json(path, data)
        expect_failure("path traversal", lambda: mod.validate_common(path, scenario, ids, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        scenario = "real-comic-corpus"
        rel, ids = mod.SCENARIOS[scenario]
        path = root / rel
        data = json.loads(path.read_text(encoding="utf-8"))
        data["results"][1]["evidence"] = data["results"][0]["evidence"]
        write_json(path, data)
        expect_failure("reused evidence content", lambda: mod.validate_common(path, scenario, ids, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td); build_bundle(root)
        functional = root / mod.SCENARIOS["windows-post-r5-functional"][0]
        memory = root / mod.SCENARIOS["windows-memory-soak"][0]
        data = json.loads(memory.read_text(encoding="utf-8")); data["acceptanceSessionId"] = "99999999-2222-4333-8444-555555555555"; write_json(memory, data)
        expect_failure("mixed Windows acceptance sessions", lambda: mod.validate_windows_cohesion(functional, memory, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td); build_bundle(root)
        functional = root / mod.SCENARIOS["windows-post-r5-functional"][0]
        memory = root / mod.SCENARIOS["windows-memory-soak"][0]
        data = json.loads(memory.read_text(encoding="utf-8")); data["processSha256"] = "e" * 64; write_json(memory, data)
        expect_failure("memory process hash differs from candidate-bound launcher", lambda: mod.validate_windows_cohesion(functional, memory, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td); build_bundle(root)
        functional = root / mod.SCENARIOS["windows-post-r5-functional"][0]
        memory = root / mod.SCENARIOS["windows-memory-soak"][0]
        data = json.loads(memory.read_text(encoding="utf-8")); data["functionalAcceptanceSha256"] = "f" * 64; write_json(memory, data)
        expect_failure("memory report is not bound to the actual functional report", lambda: mod.validate_windows_cohesion(functional, memory, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td); build_bundle(root)
        functional = root / mod.SCENARIOS["windows-post-r5-functional"][0]
        memory = root / mod.SCENARIOS["windows-memory-soak"][0]
        fdata = json.loads(functional.read_text(encoding="utf-8"))
        desktop = functional.parent / fdata["desktopAcceptanceEvidence"]
        ddata = json.loads(desktop.read_text(encoding="utf-8")); ddata["launcherSha256"] = "e" * 64; write_json(desktop, ddata)
        expect_failure("functional runtime binding desktop evidence changed", lambda: mod.validate_windows_cohesion(functional, memory, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td); fake = root / "fake-final.json"
        write_json(fake, {"overall": "PASS", "candidateSha": SHA})
        expect_failure("shallow fake external final", lambda: mod.validate_external_final(fake, SHA))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td); final = build_external_final(root / "v71-final-external-acceptance.json")
        data = json.loads(final.read_text(encoding="utf-8")); binding = next(row for row in data["evidence"] if row["name"] == "candidate-binding")
        binding["details"]["windowsIntegrityCandidateSha"] = "c" * 40; write_json(final, data)
        expect_failure("external final integrity candidate mismatch", lambda: mod.validate_external_final(final, SHA))

    print("Post-R5 live acceptance checker regression tests: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
