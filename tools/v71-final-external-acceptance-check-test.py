#!/usr/bin/env python3
"""Offline regression tests for the final 7.1 external evidence aggregator."""
from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import binascii
import struct
import zlib
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("v71final", HERE / "v71-final-external-acceptance-check.py")
assert SPEC and SPEC.loader
mod = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mod
SPEC.loader.exec_module(mod)

CANDIDATE_SHA = "a" * 40
MSI_SHA = hashlib.sha256(b"candidate-msi").hexdigest()
EXE_SHA = hashlib.sha256(b"candidate-exe").hexdigest()
PORTABLE_SHA = hashlib.sha256(b"candidate-portable").hexdigest()
HARNESS_MANIFEST = b"0" * 64 + b"  tools/example.ps1\n"
HARNESS_SHA = hashlib.sha256(HARNESS_MANIFEST).hexdigest()
SESSION_ID = "11111111-2222-4333-8444-555555555555"
HOST_FP = "6" * 64
USER_FP = "7" * 64
HOST_FIELDS = {
    "host": "WIN-ACCEPTANCE",
    "user": "acceptance-user",
    "acceptanceSessionId": SESSION_ID,
    "hostFingerprintSha256": HOST_FP,
    "userFingerprintSha256": USER_FP,
    "osVersion": "10.0.26100",
    "osBuild": "26100",
    "osArchitecture": "x64",
}
PROJECT_VERSION = "7.1.0"
SOURCE_TREE_SHA = "b" * 64


def candidate_integrity_fixture() -> tuple[bytes, bytes, bytes, str, str]:
    payloads = {
        f"MyHomeLib-{PROJECT_VERSION}.msi": b"candidate-msi",
        f"MyHomeLib-{PROJECT_VERSION}.exe": b"candidate-exe",
        f"myhomelib-{PROJECT_VERSION}-windows-amd64.zip": b"candidate-portable",
    }
    rows = []
    sums_lines = []
    aggregate = hashlib.sha256()
    for name, blob in sorted(payloads.items()):
        digest = hashlib.sha256(blob).hexdigest()
        row = {"path": name, "size": len(blob), "sha256": digest}
        rows.append(row)
        sums_lines.append(f"{digest}  {name}")
        aggregate.update(f"{digest}  {name}  {len(blob)}\n".encode())
    sums = ("\n".join(sums_lines) + "\n").encode()
    record = {
        "schemaVersion": 1,
        "scenario": "release-candidate-integrity",
        "overall": "PASS",
        "projectVersion": PROJECT_VERSION,
        "candidateSha": CANDIDATE_SHA,
        "platform": "windows",
        "sourceFileCount": 10,
        "sourceTreeSha256": SOURCE_TREE_SHA,
        "criticalPolicyFiles": [{"path": "pom.xml", "size": 1, "sha256": "c" * 64}],
        "distFileCount": len(rows),
        "distManifestSha256": aggregate.hexdigest(),
        "sha256sSha256": hashlib.sha256(sums).hexdigest(),
        "distFiles": rows,
    }
    record_bytes = (json.dumps(record, indent=2, sort_keys=True) + "\n").encode()
    record_sha = hashlib.sha256(record_bytes).hexdigest()
    sidecar = f"{record_sha}  release-candidate-integrity-windows.json\n".encode()
    return sums, record_bytes, sidecar, record_sha, aggregate.hexdigest()


RELEASE_SUMS, INTEGRITY_BYTES, INTEGRITY_SIDECAR, INTEGRITY_SHA, DIST_MANIFEST_SHA = candidate_integrity_fixture()
RELEASE_SUMS_SHA = hashlib.sha256(RELEASE_SUMS).hexdigest()


def expect_fail(fn, text: str) -> None:
    try:
        fn()
    except mod.FinalAcceptanceError as exc:
        assert text.lower() in str(exc).lower(), (text, str(exc))
    else:
        raise AssertionError(f"expected failure containing {text!r}")


