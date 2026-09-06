param(
    [string]$GitHubEvidenceRoot = "target\github-connected-acceptance",
    [string]$ExeInstaller = "",
    [string]$Launcher = "",
    [string]$PreviousVersion = "",
    [string]$HostBindingPath = "target\windows-host-binding\windows-host-binding.json",
    [string]$ReportPath = "target\windows-release-desktop-acceptance\desktop-acceptance.md",
    [string]$EvidenceDir = "",
    [switch]$ChecklistOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

if ($env:OS -ne "Windows_NT") { throw "Windows release desktop acceptance must run on Windows." }

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $ChecklistOnly -and $isAdmin) {
    throw "Final desktop acceptance requires a standard/non-elevated Windows user."
}

$GitHubEvidenceRoot = [IO.Path]::GetFullPath($GitHubEvidenceRoot)
$GitHubJson = Join-Path $GitHubEvidenceRoot "github-connected-acceptance.json"
if (-not (Test-Path $GitHubJson -PathType Leaf)) { throw "Missing connected GitHub evidence: $GitHubJson" }

& python "tools\v71-final-external-acceptance-check.py" --github-only --require-ingest --github-json $GitHubJson --out-dir "target\v71-desktop-github-preflight"
if ($LASTEXITCODE -ne 0) { throw "Connected GitHub evidence validation failed with exit code $LASTEXITCODE" }

