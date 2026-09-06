#!/usr/bin/env python3
"""Offline release sanity checks that require only Python 3.

This is intentionally not a replacement for `./mvnw clean verify`: it catches
broken XML/FXML references and validates the raw SQLite migration chain in an
empty file-backed database when Maven dependencies are not available yet.
"""
from __future__ import annotations

import os
import re
import sqlite3
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def xml_checks() -> tuple[int, list[str]]:
    files = sorted(ROOT.rglob("pom.xml")) + sorted((ROOT / "myhomelib-ui/src/main/resources").rglob("*.fxml"))
    errors: list[str] = []
    for path in files:
        try:
            ET.parse(path)
        except Exception as exc:  # noqa: BLE001 - report exact parser failure
            errors.append(f"{path.relative_to(ROOT)}: {exc}")
    return len(files), errors


def fxml_handler_checks() -> tuple[int, int, list[str]]:
    fxml_files = sorted((ROOT / "myhomelib-ui/src/main/resources/view").glob("*.fxml"))
    handlers: list[tuple[Path, str]] = []
    pattern = re.compile(r'\bon[A-Za-z]+\s*=\s*["\']#([A-Za-z_][A-Za-z0-9_]*)["\']')
    for path in fxml_files:
        text = path.read_text(encoding="utf-8")
        handlers.extend((path, name) for name in pattern.findall(text))

    # Static check only: runtime controller injection still belongs to Maven/JavaFX smoke tests.
    java_text = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in (ROOT / "myhomelib-ui/src/main/java").rglob("*.java")
    )
    missing = []
    for path, name in handlers:
        if not re.search(rf"\b{re.escape(name)}\s*\(", java_text):
            missing.append(f"{path.relative_to(ROOT)} -> #{name}")
    return len(fxml_files), len(handlers), missing


def migration_checks() -> tuple[int, list[str], str]:
    migration_dir = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration"
    migrations = sorted(
        migration_dir.glob("V*__*.sql"),
        key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)),
    )
    errors: list[str] = []
    integrity = "not-run"
    with tempfile.TemporaryDirectory(prefix="myhomelib-migrations-") as td:
        db_path = Path(td) / "library.sqlite"
        connection = sqlite3.connect(db_path)
        try:
            for migration in migrations:
                try:
                    connection.executescript(migration.read_text(encoding="utf-8"))
                    connection.commit()
                except Exception as exc:  # noqa: BLE001
                    errors.append(f"{migration.name}: {exc}")
                    connection.rollback()
                    break
        finally:
            connection.close()

        if not errors:
            # Prove that the resulting file can be closed and opened again.
            reopened = sqlite3.connect(db_path)
            try:
                integrity = reopened.execute("PRAGMA integrity_check").fetchone()[0]
                fk_violations = reopened.execute("PRAGMA foreign_key_check").fetchall()
                if integrity != "ok":
                    errors.append(f"PRAGMA integrity_check: {integrity}")
                if fk_violations:
                    errors.append(f"PRAGMA foreign_key_check: {len(fk_violations)} violation(s)")
            finally:
                reopened.close()
    return len(migrations), errors, integrity


def shell_checks() -> tuple[int, list[str]]:
    # Flag source-package issues that break Linux/macOS before Maven can even start.
    scripts = sorted(ROOT.glob("*.sh"))
    executables = scripts + ([ROOT / "mvnw"] if (ROOT / "mvnw").exists() else [])
    errors: list[str] = []
    for path in executables:
        data = path.read_bytes()
        if not data.startswith(b"#!/"):
            errors.append(f"{path.name}: missing shebang")
        if b"\r\n" in data:
            errors.append(f"{path.name}: CRLF line endings")
        if not os.access(path, os.X_OK):
            errors.append(f"{path.name}: not executable on Unix")
    return len(executables), errors



