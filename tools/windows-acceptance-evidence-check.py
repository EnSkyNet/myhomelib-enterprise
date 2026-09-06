#!/usr/bin/env python3
"""Validate Windows MHL-011/MHL-012 evidence bundles.

This tool does not manufacture acceptance evidence. It only validates reports
produced on a real Windows host/runner and fails closed when required evidence
is missing, malformed, stale-looking, or detached from the evidence bundle.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
from collections import Counter
from pathlib import Path, PureWindowsPath

DPI_SCALES = (100, 125, 150, 200)
P4_IDS = [f"P4-{i:02d}" for i in range(1, 21)]
P5_IDS = [f"P5-{i:02d}" for i in range(1, 8)]
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
SESSION_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
MIN_SCREENSHOT_WIDTH = 640
MIN_SCREENSHOT_HEIGHT = 480


def load(path: Path) -> dict:
    if not path.is_file():
        raise AssertionError(f"missing report: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        raise AssertionError(f"invalid JSON report {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise AssertionError(f"{path}: report root must be a JSON object")
    return data


def require_contract(data: dict, report: Path, scenario: str) -> None:
    if data.get("schemaVersion") != 1:
        raise AssertionError(f"{report}: schemaVersion={data.get('schemaVersion')!r}, expected 1")
    if data.get("scenario") != scenario:
        raise AssertionError(f"{report}: scenario={data.get('scenario')!r}, expected {scenario!r}")


def require_nonblank(data: dict, report: Path, *keys: str) -> None:
    for key in keys:
        if not str(data.get(key) or "").strip():
            raise AssertionError(f"{report}: missing/blank field {key}")


def resolve_bundle_path(root: Path, report: Path, raw: object) -> Path:
    value = str(raw or "").strip()
    if not value:
        raise AssertionError(f"{report}: empty evidence path")
    # Acceptance screenshots are intentionally stored as paths relative to the
    # report directory. Reject absolute paths so a report cannot pass by pointing
    # at an unrelated PNG elsewhere on the tester's machine.
    if Path(value).is_absolute() or PureWindowsPath(value).is_absolute():
        raise AssertionError(f"{report}: evidence path must be relative to the bundle: {value}")
    normalized = value.replace("\\", "/")
    candidate = (report.parent / normalized).resolve()
    bundle_root = root.resolve()
    try:
        candidate.relative_to(bundle_root)
    except ValueError as exc:
        raise AssertionError(f"{report}: evidence escapes bundle root: {value}") from exc
    return candidate


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as fh:
            for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise AssertionError(f"cannot hash evidence file {path}: {exc}") from exc
    return digest.hexdigest()


def require_sha256(value: object, report: Path, field: str) -> str:
    text = str(value or "").strip()
    if not SHA256_RE.fullmatch(text):
        raise AssertionError(f"{report}: {field} must be a 64-character SHA-256 hex digest")
    return text.lower()


def verify_png(path: Path, report: Path, check_id: str) -> str:
    if not path.is_file() or path.suffix.lower() != ".png":
        raise AssertionError(f"{report}: {check_id} missing PNG evidence: {path}")
    try:
        with path.open("rb") as fh:
            head = fh.read(24)
        size = path.stat().st_size
    except OSError as exc:
        raise AssertionError(f"{report}: cannot read {check_id} evidence {path}: {exc}") from exc
    if size < 33 or head[:8] != PNG_SIGNATURE or head[12:16] != b"IHDR":
        raise AssertionError(f"{report}: {check_id} evidence is not a valid-looking PNG file: {path}")
    width, height = struct.unpack(">II", head[16:24])
    if width < MIN_SCREENSHOT_WIDTH or height < MIN_SCREENSHOT_HEIGHT:
        raise AssertionError(
            f"{report}: {check_id} screenshot is implausibly small: {width}x{height}; "
            f"expected at least {MIN_SCREENSHOT_WIDTH}x{MIN_SCREENSHOT_HEIGHT}"
        )
    return sha256_file(path)




def verify_host_binding(root: Path) -> dict:
    report = root / "windows-host-binding" / "windows-host-binding.json"
    data = load(report)
    require_contract(data, report, "windows-acceptance-host-binding")
    require_nonblank(
        data, report, "timestamp", "acceptanceSessionId", "candidateSha", "repository",
        "host", "user", "os", "osVersion", "osBuild", "osArchitecture",
        "hostFingerprintSha256", "userFingerprintSha256",
    )
    if data.get("overall") != "PASS":
        raise AssertionError(f"{report}: overall={data.get('overall')!r}, expected PASS")
    if data.get("isAdministrator") is not False:
        raise AssertionError(f"{report}: host binding was not created by a standard/non-elevated user")
    if SESSION_RE.fullmatch(str(data.get("acceptanceSessionId") or "")) is None:
        raise AssertionError(f"{report}: acceptanceSessionId is invalid")
    candidate_sha = str(data.get("candidateSha") or "").strip().lower()
    if re.fullmatch(r"[0-9a-f]{40}", candidate_sha) is None:
        raise AssertionError(f"{report}: candidateSha must be a full 40-character Git commit SHA")
    if re.fullmatch(r"[^/\s]+/[^/\s]+", str(data.get("repository") or "")) is None:
        raise AssertionError(f"{report}: repository must be OWNER/REPO")
    run_id = data.get("acceptanceRunId")
    if not isinstance(run_id, int) or run_id <= 0:
        raise AssertionError(f"{report}: acceptanceRunId missing/invalid")
    require_sha256(data.get("hostFingerprintSha256"), report, "hostFingerprintSha256")
    require_sha256(data.get("userFingerprintSha256"), report, "userFingerprintSha256")
    if str(data.get("osArchitecture") or "") not in {"x64", "x86", "arm64"}:
        raise AssertionError(f"{report}: osArchitecture={data.get('osArchitecture')!r}, expected x64/x86/arm64")
    return data


def verify_host_cohesion(root: Path, *, require_dpi: bool, require_desktop: bool) -> dict:
    binding = verify_host_binding(root)
    reports = [
        root / "windows-installer-acceptance" / "installer-acceptance.json",
        root / "windows-portable-acceptance" / "portable-smoke.json",
    ]
    if require_desktop:
        reports.append(root / "windows-release-desktop-acceptance" / "desktop-acceptance.json")
    if require_dpi:
        reports.extend(root / f"windows-ui-acceptance-{scale}.json" for scale in DPI_SCALES)

    fields = (
        "acceptanceSessionId", "host", "user", "hostFingerprintSha256",
        "userFingerprintSha256", "osVersion", "osBuild", "osArchitecture",
    )
    for report in reports:
        data = load(report)
        require_nonblank(data, report, *fields)
        for field in fields:
            if str(data.get(field) or "") != str(binding.get(field) or ""):
                raise AssertionError(
                    f"{report}: {field} does not match windows-host-binding; "
                    "evidence from multiple hosts/users/sessions cannot be combined"
                )
        if report.name == "desktop-acceptance.json":
            if str(data.get("candidateSha") or "").lower() != str(binding.get("candidateSha") or "").lower():
                raise AssertionError(f"{report}: candidateSha does not match windows-host-binding")
            if str(data.get("repository") or "") != str(binding.get("repository") or ""):
                raise AssertionError(f"{report}: repository does not match windows-host-binding")
    return binding

def verify_dpi(root: Path) -> None:
    seen_evidence: set[Path] = set()
    seen_digests: set[str] = set()
    for scale in DPI_SCALES:
        report = root / f"windows-ui-acceptance-{scale}.json"
        data = load(report)
        require_contract(data, report, "windows-ui-dpi-acceptance")
        if data.get("scale") != scale:
            raise AssertionError(f"{report}: embedded scale={data.get('scale')!r}, expected {scale}")
        if data.get("overall") != "PASS":
            raise AssertionError(f"{report}: overall={data.get('overall')!r}, expected PASS")
        require_nonblank(data, report, "timestamp", "host", "os", "observedDpi", "launcher")

        results = data.get("results")
        if not isinstance(results, list):
            raise AssertionError(f"{report}: results must be a list")
        if not all(isinstance(row, dict) for row in results):
            raise AssertionError(f"{report}: every result row must be an object")

        counts = Counter(row.get("Id") for row in results)
        for required_id in ("AUTO-0", "AUTO-1", *P4_IDS):
            if counts[required_id] != 1:
                raise AssertionError(
                    f"{report}: expected exactly one {required_id} row, found {counts[required_id]}"
                )
        rows = {row.get("Id"): row for row in results}
        for auto_id in ("AUTO-0", "AUTO-1"):
            if rows[auto_id].get("Outcome") != "PASS":
                raise AssertionError(f"{report}: {auto_id}={rows[auto_id].get('Outcome')!r}")

        for cid in P4_IDS:
            row = rows[cid]
            if row.get("Outcome") != "PASS":
                raise AssertionError(f"{report}: {cid}={row.get('Outcome')!r}")
            evidence = resolve_bundle_path(root, report, row.get("Evidence"))
            digest = verify_png(evidence, report, cid)
            if evidence in seen_evidence:
                raise AssertionError(f"{report}: screenshot evidence reused across checks: {evidence}")
            if digest in seen_digests:
                raise AssertionError(f"{report}: duplicate screenshot content reused across checks: {evidence}")
            seen_evidence.add(evidence)
            seen_digests.add(digest)


def verify_installer(root: Path, require_standard: bool, require_real_previous: bool) -> None:
    report = root / "windows-installer-acceptance" / "installer-acceptance.json"
    data = load(report)
    require_contract(data, report, "windows-installer-lifecycle")
    require_nonblank(
        data,
        report,
        "timestamp",
        "host",
        "os",
        "user",
        "previousVersion",
        "currentVersion",
        "previousPackageSource",
        "previousMsi",
        "currentMsi",
    )
    if data.get("overall") != "PASS":
        raise AssertionError(f"{report}: overall is not PASS")
    if data.get("previousVersion") == data.get("currentVersion"):
        raise AssertionError(f"{report}: previousVersion must differ from currentVersion")
    if data.get("previousPackageSource") not in {"synthetic", "external"}:
        raise AssertionError(
            f"{report}: previousPackageSource={data.get('previousPackageSource')!r}, expected synthetic/external"
        )
    previous_hash = require_sha256(data.get("previousMsiSha256"), report, "previousMsiSha256")
    current_hash = require_sha256(data.get("currentMsiSha256"), report, "currentMsiSha256")
    if previous_hash == current_hash:
        raise AssertionError(f"{report}: previous/current MSI hashes must differ")

    logs = data.get("msiexecLogs")
    if not isinstance(logs, list) or len(logs) != 4 or len(set(map(str, logs))) != 4:
        raise AssertionError(f"{report}: expected exactly four unique msiexecLogs entries")
    for index, raw_log in enumerate(logs, start=1):
        log_path = resolve_bundle_path(root, report, raw_log)
        if log_path.suffix.lower() != ".log" or not log_path.is_file():
            raise AssertionError(f"{report}: msiexec log {index} missing: {log_path}")
        try:
            if log_path.stat().st_size < 32:
                raise AssertionError(f"{report}: msiexec log {index} is empty/truncated: {log_path}")
        except OSError as exc:
            raise AssertionError(f"{report}: cannot inspect msiexec log {index}: {exc}") from exc
    if require_standard:
        if data.get("requireStandardUser") is not True:
            raise AssertionError(f"{report}: -RequireStandardUser guard was not enabled")
        if data.get("isAdministrator") is not False:
            raise AssertionError(
                f"{report}: installer acceptance was not proven under a standard/non-elevated user"
            )
    if require_real_previous and data.get("previousPackageSource") != "external":
        raise AssertionError(f"{report}: final acceptance requires a real previous-release MSI")
    for key in (
        "installPrevious",
        "upgradeCurrent",
        "repeatCurrent",
        "uninstall",
        "shortcutsRemoved",
        "userDataPreserved",
    ):
        if data.get(key) != "PASS":
            raise AssertionError(f"{report}: {key}={data.get(key)!r}")


def verify_portable(root: Path) -> None:
    report = root / "windows-portable-acceptance" / "portable-smoke.json"
    data = load(report)
    require_contract(data, report, "windows-portable-unicode-smoke")
    require_nonblank(
        data,
        report,
        "timestamp",
        "host",
        "user",
        "os",
        "archive",
        "extractPath",
        "syntheticHome",
        "workingDirectory",
        "launcher",
    )
    if data.get("overall") != "PASS":
        raise AssertionError(f"{report}: overall is not PASS")
    require_sha256(data.get("archiveSha256"), report, "archiveSha256")
    if data.get("profileEnvironmentRedirected") is not True:
        raise AssertionError(f"{report}: synthetic profile environment redirect was not proven")
    if data.get("syntheticHomeWriteDetected") is not False:
        raise AssertionError(f"{report}: portable wrote into the synthetic user profile")
    if data.get("workingDirectoryWriteDetected") is not False:
        raise AssertionError(f"{report}: portable wrote into the working directory")
    if data.get("profileWriteDetected") is not False:
        raise AssertionError(f"{report}: portable wrote outside the launcher tree or field is missing")
    if data.get("portableDataCreated") is not True or data.get("markerPresent") is not True:
        raise AssertionError(f"{report}: portable acceptance contract incomplete")
    if data.get("launcherExitCode") != 0:
        raise AssertionError(f"{report}: launcherExitCode={data.get('launcherExitCode')!r}, expected 0")

    exercised: list[str] = []
    for key in ("extractPath", "syntheticHome", "workingDirectory"):
        value = str(data.get(key) or "")
        if not any(ord(ch) > 127 for ch in value):
            raise AssertionError(f"{report}: {key} did not exercise a Unicode path")
        if " " not in value:
            raise AssertionError(f"{report}: {key} did not exercise a path containing spaces")
        exercised.append(value)
    if len(set(exercised)) != len(exercised):
        raise AssertionError(f"{report}: extract/home/cwd paths must be distinct")



def verify_release_desktop(root: Path, expected_exe_sha: str | None = None) -> dict:
    report = root / "windows-release-desktop-acceptance" / "desktop-acceptance.json"
    data = load(report)
    require_contract(data, report, "windows-release-desktop-acceptance")
    require_nonblank(
        data, report, "timestamp", "host", "os", "user", "candidateSha", "repository",
        "releaseRunId", "releaseRunUrl", "exeInstaller", "exeSha256", "launcher", "previousVersion",
    )
    candidate_sha = str(data.get("candidateSha") or "").strip().lower()
    if re.fullmatch(r"[0-9a-f]{40}", candidate_sha) is None:
        raise AssertionError(f"{report}: candidateSha must be a full 40-character Git commit SHA")
    exe_sha = require_sha256(data.get("exeSha256"), report, "exeSha256")
    if expected_exe_sha and exe_sha != expected_exe_sha.lower():
        raise AssertionError(f"{report}: EXE SHA-256 does not match the GitHub release candidate")
    if data.get("requireStandardUser") is not True or data.get("isAdministrator") is not False:
        raise AssertionError(f"{report}: desktop acceptance was not proven under a standard/non-elevated user")
    if data.get("overall") != "PASS":
        raise AssertionError(f"{report}: overall={data.get('overall')!r}, expected PASS")
    run_id = data.get("releaseRunId")
    if not isinstance(run_id, int) or run_id <= 0:
        raise AssertionError(f"{report}: releaseRunId missing/invalid")
    if not str(data.get("releaseRunUrl") or "").startswith("https://"):
        raise AssertionError(f"{report}: releaseRunUrl missing/invalid")

    results = data.get("results")
    if not isinstance(results, list) or not all(isinstance(row, dict) for row in results):
        raise AssertionError(f"{report}: results must be a list of objects")
    counts = Counter(row.get("Id") for row in results)
    for required_id in ("AUTO-1", *P5_IDS):
        if counts[required_id] != 1:
            raise AssertionError(f"{report}: expected exactly one {required_id} row, found {counts[required_id]}")
    rows = {row.get("Id"): row for row in results}
    if rows["AUTO-1"].get("Outcome") != "PASS":
        raise AssertionError(f"{report}: AUTO-1={rows['AUTO-1'].get('Outcome')!r}")

    seen_evidence: set[Path] = set()
    seen_digests: set[str] = set()
    for cid in P5_IDS:
        row = rows[cid]
        if row.get("Outcome") != "PASS":
            raise AssertionError(f"{report}: {cid}={row.get('Outcome')!r}")
        if cid in {"P5-03", "P5-05", "P5-07"} and not str(row.get("Note") or "").strip():
            raise AssertionError(f"{report}: {cid} requires a non-empty evidence note")
        evidence = resolve_bundle_path(root, report, row.get("Evidence"))
        digest = verify_png(evidence, report, cid)
        if evidence in seen_evidence:
            raise AssertionError(f"{report}: desktop screenshot evidence reused across checks: {evidence}")
        if digest in seen_digests:
            raise AssertionError(f"{report}: duplicate desktop screenshot content reused across checks: {evidence}")
        seen_evidence.add(evidence)
        seen_digests.add(digest)
    return data

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, default=Path("target"))
    ap.add_argument("--require-standard-user", action="store_true")
    ap.add_argument(
        "--require-real-previous",
        action="store_true",
        help="require installer evidence to use an externally supplied previous-release MSI",
    )
    ap.add_argument(
        "--dpi",
        action="store_true",
        help="also require manual 100/125/150/200 DPI screenshot evidence",
    )
    ap.add_argument(
        "--require-host-binding",
        action="store_true",
        help="require all requested Windows evidence to belong to one bound host/user/session",
    )
    ap.add_argument(
        "--release-desktop",
        action="store_true",
        help="also require the real EXE/data-migration/collection/Reader/backup-restore desktop smoke",
    )
    ns = ap.parse_args()
    verify_installer(ns.root, ns.require_standard_user, ns.require_real_previous)
    verify_portable(ns.root)
    if ns.dpi:
        verify_dpi(ns.root)
    if ns.release_desktop:
        verify_release_desktop(ns.root)
    if ns.require_host_binding:
        verify_host_cohesion(ns.root, require_dpi=ns.dpi, require_desktop=ns.release_desktop)
    print("Windows acceptance evidence: PASS")


if __name__ == "__main__":
    main()