def github_payload(status: str = "PASS") -> dict:
    return {
        "schemaVersion": 2,
        "scenario": "github-connected-acceptance",
        "candidateSha": CANDIDATE_SHA,
        "timestamp": "2026-09-06T10:00:00Z",
        "githubApiVersion": "2026-03-10",
        "repository": "owner/repo",
        "branch": "main",
        "overall": status,
        "acceptanceHarnessManifestSha256": HARNESS_SHA,
        "checks": [
            {"id": "MHL-010-A", "status": "PASS", "summary": "x", "details": {}},
            {"id": "MHL-010-B", "status": "PASS", "summary": "x", "details": {}},
            {
                "id": "MHL-017/MHL-018",
                "status": "PASS",
                "summary": "x",
                "details": {
                    "headSha": CANDIDATE_SHA,
                    "windowsMsiSha256": MSI_SHA,
                    "windowsExeSha256": EXE_SHA,
                    "windowsPortableSha256": PORTABLE_SHA,
                    "windowsChecksumsSha256": RELEASE_SUMS_SHA,
                    "windowsIntegrityProjectVersion": PROJECT_VERSION,
                    "windowsIntegrityCandidateSha": CANDIDATE_SHA,
                    "windowsIntegritySha256": INTEGRITY_SHA,
                    "windowsIntegritySourceTreeSha256": SOURCE_TREE_SHA,
                    "windowsIntegrityDistManifestSha256": DIST_MANIFEST_SHA,
                    "runId": 123,
                    "htmlUrl": "https://github.com/owner/repo/actions/runs/123",
                },
            },
            {"id": "MHL-019", "status": "PASS", "summary": "x", "details": {}},
        ],
    }


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload)) + kind + payload
        + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF)
    )


def make_png(seed: int) -> bytes:
    width, height = 800, 600
    pixel = bytes(((seed * 53) % 251, (seed * 97) % 251, (seed * 193) % 251))
    row = b"\x00" + pixel * width
    raw = row * height
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(raw, 9))
        + png_chunk(b"IEND", b"")
    )