def packaging_checks() -> list[str]:
    """Catch release-script regressions that can be proven without the target OS."""
    errors: list[str] = []

    windows_script = (ROOT / "package-desktop.ps1").read_text(encoding="utf-8")
    if '"--win-per-user-install"' not in windows_script:
        errors.append("package-desktop.ps1: missing JDK 21 --win-per-user-install option")
    if "$PackageVersion" not in windows_script or '"--app-version", $Version' not in windows_script:
        errors.append("package-desktop.ps1: missing package-version override required for upgrade acceptance")
    if '"--win-per-user"' in windows_script:
        errors.append("package-desktop.ps1: invalid jpackage option --win-per-user; use --win-per-user-install")
    portable_windows = (ROOT / "package-portable.ps1").read_text(encoding="utf-8")
    portable_unix = (ROOT / "package-portable.sh").read_text(encoding="utf-8")
    if "myhomelib2.ini" not in portable_windows:
        errors.append("package-portable.ps1: portable archive must include myhomelib2.ini beside launcher")
    if "myhomelib2.ini" not in portable_unix:
        errors.append("package-portable.sh: portable archive must include myhomelib2.ini beside launcher")

    match = re.search(r'"--win-upgrade-uuid"\s*,\s*"([0-9a-fA-F-]{36})"', windows_script)
    if not match:
        errors.append("package-desktop.ps1: missing stable --win-upgrade-uuid")
    else:
        try:
            import uuid
            uuid.UUID(match.group(1))
        except ValueError:
            errors.append("package-desktop.ps1: --win-upgrade-uuid is not a valid UUID")

    workflow = (ROOT / ".github/workflows/ci-release.yml").read_text(encoding="utf-8")
    if "smoke-portable.ps1" not in workflow or "smoke-portable.sh" not in workflow:
        errors.append("ci-release.yml: every platform must smoke the extracted portable archive")
    windows_acceptance = ROOT / "tools/windows-installer-acceptance.ps1"
    if not windows_acceptance.is_file():
        errors.append("tools/windows-installer-acceptance.ps1: Windows installer lifecycle gate is missing")
    else:
        acceptance_text = windows_acceptance.read_text(encoding="utf-8")
        required_contracts = [
            "msiexec.exe",
            "Assert-SingleRegistration",
            ".myhomelibcorp",
            "acceptance-library.db",
            "Desktop MyHomeLib shortcut",
            "Start Menu MyHomeLib shortcut",
        ]
        for contract in required_contracts:
            if contract not in acceptance_text:
                errors.append(f"windows-installer-acceptance.ps1: missing acceptance contract {contract!r}")
    if "windows-installer-acceptance.ps1" not in workflow:
        errors.append("ci-release.yml: Windows installer lifecycle acceptance is not executed")
    if "--expect-windows-msi" not in workflow or "--expect-windows-exe" not in workflow:
        errors.append("ci-release.yml: Windows release validation must require both MSI and EXE candidates")
    windows_ui_acceptance = ROOT / "tools/windows-ui-acceptance.ps1"
    if not windows_ui_acceptance.is_file():
        errors.append("tools/windows-ui-acceptance.ps1: Windows UI/DPI acceptance runner is missing")
    else:
        ui_text = windows_ui_acceptance.read_text(encoding="utf-8")
        for contract in ["ValidateSet(100, 125, 150, 200)", "дорничев", "Дмитрий Дорничев",
                         "Reader toolbar", "Collection Wizard", "Backup and Restore",
                         "Back / Forward", "Followed Authors", "client area",
                         "GetDpiForSystem", "GetSystemMetrics", "$expectedDpi", "AUTO-0"]:
            if contract not in ui_text:
                errors.append(f"windows-ui-acceptance.ps1: missing P4 contract {contract!r}")
    release_ps1 = (ROOT / "release.ps1").read_text(encoding="utf-8")
    release_sh = (ROOT / "release.sh").read_text(encoding="utf-8")
    if "smoke-portable.ps1" not in release_ps1 or "smoke-portable.sh" not in release_sh:
        errors.append("release scripts: extracted portable archive smoke is missing")
    release_action_count = workflow.count("softprops/action-gh-release@")
    if release_action_count != 1:
        errors.append(
            f"ci-release.yml: expected exactly one GitHub release publisher, found {release_action_count}"
        )
    if "needs: package" not in workflow:
        errors.append("ci-release.yml: publish job must wait for all package jobs")

    connected_script = ROOT / "tools/github-connected-acceptance.py"
    connected_test = ROOT / "tools/github-connected-acceptance-test.py"
    ingest_script = ROOT / "tools/github-acceptance-artifact-ingest.py"
    ingest_test = ROOT / "tools/github-acceptance-artifact-ingest-test.py"
    harness_binding = ROOT / "tools/windows-acceptance-harness-binding.py"
    harness_binding_test = ROOT / "tools/windows-acceptance-harness-binding-test.py"
    windows_host_binding = ROOT / "tools/windows-acceptance-host.ps1"
    desktop_acceptance = ROOT / "tools/windows-release-desktop-acceptance.ps1"
    final_external = ROOT / "tools/v71-final-external-acceptance-check.py"
    final_external_test = ROOT / "tools/v71-final-external-acceptance-check-test.py"
    connected_workflow = ROOT / ".github/workflows/github-acceptance.yml"
    for path in (connected_script, connected_test, ingest_script, ingest_test, harness_binding, harness_binding_test, windows_host_binding, desktop_acceptance, final_external, final_external_test, connected_workflow):
        if not path.is_file():
            errors.append(f"{path.relative_to(ROOT)}: connected GitHub acceptance contract is missing")
    if connected_script.is_file():
        connected_text = connected_script.read_text(encoding="utf-8")
        for contract in ["/rules/branches/", "Fast gate", "ci-release.yml", "bom.json",
                         "dependency-check-report.json", "/code-scanning/analyses",
                         "--codeql-release-gate-only", "--expected-sha", "myhomelib-windows",
                         "windowsMsiSha256", "windowsExeSha256", "windowsPortableSha256", "candidate-windows",
                         "acceptanceHarnessManifestSha256", "acceptance-harness.sha256"]:
            if contract not in connected_text:
                errors.append(f"github-connected-acceptance.py: missing acceptance contract {contract!r}")
    if windows_host_binding.is_file():
        host_text = windows_host_binding.read_text(encoding="utf-8")
        for contract in ["MachineGuid", "acceptanceSessionId", "hostFingerprintSha256", "userFingerprintSha256", "windows-acceptance-host-binding"]:
            if contract not in host_text:
                errors.append(f"windows-acceptance-host.ps1: missing host/session contract {contract!r}")
    if final_external.is_file():
        final_text = final_external.read_text(encoding="utf-8")
        for contract in ["MHL-010-A", "MHL-017/MHL-018", "verify_installer",
                         "verify_portable", "verify_dpi", "verify_release_desktop",
                         "verify_github_ingest", "windows-final-acceptance-evidence.zip",
                         "schemaVersion must be 2", "candidate-binding", "windowsExeSha256",
                         "windowsPortableSha256", "verify_harness_binding", "acceptanceHarnessManifestSha256",
                         "verify_host_cohesion", "acceptanceSessionId", "hostFingerprintSha256"]:
            if contract not in final_text:
                errors.append(f"v71-final-external-acceptance-check.py: missing final acceptance contract {contract!r}")

    checksum_script = (ROOT / "checksums.sh").read_text(encoding="utf-8")
    if "sort -z" in checksum_script or 'sha256sum "$rel"' in checksum_script:
        errors.append("checksums.sh: contains GNU-specific checksum pipeline; macOS portability would be broken")

    return errors

