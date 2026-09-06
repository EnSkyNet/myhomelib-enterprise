#!/usr/bin/env python3
"""Offline regression for the immutable 7.1 reviewer evidence bundle."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


bundle_mod = load("v71bundle", HERE / "v71-final-evidence-bundle-check.py")
fixture_mod = load("v71fixture", HERE / "v71-final-external-acceptance-check-test.py")
final_mod = load("v71final_for_fixture", HERE / "v71-final-external-acceptance-check.py")


def make_bundle(root: Path) -> Path:
    gh_payload = fixture_mod.github_payload()
    gh_bytes = json.dumps(gh_payload, indent=2).encode()
    gh_path = root / "gh.json"
    gh_path.write_bytes(gh_bytes)
    gh_ev = final_mod.verify_github_json(gh_path)
    gh = gh_ev.details
    ingest = {
        "schemaVersion": 1,
        "scenario": "github-connected-acceptance-artifact-ingest",
        "overall": "PASS",
        "candidateSha": gh["candidateSha"],
        "repository": gh["repository"],
        "releaseRunId": gh["releaseRunId"],
        "releaseRunUrl": gh["releaseRunUrl"],
        "artifactZipSha256": "b" * 64,
        "remoteDigestVerified": True,
        "acceptanceRunId": 456,
        "acceptanceRunUrl": "https://github.com/owner/repo/actions/runs/456",
        "githubArtifactId": 999,
        "githubArtifactName": "github-connected-acceptance-456-1",
        "githubDeclaredDigest": "sha256:" + "b" * 64,
        "windowsMsiSha256": gh["windowsMsiSha256"],
        "windowsExeSha256": gh["windowsExeSha256"],
        "windowsPortableSha256": gh["windowsPortableSha256"],
        "acceptanceHarnessManifestSha256": gh["acceptanceHarnessManifestSha256"],
    }
    ingest_bytes = json.dumps(ingest, indent=2).encode()
    ingest_path = root / "ingest.json"
    ingest_path.write_bytes(ingest_bytes)
    ingest_ev = final_mod.verify_github_ingest(ingest_path, gh_ev)
    harness_binding = {
        "schemaVersion": 1,
        "scenario": "windows-acceptance-harness-binding",
        "overall": "PASS",
        "candidateSha": gh["candidateSha"],
        "manifestSha256": gh["acceptanceHarnessManifestSha256"],
        "fileCount": 1,
        "files": [{"path": "tools/example.ps1", "sha256": "0" * 64}],
    }
    harness_binding_bytes = json.dumps(harness_binding, indent=2).encode()
    harness_binding_path = root / "harness-binding.json"
    harness_binding_path.write_bytes(harness_binding_bytes)
    harness_ev = final_mod.verify_harness_binding(harness_binding_path, gh_ev)
    host_binding = {
        "schemaVersion": 1,
        "scenario": "windows-acceptance-host-binding",
        "timestamp": "2026-09-06T17:59:00+03:00",
        "overall": "PASS",
        "acceptanceSessionId": fixture_mod.SESSION_ID,
        "candidateSha": gh["candidateSha"],
        "repository": gh["repository"],
        "acceptanceRunId": ingest_ev.details["acceptanceRunId"],
        "host": fixture_mod.HOST_FIELDS["host"],
        "user": fixture_mod.HOST_FIELDS["user"],
        "os": "Microsoft Windows 11",
        "osVersion": fixture_mod.HOST_FIELDS["osVersion"],
        "osBuild": fixture_mod.HOST_FIELDS["osBuild"],
        "osArchitecture": fixture_mod.HOST_FIELDS["osArchitecture"],
        "hostFingerprintSha256": fixture_mod.HOST_FP,
        "userFingerprintSha256": fixture_mod.USER_FP,
        "isAdministrator": False,
    }
    host_binding_bytes = json.dumps(host_binding, indent=2).encode()

    windows = root / "windows-final-acceptance-evidence.zip"
    windows.write_bytes(fixture_mod.windows_archive_bytes())
    windows_sha = hashlib.sha256(windows.read_bytes()).hexdigest()
    windows_sidecar = f"{windows_sha}  {windows.name}\n".encode()
    Path(str(windows) + ".sha256").write_bytes(windows_sidecar)
    final_mod.verify_windows_archive(
        windows, gh["windowsMsiSha256"], gh["windowsPortableSha256"], gh["windowsExeSha256"]
    )

    candidate_manifest = (
        f"{gh['windowsMsiSha256']}  MyHomeLib-7.1.0.msi\n"
        f"{gh['windowsExeSha256']}  MyHomeLib-7.1.0.exe\n"
        f"{gh['windowsPortableSha256']}  myhomelib-7.1.0-windows-amd64.zip\n"
    ).encode()
    decision = {
        "schemaVersion": 1,
        "scenario": "myhomelib-7.1-final-external-acceptance",
        "timestamp": "2026-09-06T18:30:00Z",
        "overall": "PASS",
        "backlogItems": ["MHL-010", "MHL-011", "MHL-012", "MHL-017", "MHL-018", "MHL-019"],
        "evidence": [
            {"name": "github", "status": "PASS", "details": dict(gh)},
            {"name": "github-ingest", "status": "PASS", "details": dict(ingest_ev.details)},
            {"name": "harness-binding", "status": "PASS", "details": dict(harness_ev.details)},
            {"name": "windows", "status": "PASS", "details": {
                "currentMsiSha256": gh["windowsMsiSha256"],
                "exeSha256": gh["windowsExeSha256"],
                "portableSha256": gh["windowsPortableSha256"],
                "acceptanceSessionId": fixture_mod.SESSION_ID,
                "hostFingerprintSha256": fixture_mod.HOST_FP,
                "userFingerprintSha256": fixture_mod.USER_FP
            }},
            {"name": "windows-archive", "status": "PASS", "details": {
                "sha256": windows_sha,
                "currentMsiSha256": gh["windowsMsiSha256"],
                "exeSha256": gh["windowsExeSha256"],
                "portableSha256": gh["windowsPortableSha256"],
                "acceptanceSessionId": fixture_mod.SESSION_ID,
                "hostFingerprintSha256": fixture_mod.HOST_FP,
                "userFingerprintSha256": fixture_mod.USER_FP,
            }},
            {"name": "candidate-binding", "status": "PASS", "details": {
                "candidateSha": gh["candidateSha"],
                "windowsMsiSha256": gh["windowsMsiSha256"],
                "windowsExeSha256": gh["windowsExeSha256"],
                "windowsPortableSha256": gh["windowsPortableSha256"],
                "releaseRunId": gh["releaseRunId"],
                "releaseRunUrl": gh["releaseRunUrl"],
                "acceptanceRunId": ingest_ev.details["acceptanceRunId"],
                "acceptanceRunUrl": ingest_ev.details["acceptanceRunUrl"],
                "acceptanceHarnessManifestSha256": gh["acceptanceHarnessManifestSha256"],
                "acceptanceSessionId": fixture_mod.SESSION_ID,
                "hostFingerprintSha256": fixture_mod.HOST_FP,
                "userFingerprintSha256": fixture_mod.USER_FP,
            }},
        ],
        "failure": None,
    }
    files = {
        "github/github-connected-acceptance.json": gh_bytes,
        "github/github-connected-acceptance.md": b"# GitHub connected acceptance\n\nOverall: **PASS**\n",
        "github/github-connected-acceptance-ingest.json": ingest_bytes,
        "github/acceptance-harness.sha256": fixture_mod.HARNESS_MANIFEST,
        "github/candidate-windows.sha256": candidate_manifest,
        "windows/windows-harness-binding.json": harness_binding_bytes,
        "windows/windows-host-binding.json": host_binding_bytes,
        "windows/windows-final-acceptance-evidence.zip": windows.read_bytes(),
        "windows/windows-final-acceptance-evidence.zip.sha256": windows_sidecar,
        "final/v71-final-external-acceptance.json": json.dumps(decision, indent=2).encode(),
        "final/v71-final-external-acceptance.md": b"# Final external acceptance\n\nOverall: **PASS**\n",
    }
    manifest = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in sorted(files.items())).encode()
    bundle = root / "myhomelib-7.1-final-external-evidence.zip"
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in files.items():
            zf.writestr(name, data)
        zf.writestr("manifest.sha256", manifest)
    digest = hashlib.sha256(bundle.read_bytes()).hexdigest()
    Path(str(bundle) + ".sha256").write_text(f"{digest}  {bundle.name}\n", encoding="utf-8")
    return bundle


def expect_fail(path: Path) -> None:
    assert bundle_mod.main([str(path)]) == 1


def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        good = make_bundle(root)
        assert bundle_mod.main([str(good)]) == 0

        # Outer sidecar tampering must fail.
        sidecar = Path(str(good) + ".sha256")
        original = sidecar.read_text()
        sidecar.write_text(f"{'0'*64}  {good.name}\n", encoding="utf-8")
        expect_fail(good)
        sidecar.write_text(original, encoding="utf-8")

        # Candidate manifest tampering with a recomputed outer ZIP/manifest must still fail semantic binding.
        tampered = root / "tampered.zip"
        with zipfile.ZipFile(good) as src:
            data = {n: src.read(n) for n in src.namelist() if n != "manifest.sha256"}
        data["github/candidate-windows.sha256"] = (
            f"{'0'*64}  MyHomeLib-7.1.0.msi\n"
            f"{fixture_mod.EXE_SHA}  MyHomeLib-7.1.0.exe\n"
            f"{fixture_mod.PORTABLE_SHA}  myhomelib-7.1.0-windows-amd64.zip\n"
        ).encode()
        manifest = "".join(f"{hashlib.sha256(blob).hexdigest()}  {name}\n" for name, blob in sorted(data.items())).encode()
        with zipfile.ZipFile(tampered, "w", zipfile.ZIP_DEFLATED) as zf:
            for name, blob in data.items():
                zf.writestr(name, blob)
            zf.writestr("manifest.sha256", manifest)
        tdigest = hashlib.sha256(tampered.read_bytes()).hexdigest()
        Path(str(tampered) + ".sha256").write_text(f"{tdigest}  {tampered.name}\n", encoding="utf-8")
        expect_fail(tampered)

        # Host/session binding tampering must fail even when the outer ZIP/manifest are recomputed.
        tampered_host = root / "tampered-host.zip"
        with zipfile.ZipFile(good) as src:
            data = {n: src.read(n) for n in src.namelist() if n != "manifest.sha256"}
        bad_host = json.loads(data["windows/windows-host-binding.json"].decode())
        bad_host["hostFingerprintSha256"] = "9" * 64
        data["windows/windows-host-binding.json"] = json.dumps(bad_host, indent=2).encode()
        manifest = "".join(f"{hashlib.sha256(blob).hexdigest()}  {name}\n" for name, blob in sorted(data.items())).encode()
        with zipfile.ZipFile(tampered_host, "w", zipfile.ZIP_DEFLATED) as zf:
            for name, blob in data.items():
                zf.writestr(name, blob)
            zf.writestr("manifest.sha256", manifest)
        hdigest = hashlib.sha256(tampered_host.read_bytes()).hexdigest()
        Path(str(tampered_host) + ".sha256").write_text(f"{hdigest}  {tampered_host.name}\n", encoding="utf-8")
        expect_fail(tampered_host)

    print("7.1 final evidence bundle regression tests: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
