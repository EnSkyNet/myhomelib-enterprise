#!/usr/bin/env python3
"""Cross-platform regression tests for the Windows evidence validator."""
from __future__ import annotations

import binascii
import importlib.util
import json
import struct
import tempfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "windows-acceptance-evidence-check.py"
spec = importlib.util.spec_from_file_location("windows_acceptance_evidence_check", MODULE_PATH)
assert spec and spec.loader
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

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


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF)
    )


def make_png(width: int = 800, height: int = 600, seed: int = 1) -> bytes:
    # Uniform RGB screenshots compress to a few KiB but remain realistic in dimensions.
    pixel = bytes(((seed * 53) % 251, (seed * 97) % 251, (seed * 193) % 251))
    row = b"\x00" + pixel * width
    raw = row * height
    return (
        mod.PNG_SIGNATURE
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(raw, 9))
        + png_chunk(b"IEND", b"")
    )


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def build_bundle(root: Path, previous_source: str = "external") -> None:
    write_json(root / "windows-host-binding" / "windows-host-binding.json", {
        "schemaVersion": 1,
        "scenario": "windows-acceptance-host-binding",
        "timestamp": "2026-09-06T17:59:00+03:00",
        "overall": "PASS",
        "acceptanceSessionId": SESSION_ID,
        "candidateSha": "a" * 40,
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
    })
    installer_dir = root / "windows-installer-acceptance"
    installer_dir.mkdir(parents=True, exist_ok=True)
    logs = []
    for index, action in enumerate(("install", "install", "install", "uninstall"), start=1):
        name = f"msiexec-{index:02d}-{action}.log"
        (installer_dir / name).write_text(
            f"MyHomeLib Windows Installer acceptance log {index}\n" * 4,
            encoding="utf-8",
        )
        logs.append(name)

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
        "previousPackageSource": previous_source,
        "previousMsi": r"C:\acceptance\MyHomeLib-7.0.0.msi",
        "currentMsi": r"C:\acceptance\MyHomeLib-7.1.0.msi",
        "previousMsiSha256": "1" * 64,
        "currentMsiSha256": "2" * 64,
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
    write_json(installer_dir / "installer-acceptance.json", installer)

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
        "archiveSha256": "3" * 64,
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
    write_json(root / "windows-portable-acceptance" / "portable-smoke.json", portable)

    seed = 1
    for scale in mod.DPI_SCALES:
        evidence_dir = root / f"dpi-{scale}-evidence"
        evidence_dir.mkdir(parents=True, exist_ok=True)
        results = [
            {"Id": "AUTO-0", "Check": "DPI", "Outcome": "PASS", "Note": "", "Evidence": ""},
            {"Id": "AUTO-1", "Check": "launcher", "Outcome": "PASS", "Note": "", "Evidence": ""},
        ]
        for cid in mod.P4_IDS:
            png = evidence_dir / f"{scale}-{cid}.png"
            png.write_bytes(make_png(seed=seed))
            seed += 1
            results.append(
                {
                    "Id": cid,
                    "Check": f"check {cid}",
                    "Outcome": "PASS",
                    "Note": "",
                    "Evidence": f"dpi-{scale}-evidence\\{scale}-{cid}.png",
                }
            )
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
        write_json(root / f"windows-ui-acceptance-{scale}.json", ui)

    desktop_dir = root / "windows-release-desktop-acceptance"
    evidence_dir = desktop_dir / "evidence"
    evidence_dir.mkdir(parents=True, exist_ok=True)
    desktop_results = [
        {"Id": "AUTO-1", "Check": "launcher", "Outcome": "PASS", "Note": "post-install exit=0", "Evidence": ""},
    ]
    for cid in mod.P5_IDS:
        png = evidence_dir / f"desktop-{cid}.png"
        png.write_bytes(make_png(seed=seed))
        seed += 1
        desktop_results.append({
            "Id": cid,
            "Check": f"desktop {cid}",
            "Outcome": "PASS",
            "Note": f"evidence for {cid}" if cid in {"P5-03", "P5-05", "P5-07"} else "",
            "Evidence": f"evidence\\desktop-{cid}.png",
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
        "candidateSha": "a" * 40,
        "repository": "owner/repo",
        "releaseRunId": 123,
        "releaseRunUrl": "https://github.com/owner/repo/actions/runs/123",
        "exeInstaller": r"C:\acceptance\MyHomeLib-7.1.0.exe",
        "exeSha256": "4" * 64,
        "launcher": r"C:\Users\acceptance-user\AppData\Local\MyHomeLib\MyHomeLib.exe",
        "previousVersion": "7.0.0",
        "overall": "PASS",
        "results": desktop_results,
    }
    write_json(desktop_dir / "desktop-acceptance.json", desktop)


def expect_failure(label: str, action) -> None:
    try:
        action()
    except AssertionError:
        return
    raise AssertionError(f"negative regression did not fail closed: {label}")


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        mod.verify_installer(root, require_standard=True, require_real_previous=True)
        mod.verify_portable(root)
        mod.verify_dpi(root)
        desktop = mod.verify_release_desktop(root, "4" * 64)
        assert desktop["overall"] == "PASS"
        binding = mod.verify_host_cohesion(root, require_dpi=True, require_desktop=True)
        assert binding["acceptanceSessionId"] == SESSION_ID

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-installer-acceptance" / "installer-acceptance.json"
        data = load_json(path)
        data["requireStandardUser"] = False
        write_json(path, data)
        expect_failure("standard-user guard missing", lambda: mod.verify_installer(root, True, False))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root, previous_source="synthetic")
        expect_failure("synthetic previous accepted as final", lambda: mod.verify_installer(root, True, True))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        (root / "windows-installer-acceptance" / "msiexec-02-install.log").unlink()
        expect_failure("missing msiexec evidence log", lambda: mod.verify_installer(root, True, True))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-portable-acceptance" / "portable-smoke.json"
        data = load_json(path)
        data["extractPath"] = r"C:\Temp\МояБібліотекаΩ日本"
        write_json(path, data)
        expect_failure("portable path without spaces", lambda: mod.verify_portable(root))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-portable-acceptance" / "portable-smoke.json"
        data = load_json(path)
        data["workingDirectoryWriteDetected"] = True
        data["profileWriteDetected"] = True
        write_json(path, data)
        expect_failure("portable cwd write accepted", lambda: mod.verify_portable(root))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-ui-acceptance-125.json"
        data = load_json(path)
        data["scale"] = 100
        write_json(path, data)
        expect_failure("embedded DPI scale mismatch", lambda: mod.verify_dpi(root))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-ui-acceptance-150.json"
        data = load_json(path)
        p4 = next(row for row in data["results"] if row["Id"] == "P4-01")
        data["results"].append(dict(p4))
        write_json(path, data)
        expect_failure("duplicate P4 row", lambda: mod.verify_dpi(root))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-ui-acceptance-200.json"
        data = load_json(path)
        p401 = next(row for row in data["results"] if row["Id"] == "P4-01")
        p402 = next(row for row in data["results"] if row["Id"] == "P4-02")
        original = root / p401["Evidence"].replace("\\", "/")
        duplicate = root / p402["Evidence"].replace("\\", "/")
        duplicate.write_bytes(original.read_bytes())
        write_json(path, data)
        expect_failure("duplicate screenshot content", lambda: mod.verify_dpi(root))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-ui-acceptance-100.json"
        data = load_json(path)
        p401 = next(row for row in data["results"] if row["Id"] == "P4-01")
        evidence = root / p401["Evidence"].replace("\\", "/")
        evidence.write_bytes(make_png(width=1, height=1, seed=250))
        expect_failure("implausibly small screenshot", lambda: mod.verify_dpi(root))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-ui-acceptance-100.json"
        data = load_json(path)
        p401 = next(row for row in data["results"] if row["Id"] == "P4-01")
        p401["Evidence"] = r"..\outside.png"
        write_json(path, data)
        (root.parent / "outside.png").write_bytes(make_png(seed=251))
        try:
            expect_failure("evidence path escaped bundle", lambda: mod.verify_dpi(root))
        finally:
            (root.parent / "outside.png").unlink(missing_ok=True)

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-release-desktop-acceptance" / "desktop-acceptance.json"
        data = load_json(path)
        data["isAdministrator"] = True
        write_json(path, data)
        expect_failure("desktop admin user accepted", lambda: mod.verify_release_desktop(root, "4" * 64))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-release-desktop-acceptance" / "desktop-acceptance.json"
        data = load_json(path)
        next(row for row in data["results"] if row["Id"] == "P5-03")["Note"] = ""
        write_json(path, data)
        expect_failure("desktop migration note missing", lambda: mod.verify_release_desktop(root, "4" * 64))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        expect_failure("desktop EXE binding mismatch", lambda: mod.verify_release_desktop(root, "5" * 64))

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-ui-acceptance-150.json"
        data = load_json(path)
        data["hostFingerprintSha256"] = "8" * 64
        write_json(path, data)
        expect_failure(
            "mixed Windows host evidence accepted",
            lambda: mod.verify_host_cohesion(root, require_dpi=True, require_desktop=True),
        )

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_bundle(root)
        path = root / "windows-portable-acceptance" / "portable-smoke.json"
        data = load_json(path)
        data["acceptanceSessionId"] = "99999999-2222-4333-8444-555555555555"
        write_json(path, data)
        expect_failure(
            "mixed Windows acceptance session accepted",
            lambda: mod.verify_host_cohesion(root, require_dpi=False, require_desktop=False),
        )

    print("Windows acceptance evidence validator regression tests: PASS")


if __name__ == "__main__":
    main()