def windows_archive_bytes(msi_sha: str = MSI_SHA, portable_sha: str = PORTABLE_SHA) -> bytes:
    files: dict[str, bytes] = {}
    host_binding = {
        "schemaVersion": 1,
        "scenario": "windows-acceptance-host-binding",
        "timestamp": "2026-09-06T17:59:00+03:00",
        "overall": "PASS",
        "acceptanceSessionId": SESSION_ID,
        "candidateSha": CANDIDATE_SHA,
        "repository": "owner/repo",
        "acceptanceRunId": 456,
        "host": HOST_FIELDS["host"],
        "user": HOST_FIELDS["user"],
        "os": "Microsoft Windows 11",
        "osVersion": HOST_FIELDS["osVersion"],
        "osBuild": HOST_FIELDS["osBuild"],
        "osArchitecture": HOST_FIELDS["osArchitecture"],
        "hostFingerprintSha256": HOST_FP,
        "userFingerprintSha256": USER_FP,
        "isAdministrator": False,
    }
    files["windows-host-binding/windows-host-binding.json"] = json.dumps(host_binding).encode()
    logs = []
    for index, action in enumerate(("install", "install", "install", "uninstall"), start=1):
        name = f"msiexec-{index:02d}-{action}.log"
        logs.append(name)
        files[f"windows-installer-acceptance/{name}"] = (
            f"MyHomeLib Windows Installer acceptance log {index}\n" * 4
        ).encode()
    installer = {
        "schemaVersion": 1,
        "scenario": "windows-installer-lifecycle",
        "timestamp": "2026-09-06T18:00:00+03:00",
        "host": HOST_FIELDS["host"],
        "os": "Microsoft Windows 11",
        "user": HOST_FIELDS["user"],
        "acceptanceSessionId": SESSION_ID,
        "hostFingerprintSha256": HOST_FP,
        "userFingerprintSha256": USER_FP,
        "osVersion": HOST_FIELDS["osVersion"],
        "osBuild": HOST_FIELDS["osBuild"],
        "osArchitecture": HOST_FIELDS["osArchitecture"],
        "isAdministrator": False,
        "requireStandardUser": True,
        "previousVersion": "7.0.0",
        "currentVersion": "7.1.0",
        "previousPackageSource": "external",
        "previousMsi": r"C:\acceptance\MyHomeLib-7.0.0.msi",
        "currentMsi": r"C:\acceptance\MyHomeLib-7.1.0.msi",
        "previousMsiSha256": hashlib.sha256(b"previous-msi").hexdigest(),
        "currentMsiSha256": msi_sha,
        "msiexecLogs": logs,
        "installPrevious": "PASS",
        "upgradeCurrent": "PASS",
        "repeatCurrent": "PASS",
        "uninstall": "PASS",
        "shortcutsRemoved": "PASS",
        "userDataPreserved": "PASS",
        "overall": "PASS",
        "note": "",
    }
    files["windows-installer-acceptance/installer-acceptance.json"] = json.dumps(installer).encode()
    files["windows-installer-acceptance/installer-acceptance.md"] = b"# Installer acceptance\n\nOverall: **PASS**\n"

    portable = {
        "schemaVersion": 1,
        "scenario": "windows-portable-unicode-smoke",
        "timestamp": "2026-09-06T18:01:00+03:00",
        "host": HOST_FIELDS["host"],
        "user": HOST_FIELDS["user"],
        "acceptanceSessionId": SESSION_ID,
        "hostFingerprintSha256": HOST_FP,
        "userFingerprintSha256": USER_FP,
        "osVersion": HOST_FIELDS["osVersion"],
        "osBuild": HOST_FIELDS["osBuild"],
        "osArchitecture": HOST_FIELDS["osArchitecture"],
        "os": "Microsoft Windows 11",
        "archive": r"C:\dist\myhomelib-7.1.0-windows-amd64.zip",
        "archiveSha256": portable_sha,
        "extractPath": r"C:\Temp\extract Моя бібліотека Ω 日本",
        "syntheticHome": r"C:\Temp\home Моя бібліотека Ω 日本",
        "workingDirectory": r"C:\Temp\cwd Моя бібліотека Ω 日本",
        "launcher": r"C:\Temp\extract Моя бібліотека Ω 日本\MyHomeLib\MyHomeLib.exe",
        "markerPresent": True,
        "launcherExitCode": 0,
        "portableDataCreated": True,
        "profileEnvironmentRedirected": True,
        "syntheticHomeWriteDetected": False,
        "workingDirectoryWriteDetected": False,
        "profileWriteDetected": False,
        "overall": "PASS",
        "note": "",
    }
    files["windows-portable-acceptance/portable-smoke.json"] = json.dumps(portable, ensure_ascii=False).encode()
    files["windows-portable-acceptance/portable-smoke.md"] = b"# Portable acceptance\n\nOverall: **PASS**\n"

    seed = 1
    for scale in (100, 125, 150, 200):
        results = [
            {"Id": "AUTO-0", "Check": "DPI", "Outcome": "PASS", "Note": "", "Evidence": ""},
            {"Id": "AUTO-1", "Check": "launcher", "Outcome": "PASS", "Note": "", "Evidence": ""},
        ]
        for i in range(1, 21):
            cid = f"P4-{i:02d}"
            name = f"dpi-{scale}-evidence/{scale}-{cid}.png"
            files[name] = make_png(seed)
            seed += 1
            results.append({
                "Id": cid,
                "Check": f"check {cid}",
                "Outcome": "PASS",
                "Note": "",
                "Evidence": f"dpi-{scale}-evidence\\{scale}-{cid}.png",
            })
        ui = {
            "schemaVersion": 1,
            "scenario": "windows-ui-dpi-acceptance",
            "timestamp": "2026-09-06 18:02:00 +03:00",
            "host": HOST_FIELDS["host"],
            "user": HOST_FIELDS["user"],
            "acceptanceSessionId": SESSION_ID,
            "hostFingerprintSha256": HOST_FP,
            "userFingerprintSha256": USER_FP,
            "osVersion": HOST_FIELDS["osVersion"],
            "osBuild": HOST_FIELDS["osBuild"],
            "osArchitecture": HOST_FIELDS["osArchitecture"],
            "os": "Microsoft Windows 11",
            "scale": scale,
            "observedDpi": f"{round(96 * scale / 100)} DPI (~{scale}%)",
            "observedDpiValue": round(96 * scale / 100),
            "monitorCount": 1,
            "launcher": r"C:\Users\acceptance-user\AppData\Local\MyHomeLib\MyHomeLib.exe",
            "overall": "PASS",
            "results": results,
        }
        files[f"windows-ui-acceptance-{scale}.json"] = json.dumps(ui).encode()
        files[f"windows-ui-acceptance-{scale}.md"] = f"# DPI {scale}\n\nOverall: **PASS**\n".encode()

    desktop_results = [
        {"Id": "AUTO-1", "Check": "launcher", "Outcome": "PASS", "Note": "post-install exit=0", "Evidence": ""},
    ]
    for i in range(1, 8):
        cid = f"P5-{i:02d}"
        name = f"windows-release-desktop-acceptance/evidence/desktop-{cid}.png"
        files[name] = make_png(seed)
        seed += 1
        note = f"fixture evidence for {cid}" if cid in {"P5-03", "P5-05", "P5-07"} else ""
        desktop_results.append({
            "Id": cid, "Check": f"desktop check {cid}", "Outcome": "PASS",
            "Note": note, "Evidence": f"evidence\\desktop-{cid}.png",
        })
    desktop = {
        "schemaVersion": 1,
        "scenario": "windows-release-desktop-acceptance",
        "timestamp": "2026-09-06 18:10:00 +03:00",
        "host": HOST_FIELDS["host"],
        "os": "Microsoft Windows 11",
        "user": HOST_FIELDS["user"],
        "acceptanceSessionId": SESSION_ID,
        "hostFingerprintSha256": HOST_FP,
        "userFingerprintSha256": USER_FP,
        "osVersion": HOST_FIELDS["osVersion"],
        "osBuild": HOST_FIELDS["osBuild"],
        "osArchitecture": HOST_FIELDS["osArchitecture"],
        "isAdministrator": False,
        "requireStandardUser": True,
        "candidateSha": CANDIDATE_SHA,
        "repository": "owner/repo",
        "releaseRunId": 123,
        "releaseRunUrl": "https://github.com/owner/repo/actions/runs/123",
        "exeInstaller": r"C:\acceptance\MyHomeLib-7.1.0.exe",
        "exeSha256": EXE_SHA,
        "launcher": r"C:\Users\acceptance-user\AppData\Local\MyHomeLib\MyHomeLib.exe",
        "previousVersion": "7.0.0",
        "overall": "PASS",
        "results": desktop_results,
    }
    files["windows-release-desktop-acceptance/desktop-acceptance.json"] = json.dumps(desktop).encode()
    files["windows-release-desktop-acceptance/desktop-acceptance.md"] = b"# Desktop release acceptance\n\nOverall: **PASS**\n"

    manifest = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in sorted(files.items()))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in files.items():
            zf.writestr(name, data)
        zf.writestr("manifest.sha256", manifest)
    return out.getvalue()


