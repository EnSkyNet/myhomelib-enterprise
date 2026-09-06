#!/usr/bin/env python3
"""Consolidate the six remaining external 7.1 Final acceptance items.

This is intentionally a *post-evidence* gate. It does not create or simulate
GitHub/Windows acceptance. It revalidates the strict Windows bundle, verifies
connected GitHub evidence, checks the final Windows evidence archive checksum,
and emits one consolidated JSON/Markdown decision record.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import sys
import tempfile
import zipfile
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
REQUIRED_GITHUB_CHECKS = {
    "MHL-010-A",
    "MHL-010-B",
    "MHL-017/MHL-018",
    "MHL-019",
}


class FinalAcceptanceError(RuntimeError):
    pass


@dataclass
class Evidence:
    name: str
    status: str
    details: dict[str, Any]


def need(cond: bool, message: str) -> None:
    if not cond:
        raise FinalAcceptanceError(message)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    need(path.is_file(), f"missing JSON evidence: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception as exc:  # noqa: BLE001
        raise FinalAcceptanceError(f"invalid JSON evidence {path}: {exc}") from exc
    need(isinstance(data, dict), f"JSON evidence root must be an object: {path}")
    return data


def verify_github_json(path: Path) -> Evidence:
    data = load_json(path)
    need(data.get("schemaVersion") == 2, f"{path}: schemaVersion must be 2 (candidate-bound evidence)")
    need(data.get("scenario") == "github-connected-acceptance", f"{path}: wrong scenario")
    need(data.get("overall") == "PASS", f"{path}: overall is not PASS")
    need(str(data.get("repository") or "").strip(), f"{path}: repository missing")
    need(str(data.get("branch") or "").strip(), f"{path}: branch missing")
    need(str(data.get("timestamp") or "").strip(), f"{path}: timestamp missing")
    candidate_sha = str(data.get("candidateSha") or "").strip().lower()
    need(re.fullmatch(r"[0-9a-f]{40}", candidate_sha) is not None, f"{path}: candidateSha must be a full Git commit SHA")
    harness_manifest_sha = str(data.get("acceptanceHarnessManifestSha256") or "").strip().lower()
    need(SHA256_RE.fullmatch(harness_manifest_sha) is not None, f"{path}: acceptanceHarnessManifestSha256 missing/invalid")
    checks = data.get("checks")
    need(isinstance(checks, list), f"{path}: checks must be a list")
    seen: dict[str, dict[str, Any]] = {}
    for row in checks:
        need(isinstance(row, dict), f"{path}: check row must be an object")
        cid = str(row.get("id") or "")
        need(cid, f"{path}: check id missing")
        need(cid not in seen, f"{path}: duplicate check id {cid}")
        seen[cid] = row
    missing = REQUIRED_GITHUB_CHECKS - set(seen)
    need(not missing, f"{path}: missing required GitHub check(s): {', '.join(sorted(missing))}")
    for cid in REQUIRED_GITHUB_CHECKS:
        need(seen[cid].get("status") == "PASS", f"{path}: {cid} is not PASS")

    release_details = seen["MHL-017/MHL-018"].get("details")
    need(isinstance(release_details, dict), f"{path}: MHL-017/MHL-018 details missing")
    release_sha = str(release_details.get("headSha") or "").strip().lower()
    need(release_sha == candidate_sha, f"{path}: CI Release headSha does not match candidateSha")
    windows_msi_sha = str(release_details.get("windowsMsiSha256") or "").strip().lower()
    need(SHA256_RE.fullmatch(windows_msi_sha) is not None, f"{path}: bound Windows MSI SHA-256 missing/invalid")
    windows_exe_sha = str(release_details.get("windowsExeSha256") or "").strip().lower()
    need(SHA256_RE.fullmatch(windows_exe_sha) is not None, f"{path}: bound Windows EXE SHA-256 missing/invalid")
    windows_portable_sha = str(release_details.get("windowsPortableSha256") or "").strip().lower()
    need(SHA256_RE.fullmatch(windows_portable_sha) is not None, f"{path}: bound Windows portable SHA-256 missing/invalid")
    release_run_id = release_details.get("runId")
    need(isinstance(release_run_id, int) and release_run_id > 0, f"{path}: CI Release runId missing/invalid")
    release_run_url = str(release_details.get("htmlUrl") or "").strip()
    need(release_run_url.startswith("https://"), f"{path}: CI Release htmlUrl missing/invalid")

    return Evidence(
        "github",
        "PASS",
        {
            "path": str(path),
            "sha256": sha256_file(path),
            "repository": data.get("repository"),
            "branch": data.get("branch"),
            "timestamp": data.get("timestamp"),
            "candidateSha": candidate_sha,
            "acceptanceHarnessManifestSha256": harness_manifest_sha,
            "windowsMsiSha256": windows_msi_sha,
            "windowsExeSha256": windows_exe_sha,
            "windowsPortableSha256": windows_portable_sha,
            "releaseRunId": release_run_id,
            "releaseRunUrl": release_run_url,
            "checks": sorted(REQUIRED_GITHUB_CHECKS),
        },
    )


def verify_github_ingest(path: Path, github: Evidence) -> Evidence:
    data = load_json(path)
    need(data.get("schemaVersion") == 1, f"{path}: schemaVersion must be 1")
    need(data.get("scenario") == "github-connected-acceptance-artifact-ingest", f"{path}: wrong scenario")
    need(data.get("overall") == "PASS", f"{path}: overall is not PASS")
    need(data.get("remoteDigestVerified") is True, f"{path}: final acceptance requires GitHub API digest-verified ingest")
    details = github.details
    for key in ("candidateSha", "repository", "releaseRunId", "releaseRunUrl", "windowsMsiSha256", "windowsExeSha256", "windowsPortableSha256", "acceptanceHarnessManifestSha256"):
        need(data.get(key) == details.get(key), f"{path}: ingest/GitHub mismatch for {key}")
    acceptance_run_id = data.get("acceptanceRunId")
    need(isinstance(acceptance_run_id, int) and acceptance_run_id > 0, f"{path}: acceptanceRunId missing/invalid")
    acceptance_run_url = str(data.get("acceptanceRunUrl") or "")
    need(acceptance_run_url.startswith("https://"), f"{path}: acceptanceRunUrl missing/invalid")
    artifact_id = data.get("githubArtifactId")
    need(isinstance(artifact_id, int) and artifact_id > 0, f"{path}: githubArtifactId missing/invalid")
    artifact_name = str(data.get("githubArtifactName") or "").strip()
    need(artifact_name.startswith(f"github-connected-acceptance-{acceptance_run_id}-"), f"{path}: unexpected githubArtifactName")
    artifact_sha = str(data.get("artifactZipSha256") or "").strip().lower()
    need(SHA256_RE.fullmatch(artifact_sha) is not None, f"{path}: artifactZipSha256 missing/invalid")
    declared = str(data.get("githubDeclaredDigest") or "").strip().lower()
    need(declared == "sha256:" + artifact_sha, f"{path}: GitHub declared digest does not match verified artifact ZIP hash")
    return Evidence(
        "github-ingest", "PASS",
        {
            "path": str(path), "sha256": sha256_file(path),
            "acceptanceRunId": acceptance_run_id, "acceptanceRunUrl": acceptance_run_url,
            "githubArtifactId": artifact_id, "githubArtifactName": artifact_name,
            "artifactZipSha256": artifact_sha, "remoteDigestVerified": True,
            "candidateSha": details["candidateSha"], "repository": details["repository"],
            "releaseRunId": details["releaseRunId"], "releaseRunUrl": details["releaseRunUrl"],
            "windowsMsiSha256": details["windowsMsiSha256"],
            "windowsExeSha256": details["windowsExeSha256"],
            "windowsPortableSha256": details["windowsPortableSha256"],
            "acceptanceHarnessManifestSha256": details["acceptanceHarnessManifestSha256"],
        },
    )


def verify_harness_binding(path: Path, github: Evidence, manifest_path: Path | None = None) -> Evidence:
    data = load_json(path)
    need(data.get("schemaVersion") == 1, f"{path}: schemaVersion must be 1")
    need(data.get("scenario") == "windows-acceptance-harness-binding", f"{path}: wrong scenario")
    need(data.get("overall") == "PASS", f"{path}: overall is not PASS")
    need(str(data.get("candidateSha") or "").strip().lower() == github.details["candidateSha"],
         f"{path}: harness binding candidate SHA mismatch")
    manifest_sha = str(data.get("manifestSha256") or "").strip().lower()
    need(manifest_sha == github.details["acceptanceHarnessManifestSha256"],
         f"{path}: harness binding manifest SHA does not match GitHub evidence")
    count = data.get("fileCount")
    need(isinstance(count, int) and count > 0, f"{path}: fileCount missing/invalid")
    files = data.get("files")
    need(isinstance(files, list) and len(files) == count, f"{path}: files/fileCount mismatch")
    rows: dict[str, str] = {}
    for row in files:
        need(isinstance(row, dict), f"{path}: harness file row must be an object")
        rel = str(row.get("path") or "").replace("\\", "/")
        digest = str(row.get("sha256") or "").strip().lower()
        need(rel and rel not in rows, f"{path}: duplicate/blank harness file path")
        need(SHA256_RE.fullmatch(digest) is not None, f"{path}: invalid harness file SHA-256 for {rel}")
        rows[rel] = digest
    if manifest_path is not None:
        need(manifest_path.is_file(), f"missing acceptance harness manifest: {manifest_path}")
        need(sha256_file(manifest_path) == manifest_sha, f"{manifest_path}: SHA-256 does not match binding/GitHub evidence")
        manifest_rows: dict[str, str] = {}
        for line_no, raw in enumerate(manifest_path.read_text(encoding="utf-8-sig").splitlines(), 1):
            if not raw.strip():
                continue
            parts = raw.strip().split(None, 1)
            need(len(parts) == 2, f"{manifest_path}: invalid manifest line {line_no}")
            digest, rel = parts
            digest = digest.lower()
            rel = rel.lstrip("*").replace("\\", "/")
            need(SHA256_RE.fullmatch(digest) is not None, f"{manifest_path}: invalid SHA-256 on line {line_no}")
            need(rel not in manifest_rows, f"{manifest_path}: duplicate manifest entry {rel}")
            manifest_rows[rel] = digest
        need(rows == manifest_rows, f"{path}: harness binding file set/hashes do not match acceptance harness manifest")
    return Evidence(
        "harness-binding", "PASS",
        {
            "path": str(path), "sha256": sha256_file(path),
            "candidateSha": github.details["candidateSha"],
            "manifestSha256": manifest_sha, "fileCount": count,
        },
    )


def parse_sidecar(path: Path) -> tuple[str, str]:
    need(path.is_file(), f"missing SHA-256 sidecar: {path}")
    text = path.read_text(encoding="utf-8-sig").strip()
    parts = text.split()
    need(len(parts) >= 2, f"invalid SHA-256 sidecar: {path}")
    digest = parts[0].lower()
    name = parts[-1].lstrip("*")
    need(bool(SHA256_RE.fullmatch(digest)), f"invalid SHA-256 digest in {path}")
    return digest, name


def verify_windows_archive(
    path: Path, expected_current_msi_sha: str | None = None, expected_portable_sha: str | None = None,
    expected_exe_sha: str | None = None,
) -> Evidence:
    need(path.is_file(), f"missing Windows final evidence archive: {path}")
    sidecar = Path(str(path) + ".sha256")
    expected, sidecar_name = parse_sidecar(sidecar)
    need(sidecar_name == path.name, f"{sidecar}: expected filename {path.name!r}, found {sidecar_name!r}")
    actual = sha256_file(path)
    need(actual == expected, f"Windows evidence archive SHA-256 mismatch: expected {expected}, got {actual}")

    try:
        zf = zipfile.ZipFile(path)
    except zipfile.BadZipFile as exc:
        raise FinalAcceptanceError(f"Windows evidence archive is not a valid ZIP: {path}") from exc
    with zf:
        names = [n.replace("\\", "/") for n in zf.namelist() if not n.endswith("/")]
        need(names, f"Windows evidence archive is empty: {path}")
        need(len(names) == len(set(names)), f"Windows evidence archive contains duplicate member names")
        for name in names:
            parts = Path(name).parts
            need(not name.startswith("/") and ".." not in parts, f"unsafe Windows evidence archive member: {name}")
        need("manifest.sha256" in names, "Windows evidence archive missing manifest.sha256")
        required = {
            "windows-installer-acceptance/installer-acceptance.json",
            "windows-installer-acceptance/installer-acceptance.md",
            "windows-portable-acceptance/portable-smoke.json",
            "windows-portable-acceptance/portable-smoke.md",
            "windows-ui-acceptance-100.json",
            "windows-ui-acceptance-100.md",
            "windows-ui-acceptance-125.json",
            "windows-ui-acceptance-125.md",
            "windows-ui-acceptance-150.json",
            "windows-ui-acceptance-150.md",
            "windows-ui-acceptance-200.json",
            "windows-ui-acceptance-200.md",
            "windows-release-desktop-acceptance/desktop-acceptance.json",
            "windows-release-desktop-acceptance/desktop-acceptance.md",
            "windows-host-binding/windows-host-binding.json",
        }
        missing = required - set(names)
        need(not missing, "Windows evidence archive missing required evidence: " + ", ".join(sorted(missing)))

        try:
            manifest_text = zf.read("manifest.sha256").decode("utf-8-sig")
        except Exception as exc:  # noqa: BLE001
            raise FinalAcceptanceError(f"cannot read Windows archive manifest: {exc}") from exc
        manifest: dict[str, str] = {}
        for line_no, raw in enumerate(manifest_text.splitlines(), 1):
            if not raw.strip():
                continue
            parts = raw.strip().split(None, 1)
            need(len(parts) == 2, f"Windows archive manifest line {line_no} is invalid")
            digest, name = parts
            name = name.lstrip("*").replace("\\", "/")
            need(SHA256_RE.fullmatch(digest) is not None, f"Windows archive manifest line {line_no} has invalid digest")
            need(name not in manifest, f"Windows archive manifest has duplicate entry: {name}")
            manifest[name] = digest.lower()
        payload_names = set(names) - {"manifest.sha256"}
        need(set(manifest) == payload_names, "Windows archive manifest/member set mismatch")
        for name, digest in manifest.items():
            member_hash = hashlib.sha256(zf.read(name)).hexdigest()
            need(member_hash == digest, f"Windows archive manifest checksum mismatch for {name}")

        try:
            installer = json.loads(zf.read("windows-installer-acceptance/installer-acceptance.json").decode("utf-8-sig"))
        except Exception as exc:  # noqa: BLE001
            raise FinalAcceptanceError(f"invalid installer evidence inside Windows archive: {exc}") from exc
        archive_msi_sha = str(installer.get("currentMsiSha256") or "").strip().lower()
        need(SHA256_RE.fullmatch(archive_msi_sha) is not None, "Windows archive installer evidence lacks currentMsiSha256")
        if expected_current_msi_sha:
            need(archive_msi_sha == expected_current_msi_sha.lower(),
                 "Windows archive current MSI SHA-256 does not match the GitHub release candidate")

        try:
            portable = json.loads(zf.read("windows-portable-acceptance/portable-smoke.json").decode("utf-8-sig"))
        except Exception as exc:  # noqa: BLE001
            raise FinalAcceptanceError(f"invalid portable evidence inside Windows archive: {exc}") from exc
        archive_portable_sha = str(portable.get("archiveSha256") or "").strip().lower()
        need(SHA256_RE.fullmatch(archive_portable_sha) is not None, "Windows archive portable evidence lacks archiveSha256")
        if expected_portable_sha:
            need(archive_portable_sha == expected_portable_sha.lower(),
                 "Windows archive portable SHA-256 does not match the GitHub release candidate")

        # Re-run the same strict validator against the archive payload itself. This
        # prevents a valid live root plus a detached/altered reviewer ZIP from passing.
        with tempfile.TemporaryDirectory(prefix="myhomelib-windows-evidence-") as td:
            extracted = Path(td)
            for name in names:
                destination = extracted.joinpath(*Path(name).parts)
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(zf.read(name))
            validator = load_windows_validator()
            try:
                validator.verify_installer(extracted, True, True)
                validator.verify_portable(extracted)
                validator.verify_dpi(extracted)
                desktop = validator.verify_release_desktop(extracted, expected_exe_sha)
                host_binding = validator.verify_host_cohesion(extracted, require_dpi=True, require_desktop=True)
            except AssertionError as exc:
                raise FinalAcceptanceError(f"strict validation of Windows archive payload failed: {exc}") from exc

    return Evidence(
        "windows-archive",
        "PASS",
        {
            "path": str(path), "sha256": actual, "sidecar": str(sidecar),
            "currentMsiSha256": archive_msi_sha, "portableSha256": archive_portable_sha,
            "exeSha256": str(desktop.get("exeSha256") or "").strip().lower(),
            "acceptanceSessionId": str(host_binding.get("acceptanceSessionId") or ""),
            "hostFingerprintSha256": str(host_binding.get("hostFingerprintSha256") or "").strip().lower(),
            "userFingerprintSha256": str(host_binding.get("userFingerprintSha256") or "").strip().lower(),
            "candidateSha": str(host_binding.get("candidateSha") or "").strip().lower(),
            "repository": str(host_binding.get("repository") or ""),
            "acceptanceRunId": host_binding.get("acceptanceRunId"),
        },
    )


def load_windows_validator():
    path = ROOT / "tools/windows-acceptance-evidence-check.py"
    spec = importlib.util.spec_from_file_location("windows_acceptance_validator", path)
    need(spec is not None and spec.loader is not None, f"cannot load Windows validator: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def verify_windows_root(root: Path) -> Evidence:
    need(root.is_dir(), f"Windows evidence root not found: {root}")
    validator = load_windows_validator()
    try:
        validator.verify_installer(root, True, True)
        validator.verify_portable(root)
        validator.verify_dpi(root)
        desktop = validator.verify_release_desktop(root)
        host_binding = validator.verify_host_cohesion(root, require_dpi=True, require_desktop=True)
    except AssertionError as exc:
        raise FinalAcceptanceError(f"strict Windows evidence validation failed: {exc}") from exc
    installer_report = root / "windows-installer-acceptance" / "installer-acceptance.json"
    installer = load_json(installer_report)
    current_msi_sha = str(installer.get("currentMsiSha256") or "").strip().lower()
    need(SHA256_RE.fullmatch(current_msi_sha) is not None, f"{installer_report}: currentMsiSha256 missing/invalid")
    portable_report = root / "windows-portable-acceptance" / "portable-smoke.json"
    portable = load_json(portable_report)
    portable_sha = str(portable.get("archiveSha256") or "").strip().lower()
    need(SHA256_RE.fullmatch(portable_sha) is not None, f"{portable_report}: archiveSha256 missing/invalid")
    return Evidence(
        "windows",
        "PASS",
        {
            "root": str(root),
            "standardUser": True,
            "realPreviousMsi": True,
            "dpi": [100, 125, 150, 200],
            "currentMsiSha256": current_msi_sha,
            "exeSha256": str(desktop.get("exeSha256") or "").strip().lower(),
            "portableSha256": portable_sha,
            "acceptanceSessionId": str(host_binding.get("acceptanceSessionId") or ""),
            "hostFingerprintSha256": str(host_binding.get("hostFingerprintSha256") or "").strip().lower(),
            "userFingerprintSha256": str(host_binding.get("userFingerprintSha256") or "").strip().lower(),
            "candidateSha": str(host_binding.get("candidateSha") or "").strip().lower(),
            "repository": str(host_binding.get("repository") or ""),
            "acceptanceRunId": host_binding.get("acceptanceRunId"),
        },
    )


def write_result(out_dir: Path, status: str, evidence: list[Evidence], failure: str | None = None) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "schemaVersion": 1,
        "scenario": "myhomelib-7.1-final-external-acceptance",
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "overall": status,
        "backlogItems": ["MHL-010", "MHL-011", "MHL-012", "MHL-017", "MHL-018", "MHL-019"],
        "evidence": [asdict(x) for x in evidence],
        "failure": failure,
    }
    (out_dir / "v71-final-external-acceptance.json").write_text(
        json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    lines = [
        "# MyHomeLib 7.1 Final — external acceptance",
        "",
        f"Overall: **{status}**",
        "",
        "| Evidence | Status |",
        "|---|---|",
    ]
    for item in evidence:
        lines.append(f"| {item.name} | {item.status} |")
    if failure:
        lines.extend(["", "## Failure", "", failure])
    lines.append("")
    (out_dir / "v71-final-external-acceptance.md").write_text("\n".join(lines), encoding="utf-8")


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--windows-root", type=Path, default=Path("target"))
    p.add_argument("--windows-archive", type=Path, default=Path("target/windows-final-acceptance-evidence.zip"))
    p.add_argument("--github-json", type=Path, default=Path("target/github-connected-acceptance/github-connected-acceptance.json"))
    p.add_argument("--github-ingest-json", type=Path, default=Path("target/github-connected-acceptance/github-connected-acceptance-ingest.json"))
    p.add_argument("--harness-binding-json", type=Path, default=Path("target/windows-harness-binding/windows-harness-binding.json"))
    p.add_argument("--harness-manifest", type=Path, default=Path("target/github-connected-acceptance/acceptance-harness.sha256"))
    p.add_argument("--out-dir", type=Path, default=Path("target/v71-final-external-acceptance"))
    p.add_argument("--github-only", action="store_true", help="offline regression/helper mode: validate only supplied GitHub JSON")
    p.add_argument("--require-ingest", action="store_true", help="also require GitHub API digest-verified acceptance artifact ingest")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    evidence: list[Evidence] = []
    try:
        github = verify_github_json(args.github_json)
        evidence.append(github)
        if args.require_ingest or not args.github_only:
            evidence.append(verify_github_ingest(args.github_ingest_json, github))
        if not args.github_only:
            evidence.append(verify_harness_binding(args.harness_binding_json, github, args.harness_manifest))
            windows = verify_windows_root(args.windows_root)
            expected_msi_sha = str(github.details["windowsMsiSha256"])
            expected_exe_sha = str(github.details["windowsExeSha256"])
            expected_portable_sha = str(github.details["windowsPortableSha256"])
            need(str(windows.details["currentMsiSha256"]) == expected_msi_sha,
                 "Windows acceptance current MSI SHA-256 does not match the GitHub release candidate")
            need(str(windows.details["exeSha256"]) == expected_exe_sha,
                 "Windows desktop acceptance EXE SHA-256 does not match the GitHub release candidate")
            need(str(windows.details["portableSha256"]) == expected_portable_sha,
                 "Windows acceptance portable SHA-256 does not match the GitHub release candidate")
            need(str(windows.details["candidateSha"]) == str(github.details["candidateSha"]),
                 "Windows host binding candidate SHA does not match connected GitHub evidence")
            need(str(windows.details["repository"]) == str(github.details["repository"]),
                 "Windows host binding repository does not match connected GitHub evidence")
            need(windows.details["acceptanceRunId"] == evidence[1].details["acceptanceRunId"],
                 "Windows host binding acceptance run id does not match digest-verified GitHub ingest")
            evidence.append(windows)
            archive = verify_windows_archive(args.windows_archive, expected_msi_sha, expected_portable_sha, expected_exe_sha)
            need(str(archive.details["acceptanceSessionId"]) == str(windows.details["acceptanceSessionId"]),
                 "Windows archive acceptance session does not match live Windows evidence root")
            need(str(archive.details["hostFingerprintSha256"]) == str(windows.details["hostFingerprintSha256"]),
                 "Windows archive host fingerprint does not match live Windows evidence root")
            need(str(archive.details["userFingerprintSha256"]) == str(windows.details["userFingerprintSha256"]),
                 "Windows archive user fingerprint does not match live Windows evidence root")
            need(str(archive.details["candidateSha"]) == str(github.details["candidateSha"]),
                 "Windows archive host binding candidate SHA does not match connected GitHub evidence")
            need(archive.details["acceptanceRunId"] == evidence[1].details["acceptanceRunId"],
                 "Windows archive host binding acceptance run id does not match digest-verified GitHub ingest")
            evidence.append(archive)
            evidence.append(Evidence(
                "candidate-binding",
                "PASS",
                {
                    "candidateSha": github.details["candidateSha"],
                    "windowsMsiSha256": expected_msi_sha,
                    "windowsExeSha256": expected_exe_sha,
                    "windowsPortableSha256": expected_portable_sha,
                    "releaseRunId": github.details["releaseRunId"],
                    "releaseRunUrl": github.details["releaseRunUrl"],
                    "acceptanceRunId": evidence[1].details["acceptanceRunId"],
                    "acceptanceRunUrl": evidence[1].details["acceptanceRunUrl"],
                    "acceptanceHarnessManifestSha256": github.details["acceptanceHarnessManifestSha256"],
                    "acceptanceSessionId": windows.details["acceptanceSessionId"],
                    "hostFingerprintSha256": windows.details["hostFingerprintSha256"],
                    "userFingerprintSha256": windows.details["userFingerprintSha256"],
                },
            ))
        write_result(args.out_dir, "PASS", evidence)
        print("MyHomeLib 7.1 final external acceptance: PASS")
        return 0
    except (FinalAcceptanceError, OSError) as exc:
        write_result(args.out_dir, "FAIL", evidence, str(exc))
        print(f"MyHomeLib 7.1 final external acceptance: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
