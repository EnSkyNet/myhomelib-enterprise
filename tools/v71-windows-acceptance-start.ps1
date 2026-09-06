param(
    [Parameter(Mandatory=$true)][string]$Repo,
    [Parameter(Mandatory=$true)][long]$AcceptanceRunId,
    [Parameter(Mandatory=$true)][string]$PreviousMsi,
    [Parameter(Mandatory=$true)][string]$PreviousVersion,
    [string]$GitHubEvidenceRoot = "target\github-connected-acceptance"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

if ($env:OS -ne "Windows_NT") { throw "MyHomeLib 7.1 Windows final acceptance must start on Windows." }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Final Windows acceptance requires a standard/non-elevated user."
}
if ($AcceptanceRunId -le 0) { throw "AcceptanceRunId must be a positive GitHub Actions run id." }
if ([string]::IsNullOrWhiteSpace($Repo) -or $Repo -notmatch '^[^/\s]+/[^/\s]+$') { throw "Repo must be OWNER/REPO." }

Write-Host "[1/5] Fetch and digest-verify the exact GitHub connected-acceptance artifact"
& python "tools\github-acceptance-artifact-ingest.py" `
    --repo $Repo `
    --acceptance-run-id $AcceptanceRunId `
    --out-dir $GitHubEvidenceRoot
if ($LASTEXITCODE -ne 0) { throw "GitHub acceptance artifact ingest failed with exit code $LASTEXITCODE" }

Write-Host "[2/5] Verify local acceptance harness matches the exact candidate checkout"
$GitHubJson = Join-Path ([IO.Path]::GetFullPath($GitHubEvidenceRoot)) "github-connected-acceptance.json"
$github = Get-Content -Raw $GitHubJson | ConvertFrom-Json
$HarnessManifest = Join-Path ([IO.Path]::GetFullPath($GitHubEvidenceRoot)) "acceptance-harness.sha256"
& python "tools\windows-acceptance-harness-binding.py" `
    --verify-manifest $HarnessManifest `
    --candidate-sha ([string]$github.candidateSha) `
    --out-json "target\windows-harness-binding\windows-harness-binding.json"
if ($LASTEXITCODE -ne 0) { throw "Local Windows acceptance harness does not match the candidate-bound manifest (exit code $LASTEXITCODE)" }

Write-Host "[3/5] Start a clean candidate-bound Windows host/session"
$HostBindingPath = "target\windows-host-binding\windows-host-binding.json"
foreach ($stale in @(
    "target\windows-host-binding",
    "target\windows-installer-acceptance",
    "target\windows-portable-acceptance",
    "target\windows-release-desktop-acceptance",
    "target\windows-bound-packaging-preflight",
    "target\windows-final-acceptance-evidence.zip",
    "target\windows-final-acceptance-evidence.zip.sha256",
    "target\v71-final-external-acceptance",
    "target\myhomelib-7.1-final-external-evidence.zip",
    "target\myhomelib-7.1-final-external-evidence.zip.sha256"
)) { Remove-Item -Recurse -Force $stale -ErrorAction SilentlyContinue }
Get-ChildItem -Path "target" -Filter "windows-ui-acceptance-*" -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
Get-ChildItem -Path "target" -Filter "dpi-*-evidence" -Directory -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
. .\tools\windows-acceptance-host.ps1
$hostBinding = New-MyHomeLibWindowsAcceptanceHostBinding `
    -CandidateSha ([string]$github.candidateSha) `
    -Repository ([string]$github.repository) `
    -AcceptanceRunId $AcceptanceRunId `
    -Path $HostBindingPath
Write-Host "Acceptance session: $($hostBinding.acceptanceSessionId)"
Write-Host "Host fingerprint: $($hostBinding.hostFingerprintSha256)"

Write-Host "[4/5] Run bound real-previous MSI + portable acceptance"
& .\tools\windows-bound-packaging-acceptance.ps1 `
    -GitHubEvidenceRoot $GitHubEvidenceRoot `
    -PreviousMsi $PreviousMsi `
    -PreviousVersion $PreviousVersion `
    -HostBindingPath $HostBindingPath
if ($LASTEXITCODE -ne 0) { throw "Bound Windows packaging acceptance failed with exit code $LASTEXITCODE" }

Write-Host "[5/5] Run real EXE/data-migration/desktop release acceptance"
& .\tools\windows-release-desktop-acceptance.ps1 `
    -GitHubEvidenceRoot $GitHubEvidenceRoot `
    -PreviousVersion $PreviousVersion `
    -HostBindingPath $HostBindingPath
if ($LASTEXITCODE -ne 0) { throw "Windows desktop release acceptance failed with exit code $LASTEXITCODE" }

Write-Host "MyHomeLib 7.1 Windows acceptance preparation: PASS"
Write-Host "Next: set Windows Display scaling to 100/125/150/200% and run tools\windows-ui-acceptance.ps1 once at each scale."
Write-Host "Then run tools\v71-finalize-external-acceptance.ps1 -GitHubEvidenceRoot $GitHubEvidenceRoot"
