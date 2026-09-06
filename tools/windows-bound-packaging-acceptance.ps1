param(
    [Parameter(Mandatory=$true)][string]$PreviousMsi,
    [Parameter(Mandatory=$true)][string]$PreviousVersion,
    [string]$GitHubEvidenceRoot = "target\github-connected-acceptance",
    [string]$CurrentMsi = "",
    [string]$PortableArchive = "",
    [string]$HostBindingPath = "target\windows-host-binding\windows-host-binding.json"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

if ($env:OS -ne "Windows_NT") {
    throw "Bound Windows packaging acceptance must run on Windows."
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Final MHL-012 acceptance requires a standard/non-elevated Windows user."
}

$GitHubEvidenceRoot = [IO.Path]::GetFullPath($GitHubEvidenceRoot)
$GitHubJson = Join-Path $GitHubEvidenceRoot "github-connected-acceptance.json"
if (-not (Test-Path $GitHubJson -PathType Leaf)) {
    throw "Connected GitHub evidence JSON not found: $GitHubJson"
}

Write-Host "[1/5] Validate candidate-bound GitHub evidence"
$githubPreflight = "target\v71-github-candidate-preflight"
& python "tools\v71-final-external-acceptance-check.py" `
    --github-only `
    --require-ingest `
    --github-json $GitHubJson `
    --out-dir $githubPreflight
if ($LASTEXITCODE -ne 0) {
    throw "Connected GitHub evidence validation failed with exit code $LASTEXITCODE"
}

$github = Get-Content -Raw $GitHubJson | ConvertFrom-Json
. .\tools\windows-acceptance-host.ps1
$hostBinding = Get-VerifiedMyHomeLibWindowsAcceptanceHostBinding `
    -Path $HostBindingPath `
    -CandidateSha ([string]$github.candidateSha) `
    -Repository ([string]$github.repository)
$releaseCheck = @($github.checks | Where-Object { $_.id -eq "MHL-017/MHL-018" })
if ($releaseCheck.Count -ne 1) {
    throw "Connected GitHub evidence must contain exactly one MHL-017/MHL-018 row."
}
$expectedMsiSha = [string]$releaseCheck[0].details.windowsMsiSha256
$expectedExeSha = [string]$releaseCheck[0].details.windowsExeSha256
$expectedPortableSha = [string]$releaseCheck[0].details.windowsPortableSha256
if ($expectedMsiSha -notmatch '^[0-9a-fA-F]{64}$' -or $expectedExeSha -notmatch '^[0-9a-fA-F]{64}$' -or $expectedPortableSha -notmatch '^[0-9a-fA-F]{64}$') {
    throw "Connected GitHub evidence does not contain valid bound Windows MSI/EXE/portable hashes."
}

$candidateDir = Join-Path $GitHubEvidenceRoot "candidate-windows"
if ([string]::IsNullOrWhiteSpace($CurrentMsi)) {
    $msiMatches = @(Get-ChildItem -Path $candidateDir -Filter "MyHomeLib-*.msi" -File -ErrorAction SilentlyContinue)
    if ($msiMatches.Count -ne 1) { throw "Expected exactly one candidate MSI under $candidateDir, found $($msiMatches.Count)" }
    $CurrentMsi = $msiMatches[0].FullName
}
if ([string]::IsNullOrWhiteSpace($PortableArchive)) {
    $portableMatches = @(Get-ChildItem -Path $candidateDir -Filter "myhomelib-*-windows-*.zip" -File -ErrorAction SilentlyContinue)
    if ($portableMatches.Count -ne 1) { throw "Expected exactly one candidate portable ZIP under $candidateDir, found $($portableMatches.Count)" }
    $PortableArchive = $portableMatches[0].FullName
}
$exeMatches = @(Get-ChildItem -Path $candidateDir -Filter "MyHomeLib-*.exe" -File -ErrorAction SilentlyContinue)
if ($exeMatches.Count -ne 1) { throw "Expected exactly one candidate EXE under $candidateDir, found $($exeMatches.Count)" }
$CurrentExe = $exeMatches[0].FullName

$CurrentMsi = [IO.Path]::GetFullPath($CurrentMsi)
$CurrentExe = [IO.Path]::GetFullPath($CurrentExe)
$PortableArchive = [IO.Path]::GetFullPath($PortableArchive)
$PreviousMsi = [IO.Path]::GetFullPath($PreviousMsi)
foreach ($path in @($CurrentMsi, $CurrentExe, $PortableArchive, $PreviousMsi)) {
    if (-not (Test-Path $path -PathType Leaf)) { throw "Required acceptance artifact not found: $path" }
}
$currentMsiSha = (Get-FileHash -Algorithm SHA256 $CurrentMsi).Hash.ToLowerInvariant()
$currentExeSha = (Get-FileHash -Algorithm SHA256 $CurrentExe).Hash.ToLowerInvariant()
$portableSha = (Get-FileHash -Algorithm SHA256 $PortableArchive).Hash.ToLowerInvariant()
if ($currentMsiSha -ne $expectedMsiSha.ToLowerInvariant()) {
    throw "Current MSI does not match the GitHub release candidate: expected $expectedMsiSha, got $currentMsiSha"
}
if ($currentExeSha -ne $expectedExeSha.ToLowerInvariant()) {
    throw "Current EXE does not match the GitHub release candidate: expected $expectedExeSha, got $currentExeSha"
}
if ($portableSha -ne $expectedPortableSha.ToLowerInvariant()) {
    throw "Portable archive does not match the GitHub release candidate: expected $expectedPortableSha, got $portableSha"
}

Write-Host "[2/5] Run real previous -> bound current MSI lifecycle"
Remove-Item -Recurse -Force "target\windows-installer-acceptance" -ErrorAction SilentlyContinue
& .\tools\windows-installer-acceptance.ps1 `
    -RequireStandardUser `
    -SkipBuild `
    -PreviousMsi $PreviousMsi `
    -PreviousVersion $PreviousVersion `
    -CurrentMsi $CurrentMsi `
    -HostBindingPath $HostBindingPath
if ($LASTEXITCODE -ne 0) { throw "Installer lifecycle acceptance failed with exit code $LASTEXITCODE" }

Write-Host "[3/5] Run bound portable Unicode/isolation smoke"
Remove-Item -Recurse -Force "target\windows-portable-acceptance" -ErrorAction SilentlyContinue
& .\smoke-portable.ps1 -Archive $PortableArchive -HostBindingPath $HostBindingPath
if ($LASTEXITCODE -ne 0) { throw "Portable smoke failed with exit code $LASTEXITCODE" }

Write-Host "[4/5] Validate packaging evidence before DPI acceptance"
& python "tools\windows-acceptance-evidence-check.py" `
    --root target `
    --require-standard-user `
    --require-real-previous `
    --require-host-binding
if ($LASTEXITCODE -ne 0) { throw "Bound packaging evidence validation failed with exit code $LASTEXITCODE" }

Write-Host "[5/5] Write candidate-binding preflight evidence"
$outDir = "target\windows-bound-packaging-preflight"
New-Item -ItemType Directory -Force $outDir | Out-Null
$payload = [ordered]@{
    schemaVersion = 1
    scenario = "windows-bound-packaging-preflight"
    timestamp = (Get-Date).ToString("o")
    overall = "PASS"
    candidateSha = [string]$github.candidateSha
    repository = [string]$github.repository
    acceptanceSessionId = [string]$hostBinding.acceptanceSessionId
    hostFingerprintSha256 = [string]$hostBinding.hostFingerprintSha256
    userFingerprintSha256 = [string]$hostBinding.userFingerprintSha256
    releaseRunId = $releaseCheck[0].details.runId
    releaseRunUrl = [string]$releaseCheck[0].details.htmlUrl
    currentMsi = $CurrentMsi
    currentMsiSha256 = $currentMsiSha
    currentExe = $CurrentExe
    currentExeSha256 = $currentExeSha
    portableArchive = $PortableArchive
    portableSha256 = $portableSha
    previousMsi = $PreviousMsi
    previousVersion = $PreviousVersion
    dpiPending = $true
}
$payload | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $outDir "windows-bound-packaging-preflight.json") -Encoding utf8
@(
    "# MyHomeLib 7.1 — bound Windows packaging preflight",
    "",
    "Overall: **PASS**",
    "",
    "- Candidate SHA: ``$($payload.candidateSha)``",
    "- Release run: $($payload.releaseRunUrl)",
    "- Current MSI SHA-256: ``$currentMsiSha``",
    "- Current EXE SHA-256: ``$currentExeSha``",
    "- Portable SHA-256: ``$portableSha``",
    "- Real previous version: ``$PreviousVersion``",
    "- DPI acceptance pending: **yes**",
    "",
    "Next: run tools\windows-release-desktop-acceptance.ps1, then tools\windows-ui-acceptance.ps1 at 100/125/150/200%, then tools\v71-finalize-external-acceptance.ps1."
) | Set-Content -Path (Join-Path $outDir "windows-bound-packaging-preflight.md") -Encoding utf8

Write-Host "Bound Windows packaging acceptance: PASS (DPI still pending)"
