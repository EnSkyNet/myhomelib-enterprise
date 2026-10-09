#!/usr/bin/env python3
"""Offline release sanity checks that require only Python 3.

This is intentionally not a replacement for `mvn clean verify`: it catches
broken XML/FXML references and validates the raw SQLite migration chain in an
empty file-backed database when Maven dependencies are not available yet.
"""
from __future__ import annotations

import hashlib
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
    prepare_windows = (ROOT / "tools/prepare-runtime-tools.ps1").read_text(encoding="utf-8")
    smoke_runtime_windows = (ROOT / "tools/smoke-runtime-tools.ps1").read_text(encoding="utf-8")
    release_windows = (ROOT / "release.ps1").read_text(encoding="utf-8")
    package_desktop_unix = (ROOT / "package-desktop.sh").read_text(encoding="utf-8")
    desktop_memory_options = ("-Xms32m", "-Xmx1024m", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=20")
    desktop_runtime_options = ("-Dfile.encoding=UTF-8", "-Dspring.profiles.active=prod")
    for option in desktop_runtime_options:
        if option not in package_desktop_unix:
            errors.append(f"package-desktop.sh: missing desktop runtime option {option}")
        if option not in windows_script:
            errors.append(f"package-desktop.ps1: missing desktop runtime option {option}")
    for option in desktop_memory_options:
        if option not in package_desktop_unix:
            errors.append(f"package-desktop.sh: missing desktop memory option {option}")
        if option not in windows_script:
            errors.append(f"package-desktop.ps1: missing desktop memory option {option}")
    for script, label in ((package_desktop_unix, "package-desktop.sh"), (windows_script, "package-desktop.ps1")):
        if "--add-modules" not in script:
            errors.append(f"{label}: missing explicit jlink runtime module set")
        for required_module in ("java.desktop", "java.net.http", "java.sql.rowset", "jdk.crypto.ec", "jdk.httpserver", "jdk.zipfs"):
            if required_module not in script:
                errors.append(f"{label}: runtime module set is missing {required_module}")
        for dev_module in ("jdk.javadoc", "jdk.jshell", "jdk.jconsole", "jdk.jpackage", "jdk.jdeps"):
            if dev_module in script:
                errors.append(f"{label}: desktop runtime must not bundle development module {dev_module}")
    release_unix = (ROOT / "release.sh").read_text(encoding="utf-8")
    # Source ZIPs and copied worktrees may lose executable bits on helper scripts.
    # Release orchestration must invoke non-top-level shell helpers through bash.
    for contract, label in ((
            "bash ./tools/invoke-maven.sh", "package-desktop.sh"), (
            "bash ./tools/prepare-runtime-tools.sh", "package-desktop.sh"), (
            "bash ./tools/invoke-maven.sh", "release.sh"), (
            "bash ./tools/smoke-runtime-tools.sh", "release.sh")):
        source = package_desktop_unix if label == "package-desktop.sh" else release_unix
        if contract not in source:
            errors.append(f"{label}: helper shell scripts must be launched through bash ({contract})")
    if 'export MHL_REQUIRE_LIBMOBI=1' not in release_unix:
        errors.append("release.sh: full release must require bundled libmobi runtime")
    if '$env:MHL_REQUIRE_LIBMOBI = "1"' not in release_windows:
        errors.append("release.ps1: full release must require bundled libmobi runtime")
    if 'MHL_REQUIRE_LIBMOBI' not in prepare_windows or 'MHL_REQUIRE_LIBMOBI' not in smoke_runtime_windows:
        errors.append("Windows runtime-tool scripts: missing strict/optional libmobi packaging contract")
    for token in ("fbc-windows-amd64", "convert --to epub3", "reader-rich.fb2", "EPUB produced by packaged FBC failed EPUBCheck"):
        if token not in smoke_runtime_windows:
            errors.append(f"tools/smoke-runtime-tools.ps1: missing real packaged FBC conversion smoke token {token!r}")
    if '$libmobiLicense' not in windows_script:
        errors.append("package-desktop.ps1: bundled mobitool must be staged only with its license")
    if 'MHL_REQUIRE_LIBMOBI' not in (ROOT / "tools/prepare-runtime-tools.sh").read_text(encoding="utf-8"):
        errors.append("prepare-runtime-tools.sh: missing strict/optional libmobi packaging contract")
    if '[[ -x "tools/converters/libmobi/mobitool" ]]' not in package_desktop_unix:
        errors.append("package-desktop.sh: optional bundled mobitool must be staged conditionally")

    if "myhomelib2.ini" not in portable_windows:
        errors.append("package-portable.ps1: portable archive must include myhomelib2.ini beside launcher")
    for compatibility_launcher in ("Start-MyHomeLib.cmd", "MyHomeLib.vbs"):
        if compatibility_launcher not in portable_windows:
            errors.append(f"package-portable.ps1: missing compatibility launcher {compatibility_launcher}")
        if compatibility_launcher not in (ROOT / "smoke-portable.ps1").read_text(encoding="utf-8"):
            errors.append(f"smoke-portable.ps1: compatibility launcher is not exercised: {compatibility_launcher}")
    if 'bundledRuntimePresent' not in (ROOT / "smoke-portable.ps1").read_text(encoding="utf-8"):
        errors.append("smoke-portable.ps1: bundled Java runtime absence is not checked")
    if 'MHL_REQUIRE_FBC' not in portable_windows:
        errors.append("package-portable.ps1: Windows portable build must require bundled fb2cng/fbc")
    canonical_portable_name = 'MyHomeLib-R18-$Version-portable-windows-$portableArch.zip'
    if canonical_portable_name not in portable_windows:
        errors.append("package-portable.ps1: R18 Windows portable must use the canonical release artifact name")
    if "'^(amd64|x86_64)$' { 'x64'; break }" not in portable_windows:
        errors.append("package-portable.ps1: Windows AMD64 architecture must normalize to x64 in the artifact name")
    smoke_portable_windows = (ROOT / "smoke-portable.ps1").read_text(encoding="utf-8")
    if 'MyHomeLib-R18-$Version-portable-windows-*.zip' not in smoke_portable_windows:
        errors.append("smoke-portable.ps1: canonical R18 Windows portable name is not enforced")
    if 'MHL_REQUIRE_FBC' not in prepare_windows:
        errors.append("tools/prepare-runtime-tools.ps1: missing strict portable fb2cng/fbc contract")
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

    # Supply-chain guard: every remote GitHub Action must be pinned to an immutable full commit SHA.
    action_ref = re.compile(r"\buses:\s*([^@\s]+)@([^\s#]+)")
    for workflow_path in sorted((ROOT / ".github/workflows").glob("*.yml")):
        for line_no, line in enumerate(workflow_path.read_text(encoding="utf-8").splitlines(), start=1):
            match = action_ref.search(line)
            if not match:
                continue
            action, ref = match.groups()
            if action.startswith("./"):
                continue
            if not re.fullmatch(r"[0-9a-fA-F]{40}", ref):
                errors.append(
                    f"{workflow_path.relative_to(ROOT)}:{line_no}: GitHub Action {action}@{ref} "
                    "must be pinned to a full 40-character commit SHA"
                )

    # Build-tool contract: formal source archives rely on external tools, so launchers must fail
    # fast on unsupported JDK/Maven versions instead of producing environment-dependent failures.
    invoke_sh = (ROOT / "tools/invoke-maven.sh").read_text(encoding="utf-8")
    invoke_ps = (ROOT / "tools/invoke-maven.ps1").read_text(encoding="utf-8")
    for text, label in ((invoke_sh, "tools/invoke-maven.sh"), (invoke_ps, "tools/invoke-maven.ps1")):
        if "3.9.6" not in text:
            errors.append(f"{label}: Maven 3.9.6+ guard is missing")
        if "JDK 21+" not in text:
            errors.append(f"{label}: JDK 21+ guard is missing")
    if "locale charmap" not in invoke_sh or "C.UTF-8" not in invoke_sh or "ensure_utf8_locale" not in invoke_sh:
        errors.append("tools/invoke-maven.sh: UTF-8 launcher-locale guard is missing")
    package_desktop_sh = (ROOT / "package-desktop.sh").read_text(encoding="utf-8")
    package_desktop_ps = (ROOT / "package-desktop.ps1").read_text(encoding="utf-8")
    prepare_runtime_ps = (ROOT / "tools/prepare-runtime-tools.ps1").read_text(encoding="utf-8")
    if "tools/converters/libmobi/COPYING" not in package_desktop_sh or "build-src/COPYING" not in package_desktop_sh:
        errors.append("package-desktop.sh: libmobi runtime/source license fallback is missing")
    if "libmobi\\COPYING" not in package_desktop_ps or "build-src\\COPYING" not in package_desktop_ps:
        errors.append("package-desktop.ps1: libmobi runtime/source license fallback is missing")
    if "RuntimeLicense" not in prepare_runtime_ps or "SourceLicense" not in prepare_runtime_ps:
        errors.append("tools/prepare-runtime-tools.ps1: libmobi runtime/source license fallback is missing")
    if '"tools.zip"' not in prepare_runtime_ps or "Expand-Archive" not in prepare_runtime_ps or '"tools.7z"' not in prepare_runtime_ps:
        errors.append("tools/prepare-runtime-tools.ps1: tools.zip/tools.7z compatibility-bundle support is missing")
    libmobi_binary = ROOT / "tools/converters/libmobi/mobitool.exe"
    runtime_license = ROOT / "tools/converters/libmobi/COPYING"
    source_license = ROOT / "tools/converters/libmobi/build-src/COPYING"
    if libmobi_binary.is_file() and not runtime_license.is_file() and not source_license.is_file():
        errors.append("tools/converters/libmobi: tracked mobitool.exe has no distributable COPYING")
    if runtime_license.is_file():
        manifest = ROOT / "tools/CONVERTERS-SHA256.txt"
        expected = None
        if manifest.is_file():
            for line in manifest.read_text(encoding="utf-8", errors="replace").splitlines():
                parts = line.strip().split(None, 1)
                if len(parts) == 2 and parts[1].replace("\\", "/").endswith("tools/converters/libmobi/build-src/COPYING"):
                    expected = parts[0].lower()
                    break
        if expected is None:
            errors.append("tools/CONVERTERS-SHA256.txt: expected libmobi COPYING digest is missing")
        else:
            actual = hashlib.sha256(runtime_license.read_bytes()).hexdigest()
            if actual != expected:
                errors.append("tools/converters/libmobi/COPYING: SHA-256 differs from expected libmobi license digest")

    workflow = (ROOT / ".github/workflows/ci-release.yml").read_text(encoding="utf-8")
    if "smoke-portable.ps1" not in workflow or "smoke-portable.sh" not in workflow:
        errors.append("ci-release.yml: every platform must smoke the extracted portable archive")
    if "dist/MyHomeLib-R18-*-portable-windows-*.zip" not in workflow:
        errors.append("ci-release.yml: canonical R18 Windows portable is not uploaded as a release artifact")
    stage23_release = (ROOT / "tools/stage23-cross-platform-release-check.py").read_text(encoding="utf-8")
    if 'MyHomeLib-R18-{version}-portable-windows-*.zip' not in stage23_release:
        errors.append("stage23-cross-platform-release-check.py: canonical R18 Windows portable is not validated")
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
    zip_safety = ROOT / "tools/zip_evidence_safety.py"
    zip_safety_test = ROOT / "tools/zip-evidence-safety-test.py"
    windows_host_binding = ROOT / "tools/windows-acceptance-host.ps1"
    desktop_acceptance = ROOT / "tools/windows-release-desktop-acceptance.ps1"
    final_external = ROOT / "tools/v71-final-external-acceptance-check.py"
    final_external_test = ROOT / "tools/v71-final-external-acceptance-check-test.py"
    connected_workflow = ROOT / ".github/workflows/github-acceptance.yml"
    evidence_contracts = ROOT / "tools/evidence_contracts.py"
    evidence_contracts_test = ROOT / "tools/evidence-contracts-test.py"
    for path in (connected_script, connected_test, ingest_script, ingest_test, harness_binding, harness_binding_test, zip_safety, zip_safety_test, windows_host_binding, desktop_acceptance, final_external, final_external_test, connected_workflow, evidence_contracts, evidence_contracts_test):
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
                         'validate_record(data, "github-connected-acceptance"', "candidate-binding", "windowsExeSha256",
                         "windowsPortableSha256", "verify_harness_binding", "acceptanceHarnessManifestSha256",
                         "verify_host_cohesion", "acceptanceSessionId", "hostFingerprintSha256",
                         "expected_windows_archive_members", "inspect_open_zip"]:
            if contract not in final_text:
                errors.append(f"v71-final-external-acceptance-check.py: missing final acceptance contract {contract!r}")

    post_r5_checker = ROOT / "tools/post-r5-live-acceptance-check.py"
    post_r5_checker_test = ROOT / "tools/post-r5-live-acceptance-check-test.py"
    post_r5_recorder = ROOT / "tools/post-r5-live-evidence.py"
    post_r5_windows = ROOT / "tools/windows-post-r5-functional-acceptance.ps1"
    post_r5_memory = ROOT / "tools/windows-target-memory-soak.ps1"
    post_r5_runbook = ROOT / "docs/release/POST-R5-LIVE-ACCEPTANCE-RUNBOOK-UA.md"
    for path in (post_r5_checker, post_r5_checker_test, post_r5_recorder, post_r5_windows, post_r5_memory, post_r5_runbook):
        if not path.is_file():
            errors.append(f"{path.relative_to(ROOT)}: post-R5 live acceptance contract is missing")
    if post_r5_checker.is_file():
        text = post_r5_checker.read_text(encoding="utf-8")
        for contract in ["windows-post-r5-functional", "physical-kobo", "real-readwise",
                         "pwa-real-browser", "real-comic-corpus", "windows-memory-soak",
                         "soakDurationSeconds", "duplicate evidence content", "evidenceSha256", "candidateSha",
                         "gcCollectionCount", "gcTotalTimeMs", "workingSetPeakBytes",
                         "gcCollectionDelta", "gcTotalTimeDeltaMs", "acceptanceSessionId",
                         "hostFingerprintSha256", "validate_windows_cohesion",
                         "myhomelib-7.1-final-external-acceptance", "candidate-binding",
                         "windowsIntegrityCandidateSha"]:
            if contract not in text:
                errors.append(f"post-r5-live-acceptance-check.py: missing contract {contract!r}")
    if post_r5_windows.is_file():
        text = post_r5_windows.read_text(encoding="utf-8")
        for contract in ["WBD-01", "WBD-06", "CAL-01", "CAL-09", "WIN-01", "WIN-12",
                         "mhl-calibre-*", "calibre-parallel", "Elapsed.TotalSeconds",
                         "evidenceSha256", "Get-FileHash",
                         "Get-VerifiedMyHomeLibWindowsAcceptanceHostBinding"]:
            if contract not in text:
                errors.append(f"windows-post-r5-functional-acceptance.ps1: missing contract {contract!r}")
    if post_r5_memory.is_file():
        text = post_r5_memory.read_text(encoding="utf-8")
        for contract in ["MEM-01", "MEM-10", "SoakSeconds -lt 3600", "GC.heap_info",
                         "VM.native_memory summary", "jstat", "gcTotalTimeMs", "gcCollectionDelta",
                         "workingSetPeakBytes", "sampleCount", "evidenceSha256", "Get-FileHash",
                         "Get-VerifiedMyHomeLibWindowsAcceptanceHostBinding"]:
            if contract not in text:
                errors.append(f"windows-target-memory-soak.ps1: missing contract {contract!r}")

    # Mockito 5 must be attached explicitly as a Java agent on JDK 21+ test modules.
    # Dynamic self-attachment is deprecated and becomes a future-JDK failure mode.
    root_pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    if "<mhl.mockito.agentArg>-javaagent:${settings.localRepository}/org/mockito/mockito-core/" not in root_pom:
        errors.append("pom.xml: explicit Mockito javaagent definition is missing")
    for module in sorted(ROOT.glob("myhomelib-*")):
        test_root = module / "src/test"
        pom = module / "pom.xml"
        if not test_root.is_dir() or not pom.is_file():
            continue
        uses_mockito = any("org.mockito" in source.read_text(encoding="utf-8", errors="replace")
                           for source in test_root.rglob("*.java"))
        if not uses_mockito:
            continue
        pom_text = pom.read_text(encoding="utf-8")
        if "<mhl.test.jvmArgs>${mhl.mockito.agentArg}</mhl.test.jvmArgs>" not in pom_text:
            errors.append(f"{module.name}/pom.xml: Mockito tests must use the explicit mhl.mockito.agentArg")

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

    java_sources = [p for p in ROOT.rglob("*.java") if not any(part in {"target", ".git"} for part in p.parts)]
    java_count = len(java_sources)
    test_count = sum(1 for p in java_sources if "/src/test/" in p.as_posix())
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
    print("NOTE: this does not replace mvn clean verify or JavaFX/jpackage runtime smoke tests.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
