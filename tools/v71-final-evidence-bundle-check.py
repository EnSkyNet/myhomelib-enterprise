#!/usr/bin/env python3
"""Verify the immutable reviewer bundle for MyHomeLib 7.1 external acceptance.

This is the last offline integrity gate. It verifies the outer ZIP + sidecar,
its exact manifest, candidate manifest, connected GitHub evidence, the nested
strict Windows evidence ZIP, and the consolidated final decision record.
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
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
SESSION_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
EXPECTED_BACKLOG = {"MHL-010", "MHL-011", "MHL-012", "MHL-017", "MHL-018", "MHL-019"}
REQUIRED_MEMBERS = {
    "manifest.sha256",
    "github/github-connected-acceptance.json",
    "github/github-connected-acceptance.md",
    "github/github-connected-acceptance-ingest.json",
    "github/acceptance-harness.sha256",
    "github/candidate-windows.sha256",
    "windows/windows-harness-binding.json",
    "windows/windows-host-binding.json",
    "windows/windows-final-acceptance-evidence.zip",
    "windows/windows-final-acceptance-evidence.zip.sha256",
    "final/v71-final-external-acceptance.json",
    "final/v71-final-external-acceptance.md",
}


class BundleError(RuntimeError):
    pass


def need(cond: bool, message: str) -> None:
    if not cond:
        raise BundleError(message)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_sidecar_text(text: str, expected_name: str, label: str) -> str:
    parts = text.strip().split()
    need(len(parts) >= 2, f"{label}: invalid SHA-256 sidecar")
    digest = parts[0].lower()
    name = parts[-1].lstrip("*")
    need(SHA256_RE.fullmatch(digest) is not None, f"{label}: invalid SHA-256 digest")
    need(name == expected_name, f"{label}: expected filename {expected_name!r}, found {name!r}")
    return digest


def parse_manifest(text: str, label: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line_no, raw in enumerate(text.splitlines(), 1):
        if not raw.strip():
            continue
        parts = raw.strip().split(None, 1)
        need(len(parts) == 2, f"{label}: manifest line {line_no} is invalid")
        digest, name = parts
        name = name.lstrip("*").replace("\\", "/")
        need(SHA256_RE.fullmatch(digest) is not None, f"{label}: manifest line {line_no} has invalid digest")
        need(name not in result, f"{label}: duplicate manifest entry {name}")
        result[name] = digest.lower()
    return result


def load_final_module():
    path = ROOT / "tools/v71-final-external-acceptance-check.py"
    spec = importlib.util.spec_from_file_location("v71_final_external_for_bundle", path)
    need(spec is not None and spec.loader is not None, f"cannot load final acceptance validator: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def load_json_bytes(blob: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(blob.decode("utf-8-sig"))
    except Exception as exc:  # noqa: BLE001
        raise BundleError(f"{label}: invalid JSON: {exc}") from exc
    need(isinstance(value, dict), f"{label}: JSON root must be an object")
    return value


def verify_candidate_manifest(text: str, github: dict[str, Any]) -> dict[str, str]:
    manifest = parse_manifest(text, "candidate-windows.sha256")
    need(len(manifest) == 3, "candidate-windows.sha256 must contain exactly MSI, EXE and portable entries")
    msi = [(name, digest) for name, digest in manifest.items() if name.lower().endswith(".msi")]
    exe = [(name, digest) for name, digest in manifest.items() if name.lower().endswith(".exe")]
    portable = [(name, digest) for name, digest in manifest.items() if name.lower().endswith(".zip")]
    need(len(msi) == 1, "candidate-windows.sha256 must contain exactly one MSI entry")
    need(len(exe) == 1, "candidate-windows.sha256 must contain exactly one EXE entry")
    need(len(portable) == 1, "candidate-windows.sha256 must contain exactly one portable ZIP entry")
    need(msi[0][1] == str(github["windowsMsiSha256"]), "candidate manifest MSI SHA does not match GitHub evidence")
    need(exe[0][1] == str(github["windowsExeSha256"]), "candidate manifest EXE SHA does not match GitHub evidence")
    need(portable[0][1] == str(github["windowsPortableSha256"]), "candidate manifest portable SHA does not match GitHub evidence")
    return {"msi": msi[0][0], "exe": exe[0][0], "portable": portable[0][0]}


def verify_final_record(data: dict[str, Any], github: dict[str, Any], nested_windows_sha: str) -> None:
    need(data.get("schemaVersion") == 1, "final decision schemaVersion must be 1")
    need(data.get("scenario") == "myhomelib-7.1-final-external-acceptance", "final decision scenario mismatch")
    need(data.get("overall") == "PASS", "final decision is not PASS")
    backlog = data.get("backlogItems")
    need(isinstance(backlog, list) and set(map(str, backlog)) == EXPECTED_BACKLOG,
         "final decision backlog item set mismatch")
    evidence = data.get("evidence")
    need(isinstance(evidence, list), "final decision evidence must be a list")
    rows: dict[str, dict[str, Any]] = {}
    for row in evidence:
        need(isinstance(row, dict), "final decision evidence row must be an object")
        name = str(row.get("name") or "")
        need(name and name not in rows, f"final decision duplicate/blank evidence name: {name!r}")
        need(row.get("status") == "PASS", f"final decision evidence {name!r} is not PASS")
        rows[name] = row
    for name in ("github", "github-ingest", "harness-binding", "windows", "windows-archive", "candidate-binding"):
        need(name in rows, f"final decision missing evidence row {name!r}")

    gh = rows["github"].get("details") or {}
    gi = rows["github-ingest"].get("details") or {}
    need(gh.get("candidateSha") == github["candidateSha"], "final decision GitHub candidate SHA mismatch")
    need(gh.get("windowsMsiSha256") == github["windowsMsiSha256"], "final decision GitHub MSI SHA mismatch")
    need(gh.get("windowsExeSha256") == github["windowsExeSha256"], "final decision GitHub EXE SHA mismatch")
    need(gh.get("windowsPortableSha256") == github["windowsPortableSha256"], "final decision GitHub portable SHA mismatch")
    need(gi.get("candidateSha") == github["candidateSha"], "final decision GitHub ingest candidate SHA mismatch")
    need(gi.get("releaseRunId") == github["releaseRunId"], "final decision GitHub ingest release run mismatch")
    need(gi.get("remoteDigestVerified") is True, "final decision GitHub ingest is not remotely digest-verified")

    hb = rows["harness-binding"].get("details") or {}
    need(hb.get("candidateSha") == github["candidateSha"], "final decision harness binding candidate SHA mismatch")
    need(hb.get("manifestSha256") == github["acceptanceHarnessManifestSha256"], "final decision harness manifest SHA mismatch")

    win = rows["windows"].get("details") or {}
    need(win.get("currentMsiSha256") == github["windowsMsiSha256"], "final decision Windows root MSI SHA mismatch")
    need(win.get("exeSha256") == github["windowsExeSha256"], "final decision Windows root EXE SHA mismatch")
    need(win.get("portableSha256") == github["windowsPortableSha256"], "final decision Windows root portable SHA mismatch")
    session_id = str(win.get("acceptanceSessionId") or "")
    host_fp = str(win.get("hostFingerprintSha256") or "")
    user_fp = str(win.get("userFingerprintSha256") or "")
    need(session_id, "final decision Windows root acceptanceSessionId missing")
    need(SHA256_RE.fullmatch(host_fp) is not None, "final decision Windows root host fingerprint invalid")
    need(SHA256_RE.fullmatch(user_fp) is not None, "final decision Windows root user fingerprint invalid")

    wa = rows["windows-archive"].get("details") or {}
    need(wa.get("acceptanceSessionId") == session_id, "final decision Windows archive session mismatch")
    need(wa.get("hostFingerprintSha256") == host_fp, "final decision Windows archive host fingerprint mismatch")
    need(wa.get("userFingerprintSha256") == user_fp, "final decision Windows archive user fingerprint mismatch")

    cb = rows["candidate-binding"].get("details") or {}
    need(cb.get("acceptanceSessionId") == session_id, "final decision candidate binding session mismatch")
    need(cb.get("hostFingerprintSha256") == host_fp, "final decision candidate binding host fingerprint mismatch")
    need(cb.get("userFingerprintSha256") == user_fp, "final decision candidate binding user fingerprint mismatch")

    wa = rows["windows-archive"].get("details") or {}
    need(wa.get("sha256") == nested_windows_sha, "final decision nested Windows archive SHA mismatch")
    need(wa.get("currentMsiSha256") == github["windowsMsiSha256"], "final decision Windows archive MSI SHA mismatch")
    need(wa.get("exeSha256") == github["windowsExeSha256"], "final decision Windows archive EXE SHA mismatch")
    need(wa.get("portableSha256") == github["windowsPortableSha256"], "final decision Windows archive portable SHA mismatch")

    binding = rows["candidate-binding"].get("details") or {}
    for key in ("candidateSha", "windowsMsiSha256", "windowsExeSha256", "windowsPortableSha256", "releaseRunId", "releaseRunUrl", "acceptanceHarnessManifestSha256"):
        need(binding.get(key) == github[key], f"final decision candidate binding mismatch for {key}")
    need(binding.get("acceptanceRunId") == gi.get("acceptanceRunId"), "final decision candidate binding acceptance run id mismatch")
    need(binding.get("acceptanceRunUrl") == gi.get("acceptanceRunUrl"), "final decision candidate binding acceptance run URL mismatch")


def verify_bundle(bundle: Path) -> None:
    need(bundle.is_file(), f"final evidence bundle not found: {bundle}")
    sidecar = Path(str(bundle) + ".sha256")
    need(sidecar.is_file(), f"final evidence bundle sidecar not found: {sidecar}")
    outer_expected = parse_sidecar_text(sidecar.read_text(encoding="utf-8-sig"), bundle.name, str(sidecar))
    outer_actual = sha256_file(bundle)
    need(outer_actual == outer_expected, "final evidence bundle SHA-256 mismatch")

    try:
        zf = zipfile.ZipFile(bundle)
    except zipfile.BadZipFile as exc:
        raise BundleError(f"final evidence bundle is not a valid ZIP: {bundle}") from exc
    with zf:
        names = [n.replace("\\", "/") for n in zf.namelist() if not n.endswith("/")]
        need(names and len(names) == len(set(names)), "final evidence bundle is empty or contains duplicate names")
        for name in names:
            parts = Path(name).parts
            need(not name.startswith("/") and ".." not in parts, f"unsafe final evidence member: {name}")
        missing = REQUIRED_MEMBERS - set(names)
        need(not missing, "final evidence bundle missing required member(s): " + ", ".join(sorted(missing)))

        manifest = parse_manifest(zf.read("manifest.sha256").decode("utf-8-sig"), "outer manifest.sha256")
        payload_names = set(names) - {"manifest.sha256"}
        need(set(manifest) == payload_names, "outer manifest/member set mismatch")
        for name, digest in manifest.items():
            need(sha256_bytes(zf.read(name)) == digest, f"outer manifest checksum mismatch for {name}")

        final_mod = load_final_module()
        with tempfile.TemporaryDirectory(prefix="myhomelib-final-bundle-") as td:
            temp = Path(td)
            github_path = temp / "github-connected-acceptance.json"
            github_path.write_bytes(zf.read("github/github-connected-acceptance.json"))
            github_ev = final_mod.verify_github_json(github_path)
            github = github_ev.details
            ingest_path = temp / "github-connected-acceptance-ingest.json"
            ingest_path.write_bytes(zf.read("github/github-connected-acceptance-ingest.json"))
            ingest_ev = final_mod.verify_github_ingest(ingest_path, github_ev)
            harness_manifest_blob = zf.read("github/acceptance-harness.sha256")
            need(sha256_bytes(harness_manifest_blob) == github["acceptanceHarnessManifestSha256"],
                 "reviewer bundle acceptance harness manifest SHA does not match GitHub evidence")
            harness_manifest_path = temp / "acceptance-harness.sha256"
            harness_manifest_path.write_bytes(harness_manifest_blob)
            harness_binding_path = temp / "windows-harness-binding.json"
            harness_binding_path.write_bytes(zf.read("windows/windows-harness-binding.json"))
            final_mod.verify_harness_binding(harness_binding_path, github_ev, harness_manifest_path)
            host_binding_blob = zf.read("windows/windows-host-binding.json")
            host_binding = json.loads(host_binding_blob.decode("utf-8-sig"))
            need(host_binding.get("scenario") == "windows-acceptance-host-binding", "reviewer bundle Windows host binding scenario mismatch")
            need(host_binding.get("overall") == "PASS", "reviewer bundle Windows host binding is not PASS")
            need(host_binding.get("candidateSha") == github["candidateSha"], "reviewer bundle Windows host binding candidate SHA mismatch")
            need(host_binding.get("repository") == github["repository"], "reviewer bundle Windows host binding repository mismatch")
            need(host_binding.get("acceptanceRunId") == ingest_ev.details["acceptanceRunId"], "reviewer bundle Windows host binding acceptance run mismatch")
            need(SESSION_RE.fullmatch(str(host_binding.get("acceptanceSessionId") or "")) is not None,
                 "reviewer bundle Windows acceptance session id invalid")
            need(SHA256_RE.fullmatch(str(host_binding.get("hostFingerprintSha256") or "")) is not None,
                 "reviewer bundle Windows host fingerprint invalid")
            need(SHA256_RE.fullmatch(str(host_binding.get("userFingerprintSha256") or "")) is not None,
                 "reviewer bundle Windows user fingerprint invalid")
            verify_candidate_manifest(zf.read("github/candidate-windows.sha256").decode("utf-8-sig"), github)

            windows_path = temp / "windows-final-acceptance-evidence.zip"
            windows_path.write_bytes(zf.read("windows/windows-final-acceptance-evidence.zip"))
            nested_sidecar_text = zf.read("windows/windows-final-acceptance-evidence.zip.sha256").decode("utf-8-sig")
            nested_expected = parse_sidecar_text(nested_sidecar_text, windows_path.name, "nested Windows sidecar")
            nested_actual = sha256_file(windows_path)
            need(nested_actual == nested_expected, "nested Windows evidence ZIP SHA-256 mismatch")
            Path(str(windows_path) + ".sha256").write_text(nested_sidecar_text, encoding="utf-8")
            nested_windows_ev = final_mod.verify_windows_archive(
                windows_path,
                str(github["windowsMsiSha256"]),
                str(github["windowsPortableSha256"]),
                str(github["windowsExeSha256"]),
            )
            need(nested_windows_ev.details["acceptanceSessionId"] == host_binding["acceptanceSessionId"],
                 "reviewer bundle outer/nested Windows acceptance session mismatch")
            need(nested_windows_ev.details["hostFingerprintSha256"] == host_binding["hostFingerprintSha256"],
                 "reviewer bundle outer/nested Windows host fingerprint mismatch")
            need(nested_windows_ev.details["userFingerprintSha256"] == host_binding["userFingerprintSha256"],
                 "reviewer bundle outer/nested Windows user fingerprint mismatch")

            final_record = load_json_bytes(zf.read("final/v71-final-external-acceptance.json"), "final decision")
            verify_final_record(final_record, github, nested_actual)
            final_rows = {str(row.get("name") or ""): (row.get("details") or {}) for row in final_record["evidence"]}
            final_win = final_rows["windows"]
            need(final_win.get("acceptanceSessionId") == host_binding["acceptanceSessionId"],
                 "reviewer bundle outer host binding/final decision session mismatch")
            need(final_win.get("hostFingerprintSha256") == host_binding["hostFingerprintSha256"],
                 "reviewer bundle outer host binding/final decision host fingerprint mismatch")
            need(final_win.get("userFingerprintSha256") == host_binding["userFingerprintSha256"],
                 "reviewer bundle outer host binding/final decision user fingerprint mismatch")


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("bundle", nargs="?", type=Path, default=Path("target/myhomelib-7.1-final-external-evidence.zip"))
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    try:
        verify_bundle(args.bundle)
        print("MyHomeLib 7.1 final evidence bundle: PASS")
        return 0
    except (BundleError, OSError, zipfile.BadZipFile) as exc:
        print(f"MyHomeLib 7.1 final evidence bundle: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
