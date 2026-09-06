#!/usr/bin/env python3
from pathlib import Path


def require(path, *needles):
    text = Path(path).read_text(encoding="utf-8")
    for needle in needles:
        assert needle in text, f"{path}: missing {needle!r}"


require(
    "tools/windows-acceptance-host.ps1",
    "MachineGuid",
    "WindowsIdentity",
    "acceptanceSessionId",
    "hostFingerprintSha256",
    "userFingerprintSha256",
    "windows-acceptance-host-binding",
    "standard/non-elevated",
)
require(
    "tools/windows-ui-acceptance.ps1",
    "Save-DesktopScreenshot",
    "cannot PASS without screenshot evidence",
    "evidencePath =",
    "P4-01 supplied screenshot-backed confirmation",
    'scenario = "windows-ui-dpi-acceptance"',
    "windows-ui-acceptance-$Scale.md",
    "P4-20",
    "HostBindingPath",
    "acceptanceSessionId",
    "hostFingerprintSha256",
)
require(
    "tools/windows-installer-acceptance.ps1",
    "RequireStandardUser",
    "previousPackageSource",
    '"synthetic"',
    '"external"',
    "previousMsiSha256",
    "currentMsiSha256",
    "Copy-Item -Force $builtCurrent $CurrentMsi",
    "msiexecLogs",
    "installer-acceptance.json",
    "userDataPreserved",
    "shortcutsRemoved",
    "HostBindingPath",
    "acceptanceSessionId",
    "hostFingerprintSha256",
)
require(
    "smoke-portable.ps1",
    "Моя бібліотека",
    "portable-smoke.json",
    "profileEnvironmentRedirected",
    "syntheticHomeWriteDetected",
    "workingDirectoryWriteDetected",
    "profileWriteDetected",
    "portableDataCreated",
    "HostBindingPath",
    "acceptanceSessionId",
    "hostFingerprintSha256",
)
require(
    "tools/windows-acceptance-evidence-check.py",
    "DPI_SCALES = (100, 125, 150, 200)",
    "PureWindowsPath",
    "screenshot evidence reused across checks",
    "duplicate screenshot content reused across checks",
    "MIN_SCREENSHOT_WIDTH",
    "msiexecLogs",
    "archiveSha256",
    "--require-standard-user",
    "--require-real-previous",
    "--dpi",
    "--release-desktop",
    "verify_release_desktop",
    "P5_IDS",
    "verify_host_binding",
    "verify_host_cohesion",
    "--require-host-binding",
)
require(
    "tools/windows-acceptance-evidence-check-test.py",
    "duplicate P4 row",
    "duplicate screenshot content",
    "implausibly small screenshot",
    "missing msiexec evidence log",
    "portable cwd write accepted",
    "evidence path escaped bundle",
    "desktop admin user accepted",
    "desktop migration note missing",
    "desktop EXE binding mismatch",
    "mixed Windows host evidence accepted",
    "mixed Windows acceptance session accepted",
)
require(
    "tools/windows-final-evidence-pack.ps1",
    "--require-standard-user",
    "--require-real-previous",
    "--dpi",
    "--release-desktop",
    "windows-release-desktop-acceptance",
    "windows-host-binding",
    "--require-host-binding",
    "manifest.sha256",
    "windows-final-acceptance-evidence.zip",
)

require(
    "tools/windows-bound-packaging-acceptance.ps1",
    "--github-only",
    "windowsMsiSha256",
    "windowsExeSha256",
    "windowsPortableSha256",
    "--require-ingest",
    "-RequireStandardUser",
    "-PreviousMsi",
    "-CurrentMsi",
    "smoke-portable.ps1",
    "--require-real-previous",
    "HostBindingPath",
    "--require-host-binding",
)
require(
    "tools/v71-finalize-external-acceptance.ps1",
    "--require-real-previous",
    "--dpi",
    "--release-desktop",
    "github-connected-acceptance-ingest.json",
    "windows-final-evidence-pack.ps1",
    "v71-final-external-acceptance-check.py",
    "myhomelib-7.1-final-external-evidence.zip",
    "manifest.sha256",
    "windows-host-binding",
    "--require-host-binding",
)

require(
    "tools/v71-windows-acceptance-start.ps1",
    "github-acceptance-artifact-ingest.py",
    "windows-bound-packaging-acceptance.ps1",
    "windows-release-desktop-acceptance.ps1",
    "AcceptanceRunId",
    "standard/non-elevated",
    "windows-acceptance-harness-binding.py",
    "acceptance-harness.sha256",
    "windows-acceptance-host.ps1",
    "New-MyHomeLibWindowsAcceptanceHostBinding",
    "acceptanceSessionId",
)
require(
    "tools/windows-release-desktop-acceptance.ps1",
    'scenario = "windows-release-desktop-acceptance"',
    "windowsExeSha256",
    "P5-01",
    "P5-07",
    "Save-DesktopScreenshot",
    "HostBindingPath",
    "acceptanceSessionId",
    "hostFingerprintSha256",
)
require(
    "tools/github-acceptance-artifact-ingest.py",
    "remoteDigestVerified",
    "verify_github_artifact_digest",
    "candidate-windows.sha256",
    "windowsExeSha256",
    "acceptance-harness.sha256",
    "acceptanceHarnessManifestSha256",
)
require(
    "tools/windows-acceptance-harness-binding.py",
    "CRITICAL_FILES",
    "verify_manifest",
    "windows-acceptance-harness-binding",
    "tools/windows-acceptance-host.ps1",
)
require(
    "tools/windows-acceptance-harness-binding-test.py",
    "tampered local acceptance harness unexpectedly passed",
)
require(
    "tools/github-acceptance-artifact-ingest-test.py",
    "parent traversal",
    "remoteDigestVerified",
)

require(
    ".github/workflows/ci-pr.yml",
    "Windows acceptance harness structure",
    "Windows acceptance evidence validator regression",
    "Windows acceptance harness candidate-binding regression",
)
require(
    ".github/workflows/ci-release.yml",
    "Windows acceptance harness structure",
    "windows-acceptance-evidence",
)
print("Windows acceptance harness structure: PASS")