$github = Get-Content -Raw $GitHubJson | ConvertFrom-Json
. .\tools\windows-acceptance-host.ps1
$hostBinding = Get-VerifiedMyHomeLibWindowsAcceptanceHostBinding `
    -Path $HostBindingPath `
    -CandidateSha ([string]$github.candidateSha) `
    -Repository ([string]$github.repository)
$releaseCheck = @($github.checks | Where-Object { $_.id -eq "MHL-017/MHL-018" })
if ($releaseCheck.Count -ne 1) { throw "Connected GitHub evidence must contain exactly one MHL-017/MHL-018 row." }
$expectedExeSha = [string]$releaseCheck[0].details.windowsExeSha256
if ($expectedExeSha -notmatch '^[0-9a-fA-F]{64}$') { throw "Connected GitHub evidence does not contain a valid candidate EXE SHA-256." }

$candidateDir = Join-Path $GitHubEvidenceRoot "candidate-windows"
if ([string]::IsNullOrWhiteSpace($ExeInstaller)) {
    $matches = @(Get-ChildItem -Path $candidateDir -Filter "MyHomeLib-*.exe" -File -ErrorAction SilentlyContinue)
    if ($matches.Count -ne 1) { throw "Expected exactly one bound candidate EXE under $candidateDir, found $($matches.Count)" }
    $ExeInstaller = $matches[0].FullName
}
$ExeInstaller = [IO.Path]::GetFullPath($ExeInstaller)
if (-not (Test-Path $ExeInstaller -PathType Leaf)) { throw "Candidate EXE installer not found: $ExeInstaller" }
$exeSha = (Get-FileHash -Algorithm SHA256 $ExeInstaller).Hash.ToLowerInvariant()
if ($exeSha -ne $expectedExeSha.ToLowerInvariant()) {
    throw "Candidate EXE does not match GitHub evidence: expected $expectedExeSha, got $exeSha"
}

if ([string]::IsNullOrWhiteSpace($Launcher)) { $Launcher = Join-Path $env:LOCALAPPDATA "MyHomeLib\MyHomeLib.exe" }
$Launcher = [IO.Path]::GetFullPath($Launcher)

if ([string]::IsNullOrWhiteSpace($PreviousVersion)) {
    $preflight = "target\windows-bound-packaging-preflight\windows-bound-packaging-preflight.json"
    if (Test-Path $preflight -PathType Leaf) {
        $PreviousVersion = [string](Get-Content -Raw $preflight | ConvertFrom-Json).previousVersion
    }
}
if ([string]::IsNullOrWhiteSpace($PreviousVersion)) {
    throw "PreviousVersion is required (or run windows-bound-packaging-acceptance.ps1 first)."
}

$ReportPath = [IO.Path]::GetFullPath($ReportPath)
New-Item -ItemType Directory -Force (Split-Path -Parent $ReportPath) | Out-Null
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) { $EvidenceDir = Join-Path (Split-Path -Parent $ReportPath) "evidence" }
$EvidenceDir = [IO.Path]::GetFullPath($EvidenceDir)
New-Item -ItemType Directory -Force $EvidenceDir | Out-Null

function Get-RelativeEvidencePath {
    param([string]$BaseDirectory, [string]$TargetPath)
    $baseFull = [IO.Path]::GetFullPath($BaseDirectory).TrimEnd([char[]]@('\', '/')) + [IO.Path]::DirectorySeparatorChar
    $targetFull = [IO.Path]::GetFullPath($TargetPath)
    $baseUri = New-Object System.Uri -ArgumentList $baseFull
    $targetUri = New-Object System.Uri -ArgumentList $targetFull
    return ([System.Uri]::UnescapeDataString($baseUri.MakeRelativeUri($targetUri).ToString())).Replace('/', [IO.Path]::DirectorySeparatorChar)
}

function Save-DesktopScreenshot {
    param([string]$Id)
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms
    $bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
    $bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($bounds.Left, $bounds.Top, 0, 0, $bitmap.Size)
        $file = Join-Path $EvidenceDir ("desktop-{0}.png" -f $Id)
        $bitmap.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
        return Get-RelativeEvidencePath -BaseDirectory (Split-Path -Parent $ReportPath) -TargetPath $file
    }
    finally {
        $graphics.Dispose(); $bitmap.Dispose()
    }
}

$results = New-Object System.Collections.Generic.List[object]
function Add-Result { param([string]$Id,[string]$Check,[string]$Outcome,[string]$Note,[string]$Evidence="")
    $results.Add([pscustomobject]@{ Id=$Id; Check=$Check; Outcome=$Outcome; Note=$Note; Evidence=$Evidence }) | Out-Null
}

if (Test-Path $Launcher -PathType Leaf) {
    $process = Start-Process -FilePath $Launcher -ArgumentList "--release-smoke" -Wait -PassThru
    Add-Result "AUTO-1" "Installed packaged launcher exists and --release-smoke exits 0" $(if ($process.ExitCode -eq 0) {"PASS"} else {"FAIL"}) "exit=$($process.ExitCode)"
} else {
    Add-Result "AUTO-1" "Installed packaged launcher exists and --release-smoke exits 0" "BLOCKED" "Launcher is not installed yet: $Launcher"
}

function Invoke-ManualCheck {
    param([string]$Id,[string]$Check,[switch]$RequireNote)
    if ($ChecklistOnly) { Add-Result $Id $Check "PENDING" ""; return }
    Write-Host ""; Write-Host "$Id $Check"
    Read-Host "Prepare the exact real release state, then press Enter to capture screenshot evidence" | Out-Null
    $evidence = Save-DesktopScreenshot $Id
    do { $answer = (Read-Host "$Id result [PASS/FAIL/BLOCKED]").Trim().ToUpperInvariant() } while ($answer -notin @("PASS","FAIL","BLOCKED"))
    $note = Read-Host $(if ($RequireNote) {"Evidence note (required for PASS)"} else {"Note (optional)"})
    if ($answer -eq "PASS" -and $RequireNote -and [string]::IsNullOrWhiteSpace($note)) { throw "$Id requires a non-empty evidence note when marked PASS." }
    Add-Result $Id $Check $answer $note $evidence
}

if (-not $ChecklistOnly) {
    Write-Host "Launching the exact SHA-256-verified candidate EXE installer: $ExeInstaller"
    Start-Process -FilePath $ExeInstaller | Out-Null
}
Invoke-ManualCheck "P5-01" "Complete the installer UI launched by this harness from the exact bound MyHomeLib EXE. Installer UI is usable, installation completes for this standard user, and no unexpected elevation/data-delete prompt appears."
Invoke-ManualCheck "P5-02" "First start from the EXE-installed launcher completes and the main application window is usable."
Invoke-ManualCheck "P5-03" "A representative profile/database/library created by real previous version $PreviousVersion opens after upgrade; collections, stable book/user metadata and settings needed for release are preserved." -RequireNote
Invoke-ManualCheck "P5-04" "Open a real collection, browse/search, open Book Details and confirm representative local content remains usable."
Invoke-ManualCheck "P5-05" "Use a configured online catalogue to download one test book; confirm the download completes and the book becomes locally openable." -RequireNote
Invoke-ManualCheck "P5-06" "Open the downloaded or representative book in Reader, navigate pages, close and reopen it; reading state and UI remain usable."
Invoke-ManualCheck "P5-07" "Create a real application backup, change a small test state, restore the backup and confirm the expected collection/user state returns successfully." -RequireNote

# AUTO-1 may initially be BLOCKED before P5-01/P5-02 installs the candidate. Re-check after manual steps.
$auto = $results | Where-Object { $_.Id -eq "AUTO-1" } | Select-Object -First 1
if (-not $ChecklistOnly -and (Test-Path $Launcher -PathType Leaf)) {
    $process = Start-Process -FilePath $Launcher -ArgumentList "--release-smoke" -Wait -PassThru
    if ($process.ExitCode -eq 0) { $auto.Outcome = "PASS"; $auto.Note = "post-install exit=0" }
    else { $auto.Outcome = "FAIL"; $auto.Note = "post-install exit=$($process.ExitCode)" }
}

$failed = @($results | Where-Object { $_.Outcome -eq "FAIL" }).Count
$blocked = @($results | Where-Object { $_.Outcome -eq "BLOCKED" }).Count
$pending = @($results | Where-Object { $_.Outcome -eq "PENDING" }).Count
$overall = if ($failed -eq 0 -and $blocked -eq 0 -and $pending -eq 0) { "PASS" } elseif ($ChecklistOnly) { "PENDING" } else { "FAIL" }
$osCaption = try { (Get-CimInstance Win32_OperatingSystem).Caption } catch { [Environment]::OSVersion.VersionString }
$timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss K")

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# MyHomeLib 7.1 — real desktop release acceptance")
$lines.Add("")
$lines.Add("- Timestamp: $timestamp")
$lines.Add("- Host: $env:COMPUTERNAME")
$lines.Add("- User: $env:USERNAME")
$lines.Add("- Candidate SHA: ``$($github.candidateSha)``")
$lines.Add("- Candidate EXE SHA-256: ``$exeSha``")
$lines.Add("- Previous real version: ``$PreviousVersion``")
$lines.Add("")
$lines.Add("| ID | Result | Check | Evidence | Note |")
$lines.Add("|---|---|---|---|---|")
foreach ($row in $results) {
    $check = ([string]$row.Check).Replace("|", "\|")
    $note = ([string]$row.Note).Replace("|", "\|").Replace("`r", " ").Replace("`n", " ")
    $ev = ([string]$row.Evidence).Replace("|", "\|")
    $lines.Add("| $($row.Id) | $($row.Outcome) | $check | $ev | $note |")
}
$lines.Add("")
$lines.Add("**Overall: $overall** (FAIL=$failed, BLOCKED=$blocked, PENDING=$pending)")
[IO.File]::WriteAllLines($ReportPath, $lines, [Text.UTF8Encoding]::new($false))
$jsonPath = [IO.Path]::ChangeExtension($ReportPath, ".json")
[ordered]@{
    schemaVersion = 1
    scenario = "windows-release-desktop-acceptance"
    timestamp = $timestamp
    host = $env:COMPUTERNAME
    os = $osCaption
    user = $env:USERNAME
    acceptanceSessionId = [string]$hostBinding.acceptanceSessionId
    hostFingerprintSha256 = [string]$hostBinding.hostFingerprintSha256
    userFingerprintSha256 = [string]$hostBinding.userFingerprintSha256
    osVersion = [string]$hostBinding.osVersion
    osBuild = [string]$hostBinding.osBuild
    osArchitecture = [string]$hostBinding.osArchitecture
    isAdministrator = $isAdmin
    requireStandardUser = (-not $ChecklistOnly)
    candidateSha = [string]$github.candidateSha
    repository = [string]$github.repository
    releaseRunId = $releaseCheck[0].details.runId
    releaseRunUrl = [string]$releaseCheck[0].details.htmlUrl
    exeInstaller = $ExeInstaller
    exeSha256 = $exeSha
    launcher = $Launcher
    previousVersion = $PreviousVersion
    overall = $overall
    results = $results
} | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding utf8

Write-Host "Windows desktop release acceptance report: $ReportPath"
Write-Host "Overall: $overall"
if ($ChecklistOnly) { exit 0 }
if ($overall -ne "PASS") { exit 1 }