def main() -> int:
    xml_count, xml_errors = xml_checks()
    fxml_count, handler_count, handler_errors = fxml_handler_checks()
    migration_count, migration_errors, integrity = migration_checks()
    shell_count, shell_errors = shell_checks()
    packaging_errors = packaging_checks()

    java_count = sum(1 for _ in ROOT.rglob("*.java"))
    test_count = sum(1 for p in ROOT.rglob("*.java") if "/src/test/" in p.as_posix())
    all_errors = xml_errors + handler_errors + migration_errors + shell_errors + packaging_errors

    print(f"XML (POM + FXML): {xml_count}; errors: {len(xml_errors)}")
    print(f"FXML workspaces: {fxml_count}; handler references: {handler_count}; missing: {len(handler_errors)}")
    print(f"SQLite migrations: {migration_count}; errors: {len(migration_errors)}; integrity: {integrity}")
    print(f"Root shell scripts: {shell_count}; static issues: {len(shell_errors)}")
    print(f"Release packaging/CI static issues: {len(packaging_errors)}")
    print(f"Java sources: {java_count}; test sources: {test_count}")

    if all_errors:
        print("\nFAILURES:")
        for error in all_errors:
            print(f"- {error}")
        return 1
    print("OFFLINE STATIC RELEASE CHECK: PASS")
    print("NOTE: this does not replace ./mvnw clean verify or JavaFX/jpackage runtime smoke tests.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