def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        good = root / "github.json"
        good.write_text(json.dumps(github_payload()), encoding="utf-8")
        gh = mod.verify_github_json(good)
        assert gh.status == "PASS"
        assert gh.details["windowsMsiSha256"] == MSI_SHA
        assert gh.details["windowsExeSha256"] == EXE_SHA
        assert gh.details["windowsPortableSha256"] == PORTABLE_SHA

        ingest_payload = {
            "schemaVersion": 1,
            "scenario": "github-connected-acceptance-artifact-ingest",
            "overall": "PASS",
            "candidateSha": CANDIDATE_SHA,
            "repository": "owner/repo",
            "releaseRunId": 123,
            "releaseRunUrl": "https://github.com/owner/repo/actions/runs/123",
            "artifactZipSha256": "b" * 64,
            "remoteDigestVerified": True,
            "acceptanceRunId": 456,
            "acceptanceRunUrl": "https://github.com/owner/repo/actions/runs/456",
            "githubArtifactId": 999,
            "githubArtifactName": "github-connected-acceptance-456-1",
            "githubDeclaredDigest": "sha256:" + "b" * 64,
            "windowsMsiSha256": MSI_SHA,
            "windowsExeSha256": EXE_SHA,
            "windowsPortableSha256": PORTABLE_SHA,
            "windowsChecksumsSha256": RELEASE_SUMS_SHA,
            "windowsIntegrityProjectVersion": PROJECT_VERSION,
            "windowsIntegrityCandidateSha": CANDIDATE_SHA,
            "windowsIntegritySha256": INTEGRITY_SHA,
            "windowsIntegritySourceTreeSha256": SOURCE_TREE_SHA,
            "windowsIntegrityDistManifestSha256": DIST_MANIFEST_SHA,
            "acceptanceHarnessManifestSha256": HARNESS_SHA,
        }
        ingest = root / "ingest.json"
        ingest.write_text(json.dumps(ingest_payload), encoding="utf-8")
        assert mod.verify_github_ingest(ingest, gh).status == "PASS"

        harness_binding_payload = {
            "schemaVersion": 1,
            "scenario": "windows-acceptance-harness-binding",
            "overall": "PASS",
            "candidateSha": CANDIDATE_SHA,
            "manifestSha256": HARNESS_SHA,
            "fileCount": 1,
            "files": [{"path": "tools/example.ps1", "sha256": "0" * 64}],
        }
        harness_binding = root / "harness-binding.json"
        harness_binding.write_text(json.dumps(harness_binding_payload), encoding="utf-8")
        assert mod.verify_harness_binding(harness_binding, gh).status == "PASS"
        harness_binding_payload["manifestSha256"] = "0" * 64
        harness_binding.write_text(json.dumps(harness_binding_payload), encoding="utf-8")
        expect_fail(lambda: mod.verify_harness_binding(harness_binding, gh), "manifest SHA")

        ingest_payload["remoteDigestVerified"] = False
        ingest.write_text(json.dumps(ingest_payload), encoding="utf-8")
        expect_fail(lambda: mod.verify_github_ingest(ingest, gh), "digest-verified ingest")

        old = root / "old.json"
        payload = github_payload()
        payload["schemaVersion"] = 1
        old.write_text(json.dumps(payload), encoding="utf-8")
        expect_fail(lambda: mod.verify_github_json(old), "schemaVersion must be 2")

        bad = root / "bad.json"
        payload = github_payload()
        payload["checks"] = payload["checks"][:-1]
        bad.write_text(json.dumps(payload), encoding="utf-8")
        expect_fail(lambda: mod.verify_github_json(bad), "missing required")

        mismatch = root / "mismatch.json"
        payload = github_payload()
        payload["checks"][2]["details"]["headSha"] = "b" * 40
        mismatch.write_text(json.dumps(payload), encoding="utf-8")
        expect_fail(lambda: mod.verify_github_json(mismatch), "does not match candidateSha")

        archive = root / "windows.zip"
        archive.write_bytes(windows_archive_bytes())
        digest = mod.sha256_file(archive)
        Path(str(archive) + ".sha256").write_text(f"{digest}  windows.zip\n", encoding="utf-8")
        wa = mod.verify_windows_archive(archive, MSI_SHA, PORTABLE_SHA, EXE_SHA)
        assert wa.status == "PASS" and wa.details["currentMsiSha256"] == MSI_SHA
        assert wa.details["exeSha256"] == EXE_SHA
        assert wa.details["acceptanceSessionId"] == SESSION_ID
        assert wa.details["hostFingerprintSha256"] == HOST_FP

        expect_fail(lambda: mod.verify_windows_archive(archive, "0" * 64, PORTABLE_SHA, EXE_SHA), "does not match")
        expect_fail(lambda: mod.verify_windows_archive(archive, MSI_SHA, "0" * 64, EXE_SHA), "portable SHA-256 does not match")
        expect_fail(lambda: mod.verify_windows_archive(archive, MSI_SHA, PORTABLE_SHA, "0" * 64), "EXE SHA-256 does not match")

        # Even a manifest-consistent extra file must be rejected: reviewer evidence is a closed set.
        extra_archive = root / "windows-extra.zip"
        with zipfile.ZipFile(archive) as src:
            payload = {name: src.read(name) for name in src.namelist() if name != "manifest.sha256"}
        payload["windows-installer-acceptance/unreferenced-secret.txt"] = b"must-not-be-bundled"
        extra_manifest = "".join(
            f"{hashlib.sha256(blob).hexdigest()}  {name}\n" for name, blob in sorted(payload.items())
        )
        with zipfile.ZipFile(extra_archive, "w", zipfile.ZIP_DEFLATED) as zf:
            for name, blob in payload.items():
                zf.writestr(name, blob)
            zf.writestr("manifest.sha256", extra_manifest)
        extra_digest = mod.sha256_file(extra_archive)
        Path(str(extra_archive) + ".sha256").write_text(
            f"{extra_digest}  {extra_archive.name}\n", encoding="utf-8"
        )
        expect_fail(lambda: mod.verify_windows_archive(extra_archive, MSI_SHA, PORTABLE_SHA, EXE_SHA), "unreferenced member")

        Path(str(archive) + ".sha256").write_text(f"{'0'*64}  windows.zip\n", encoding="utf-8")
        expect_fail(lambda: mod.verify_windows_archive(archive), "mismatch")

        out = root / "out"
        rc = mod.main(["--github-only", "--github-json", str(good), "--out-dir", str(out)])
        assert rc == 0
        result = json.loads((out / "v71-final-external-acceptance.json").read_text(encoding="utf-8"))
        assert result["overall"] == "PASS"

    print("7.1 final external acceptance regression tests: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
