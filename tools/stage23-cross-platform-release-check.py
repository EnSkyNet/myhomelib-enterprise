#!/usr/bin/env python3
"""Validate final MyHomeLib release artifacts without executing the desktop UI."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import sys
import tarfile
import zipfile
import xml.etree.ElementTree as ET

SHA_LINE = re.compile(r"^([0-9a-fA-F]{64})  (.+)$")


def project_version() -> str:
    root = ET.parse("pom.xml").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    value = root.findtext("m:version", namespaces=ns)
    if not value or not value.strip():
        raise RuntimeError("Cannot determine project version from pom.xml")
    return value.strip()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_relative_name(name: str) -> bool:
    normalized = name.replace("\\", "/")
    path = PurePosixPath(normalized)
    return bool(normalized) and not path.is_absolute() and ".." not in path.parts


def verify_checksums(dist: Path, required: bool) -> int:
    sums = dist / "SHA256SUMS"
    if not sums.is_file():
        if required:
            raise RuntimeError("SHA256SUMS is required but missing")
        print("WARN: SHA256SUMS is absent")
        return 0

    checked = 0
    seen: set[str] = set()
    for line_no, raw in enumerate(sums.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not raw.strip():
            continue
        match = SHA_LINE.match(raw.strip())
        if not match:
            raise RuntimeError(f"Invalid SHA256SUMS line {line_no}: {raw!r}")
        expected, name = match.groups()
        name = name.replace("\\", "/")
        if not safe_relative_name(name):
            raise RuntimeError(f"Unsafe checksum path on line {line_no}: {name!r}")
        if name in seen:
            raise RuntimeError(f"Duplicate checksum entry: {name}")
        seen.add(name)
        target = dist / Path(*PurePosixPath(name).parts)
        if not target.is_file():
            raise RuntimeError(f"Checksum target is missing: {name}")
        actual = sha256(target)
        if actual.lower() != expected.lower():
            raise RuntimeError(f"Checksum mismatch for {name}: expected {expected}, got {actual}")
        checked += 1
    if checked == 0:
        raise RuntimeError("SHA256SUMS contains no file entries")
    print(f"PASS checksums: {checked} file(s)")
    return checked


def archive_members(path: Path) -> list[str]:
    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path) as archive:
            bad = archive.testzip()
            if bad is not None:
                raise RuntimeError(f"Corrupt ZIP member {bad} in {path.name}")
            return [name.replace("\\", "/") for name in archive.namelist()]
    if path.name.endswith(".tar.gz") or path.suffix.lower() in {".tgz", ".tar"}:
        with tarfile.open(path, "r:*") as archive:
            return [member.name.replace("\\", "/") for member in archive.getmembers()]
    return []


def verify_portable_archives(dist: Path, version: str) -> int:
    archives = sorted(list(dist.glob(f"myhomelib-{version}-*.zip")) + list(dist.glob(f"myhomelib-{version}-*.tar.gz")))
    for archive in archives:
        if archive.stat().st_size <= 0:
            raise RuntimeError(f"Portable archive is empty: {archive.name}")
        members = archive_members(archive)
        if not members:
            raise RuntimeError(f"Portable archive has no entries: {archive.name}")
        lower = [name.lower() for name in members]
        launcher_names = [name for name in lower if name.endswith("/bin/myhomelib")
                          or name.endswith("/myhomelib.exe")
                          or name.endswith("/contents/macos/myhomelib")]
        has_launcher = bool(launcher_names)
        has_runtime = any("/runtime/" in name or name.endswith("/runtime") for name in lower)
        if not has_launcher:
            raise RuntimeError(f"Portable archive does not contain the MyHomeLib launcher: {archive.name}")
        expected_markers = {str(PurePosixPath(name).parent / "myhomelib2.ini") for name in launcher_names}
        if not any(marker in lower for marker in expected_markers):
            raise RuntimeError(
                f"Portable archive does not contain myhomelib2.ini beside its launcher: {archive.name}"
            )
        if not has_runtime:
            raise RuntimeError(f"Portable archive does not contain a bundled Java runtime: {archive.name}")
        print(f"PASS portable archive: {archive.name} ({len(members)} entries)")
    return len(archives)


def verify_app_image(dist: Path) -> bool:
    candidates = [
        dist / "MyHomeLib" / "bin" / "MyHomeLib",
        dist / "MyHomeLib" / "MyHomeLib.exe",
        dist / "MyHomeLib.app" / "Contents" / "MacOS" / "MyHomeLib",
    ]
    launcher = next((path for path in candidates if path.is_file()), None)
    if launcher is None:
        return False
    image = launcher.parents[1] if launcher.name == "MyHomeLib" and launcher.parent.name == "bin" else launcher.parent
    if "Contents" in launcher.parts:
        app_index = launcher.parts.index("MyHomeLib.app")
        image = Path(*launcher.parts[: app_index + 1])
    runtime_candidates = [image / "lib" / "runtime", image / "runtime", image / "Contents" / "runtime"]
    if not any(path.is_dir() for path in runtime_candidates):
        raise RuntimeError(f"Packaged app-image has no bundled runtime near {launcher}")
    print(f"PASS app-image launcher/runtime: {launcher}")
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dist", default="dist")
    parser.add_argument("--require-checksums", action="store_true")
    parser.add_argument("--expect-installer", action="store_true")
    parser.add_argument("--expect-windows-msi", action="store_true")
    parser.add_argument("--expect-windows-exe", action="store_true")
    parser.add_argument("--require-portable", action="store_true")
    args = parser.parse_args()

    dist = Path(args.dist).resolve()
    if not dist.is_dir():
        raise RuntimeError(f"dist directory not found: {dist}")
    version = project_version()
    print(f"Validating MyHomeLib {version} artifacts in {dist}")

    bootstrap = dist / f"myhomelib-bootstrap-{version}.jar"
    if bootstrap.exists() and bootstrap.stat().st_size <= 0:
        raise RuntimeError(f"Bootstrap JAR is empty: {bootstrap}")
    mcp = dist / f"myhomelib-mcp-{version}.jar"
    if mcp.exists() and mcp.stat().st_size <= 0:
        raise RuntimeError(f"MCP JAR is empty: {mcp}")

    app_image = verify_app_image(dist)
    portable_count = verify_portable_archives(dist, version)
    if args.require_portable and portable_count == 0:
        raise RuntimeError("No versioned portable archive found")

    if args.expect_installer:
        installers = [p for p in dist.iterdir() if p.is_file() and p.suffix.lower() in {".exe", ".msi", ".pkg", ".dmg", ".deb", ".rpm"}]
        if not installers:
            raise RuntimeError("Native installer was expected but none was found")
        for installer in installers:
            if installer.stat().st_size <= 0:
                raise RuntimeError(f"Installer is empty: {installer.name}")
        print("PASS installer(s): " + ", ".join(p.name for p in installers))

    if args.expect_windows_msi:
        msi = dist / f"MyHomeLib-{version}.msi"
        if not msi.is_file() or msi.stat().st_size <= 0:
            raise RuntimeError(f"Windows MSI release candidate is required but missing/empty: {msi.name}")
        print(f"PASS Windows MSI candidate: {msi.name}")

    if args.expect_windows_exe:
        exe = dist / f"MyHomeLib-{version}.exe"
        if not exe.is_file() or exe.stat().st_size <= 0:
            raise RuntimeError(f"Windows EXE release candidate is required but missing/empty: {exe.name}")
        print(f"PASS Windows EXE candidate: {exe.name}")

    if not app_image and portable_count == 0 and not bootstrap.is_file():
        raise RuntimeError("No desktop app-image, portable archive, or bootstrap JAR found")

    verify_checksums(dist, args.require_checksums)
    print("RELEASE ARTIFACT VALIDATION: PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"RELEASE ARTIFACT VALIDATION: FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
