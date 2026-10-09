param(
    [Parameter(Mandatory=$true)][string]$CandidateSha,
    [string]$Launcher = "",
    [string]$CalibreExe = "",
    [string]$LibmobiExe = "",
    [string]$HostBindingPath = "target\windows-host-binding\windows-host-binding.json",
    [string]$DesktopAcceptancePath = "target\windows-release-desktop-acceptance\desktop-acceptance.json",
    [string]$ReportPath = "target\post-r5-live-evidence\windows-post-r5-functional\windows-post-r5-functional.md",
    [string]$EvidenceDir = "",
    [switch]$ChecklistOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

if ($CandidateSha -notmatch '^[0-9a-fA-F]{40}$') { throw "CandidateSha must be a full 40-character Git SHA." }
if ($env:OS -ne "Windows_NT" -and -not $ChecklistOnly) { throw "Post-R5 Windows acceptance must run on Windows." }

$ReportPath = [IO.Path]::GetFullPath($ReportPath)
New-Item -ItemType Directory -Force (Split-Path -Parent $ReportPath) | Out-Null
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) { $EvidenceDir = Join-Path (Split-Path -Parent $ReportPath) "evidence" }
$EvidenceDir = [IO.Path]::GetFullPath($EvidenceDir)
New-Item -ItemType Directory -Force $EvidenceDir | Out-Null

if (-not $ChecklistOnly) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "Final post-R5 Windows acceptance requires a standard/non-elevated user."
    }
    . .\tools\windows-acceptance-host.ps1
    $hostBinding = Get-VerifiedMyHomeLibWindowsAcceptanceHostBinding -Path $HostBindingPath -CandidateSha $CandidateSha
} else {
    $hostBinding = [pscustomobject]@{
        acceptanceSessionId = "CHECKLIST-ONLY"
        hostFingerprintSha256 = ""
        userFingerprintSha256 = ""
        osVersion = ""
        osBuild = ""
        osArchitecture = ""
    }
}

function Get-RelativeEvidencePath {
    param([string]$TargetPath)
    $base = (Split-Path -Parent $ReportPath).TrimEnd([char[]]@('\','/')) + [IO.Path]::DirectorySeparatorChar
    $baseUri = [Uri]::new($base)
    $targetUri = [Uri]::new([IO.Path]::GetFullPath($TargetPath))
    return ([Uri]::UnescapeDataString($baseUri.MakeRelativeUri($targetUri).ToString())).Replace('/', [IO.Path]::DirectorySeparatorChar)
}

function Get-EvidenceSha256 {
    param([string]$Evidence)
    if ([string]::IsNullOrWhiteSpace($Evidence)) { return "" }
    $base = Split-Path -Parent $ReportPath
    $full = [IO.Path]::GetFullPath((Join-Path $base $Evidence))
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "Evidence file not found for hashing: $Evidence" }
    return (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
}

$launcherSha256 = ""
$desktopAcceptanceEvidence = ""
$desktopAcceptanceSha256 = ""
if (-not $ChecklistOnly) {
    $DesktopAcceptancePath = [IO.Path]::GetFullPath($DesktopAcceptancePath)
    if (-not (Test-Path -LiteralPath $DesktopAcceptancePath -PathType Leaf)) {
        throw "Candidate-bound desktop acceptance report not found: $DesktopAcceptancePath"
    }
    $desktop = Get-Content -Raw -LiteralPath $DesktopAcceptancePath | ConvertFrom-Json
    if ($desktop.schemaVersion -ne 1 -or [string]$desktop.scenario -ne "windows-release-desktop-acceptance" -or [string]$desktop.overall -ne "PASS") {
        throw "Candidate-bound desktop acceptance report schema/scenario/overall is invalid."
    }
    if ([string]$desktop.candidateSha -ne $CandidateSha.ToLowerInvariant()) {
        throw "Desktop acceptance candidate SHA does not match CandidateSha."
    }
    foreach ($field in @("acceptanceSessionId","hostFingerprintSha256","userFingerprintSha256","osVersion","osBuild","osArchitecture")) {
        if ([string]$desktop.$field -ne [string]$hostBinding.$field) {
            throw "Desktop acceptance does not match the active Windows host/session for $field."
        }
    }
    $boundLauncher = [IO.Path]::GetFullPath([string]$desktop.launcher)
    if ([string]::IsNullOrWhiteSpace($Launcher)) { $Launcher = $boundLauncher } else { $Launcher = [IO.Path]::GetFullPath($Launcher) }
    if ($Launcher -ne $boundLauncher) { throw "Launcher does not match the launcher accepted by the candidate-bound desktop run." }
    if (-not (Test-Path -LiteralPath $Launcher -PathType Leaf)) { throw "Candidate-bound installed launcher not found: $Launcher" }
    $expectedLauncherSha256 = ([string]$desktop.launcherSha256).ToLowerInvariant()
    if ($expectedLauncherSha256 -notmatch '^[0-9a-f]{64}$') { throw "Desktop acceptance report has no valid launcherSha256." }
    $launcherSha256 = (Get-FileHash -LiteralPath $Launcher -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($launcherSha256 -ne $expectedLauncherSha256) {
        throw "Installed launcher SHA-256 does not match the candidate-bound desktop acceptance report."
    }
    $desktopCopy = Join-Path $EvidenceDir "runtime-desktop-acceptance.json"
    Copy-Item -LiteralPath $DesktopAcceptancePath -Destination $desktopCopy -Force
    $desktopAcceptanceEvidence = Get-RelativeEvidencePath $desktopCopy
    $desktopAcceptanceSha256 = Get-EvidenceSha256 $desktopAcceptanceEvidence
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
        $file = Join-Path $EvidenceDir ("{0}.png" -f $Id)
        $bitmap.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
        return Get-RelativeEvidencePath $file
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}

function Save-TextEvidence {
    param([string]$Id,[string[]]$Lines)
    $file = Join-Path $EvidenceDir ("{0}.txt" -f $Id)
    [IO.File]::WriteAllLines($file, $Lines, [Text.UTF8Encoding]::new($false))
    return Get-RelativeEvidencePath $file
}

function Get-CalibreResidueLines {
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("timestamp=" + (Get-Date).ToString("o")) | Out-Null
    $processes = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match '^(ebook-convert|calibre-parallel|calibre)$' })
    $lines.Add("calibreProcessCount=$($processes.Count)") | Out-Null
    foreach ($p in $processes) { $lines.Add("process=$($p.ProcessName);pid=$($p.Id)") | Out-Null }
    $workspaces = @(Get-ChildItem -LiteralPath ([IO.Path]::GetTempPath()) -Directory -Filter "mhl-calibre-*" -ErrorAction SilentlyContinue)
    $lines.Add("workspaceCount=$($workspaces.Count)") | Out-Null
    foreach ($w in $workspaces) { $lines.Add("workspace=$($w.FullName)") | Out-Null }
    return $lines.ToArray()
}

$results = New-Object System.Collections.Generic.List[object]
function Add-Result {
    param([string]$Id,[string]$Check,[string]$Outcome,[string]$Note,[string]$Evidence)
    $evidenceSha256 = Get-EvidenceSha256 $Evidence
    $results.Add([pscustomobject]@{ id=$Id; check=$Check; outcome=$Outcome; note=$Note; evidence=$Evidence; evidenceSha256=$evidenceSha256 }) | Out-Null
}

function Invoke-ManualCheck {
    param([string]$Id,[string]$Check)
    if ($ChecklistOnly) { Add-Result $Id $Check "PENDING" "" ""; return }
    Write-Host ""; Write-Host "$Id $Check"
    Read-Host "Prepare the exact state, then press Enter to capture screenshot evidence" | Out-Null
    $evidence = Save-DesktopScreenshot $Id
    do { $answer = (Read-Host "$Id result [PASS/FAIL/BLOCKED]").Trim().ToUpperInvariant() } while ($answer -notin @("PASS","FAIL","BLOCKED"))
    $note = Read-Host "Evidence note (required for PASS)"
    if ($answer -eq "PASS" -and [string]::IsNullOrWhiteSpace($note)) { throw "$Id PASS requires a non-empty evidence note." }
    Add-Result $Id $Check $answer $note $evidence
}

# R1/R2 repeated-selection / passive Book Details regression.
Invoke-ManualCheck "WBD-01" "Download one large FB2 once; repeated selection/back-and-forth causes no repeated HTTP download of the same local book."
Invoke-ManualCheck "WBD-02" "While passive Book Details analysis is active, Operations, Statistics, Reader, Conversion and navigation remain usable."
Invoke-ManualCheck "WBD-03" "Passive Book Details analysis does not create a library-exclusive/busy operation that blocks unrelated allowed work."
Invoke-ManualCheck "WBD-04" "The old completion message 'Завантажено: …' does not reappear as new activity after a later passive background task."
Invoke-ManualCheck "WBD-05" "Any real blocking conflict encountered has understandable UX and is visible in Operation Center; if none occurs, evidence note states that explicitly."
Invoke-ManualCheck "WBD-06" "No uncaught JavaFX handler exception appears during the repeated-selection/navigation scenario."

# Calibre Windows acceptance.
if ($ChecklistOnly) {
    Add-Result "CAL-01" "Record actual Calibre/libmobi versions from the supplied Windows bundle." "PENDING" "" ""
} else {
    if ([string]::IsNullOrWhiteSpace($CalibreExe) -or -not (Test-Path $CalibreExe -PathType Leaf)) { throw "CalibreExe must point to the supplied ebook-convert.exe." }
    if ([string]::IsNullOrWhiteSpace($LibmobiExe) -or -not (Test-Path $LibmobiExe -PathType Leaf)) { throw "LibmobiExe must point to the supplied mobitool.exe." }
    $calibreVersion = (& $CalibreExe --version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Cannot read Calibre version from $CalibreExe" }
    $mobiVersion = (& $LibmobiExe -v 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Cannot read libmobi version from $LibmobiExe" }
    $ev = Save-TextEvidence "CAL-01" @("Calibre=$calibreVersion", "libmobi=$mobiVersion", "CalibreExe=$CalibreExe", "LibmobiExe=$LibmobiExe")
    Add-Result "CAL-01" "Record actual Calibre/libmobi versions from the supplied Windows bundle." "PASS" "Calibre=$calibreVersion; libmobi=$mobiVersion" $ev
}
Invoke-ManualCheck "CAL-02" "Confirm effective Calibre timeout is 1800 s for default and legacy 300 s configuration."

if ($ChecklistOnly) {
    Add-Result "CAL-03" "Complete FB2 → EPUB through MyHomeLib with elapsed time >300 s and no legacy 300 s timeout." "PENDING" "" ""
} else {
    Write-Host ""; Write-Host "CAL-03 Start the exact large FB2 → EPUB conversion through MyHomeLib."
    Read-Host "Press Enter immediately before starting the conversion" | Out-Null
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Read-Host "After successful conversion completes, press Enter immediately" | Out-Null
    $sw.Stop()
    $evidence = Save-DesktopScreenshot "CAL-03"
    $seconds = [math]::Round($sw.Elapsed.TotalSeconds, 3)
    $outcome = if ($seconds -gt 300) { "PASS" } else { "FAIL" }
    $note = "Measured UI conversion elapsedSeconds=$seconds; requirement >300 s."
    Add-Result "CAL-03" "Complete FB2 → EPUB through MyHomeLib with elapsed time >300 s and no legacy 300 s timeout." $outcome $note $evidence
}
Invoke-ManualCheck "CAL-04" "Open/validate the produced EPUB and confirm representative content is readable."

if ($ChecklistOnly) {
    Add-Result "CAL-05" "After successful conversion, no mhl-calibre-* workspace/process residue remains." "PENDING" "" ""
} else {
    $lines = Get-CalibreResidueLines
    $ev = Save-TextEvidence "CAL-05" $lines
    $clean = ($lines -contains "calibreProcessCount=0") -and ($lines -contains "workspaceCount=0")
    Add-Result "CAL-05" "After successful conversion, no mhl-calibre-* workspace/process residue remains." $(if($clean){"PASS"}else{"FAIL"}) ($lines -join "; ") $ev
}

if ($ChecklistOnly) {
    Add-Result "CAL-06" "Cancel active Calibre: operation is CANCELLED, full process tree exits, input is unlocked and workspace is removable/removed." "PENDING" "" ""
} else {
    Write-Host ""; Write-Host "CAL-06 Start a Calibre conversion, wait until ebook-convert/calibre-parallel is active, then cancel it from MyHomeLib."
    Read-Host "After MyHomeLib reports cancellation, press Enter" | Out-Null
    $lines = Get-CalibreResidueLines
    $ev = Save-TextEvidence "CAL-06" $lines
    $clean = ($lines -contains "calibreProcessCount=0") -and ($lines -contains "workspaceCount=0")
    $note = Read-Host "Evidence note confirming UI state=CANCELLED and input/workspace lock behavior"
    if ([string]::IsNullOrWhiteSpace($note)) { throw "CAL-06 PASS requires a note." }
    Add-Result "CAL-06" "Cancel active Calibre: operation is CANCELLED, full process tree exits, input is unlocked and workspace is removable/removed." $(if($clean){"PASS"}else{"FAIL"}) ($note + "; " + ($lines -join "; ")) $ev
}

if ($ChecklistOnly) {
    Add-Result "CAL-07" "Close MyHomeLib during active conversion; no orphan Calibre process or locked workspace remains." "PENDING" "" ""
} else {
    Write-Host ""; Write-Host "CAL-07 Start a Calibre conversion and close MyHomeLib while it is active."
    Read-Host "After MyHomeLib has closed, press Enter" | Out-Null
    Start-Sleep -Seconds 2
    $lines = Get-CalibreResidueLines
    $ev = Save-TextEvidence "CAL-07" $lines
    $clean = ($lines -contains "calibreProcessCount=0") -and ($lines -contains "workspaceCount=0")
    Add-Result "CAL-07" "Close MyHomeLib during active conversion; no orphan Calibre process or locked workspace remains." $(if($clean){"PASS"}else{"FAIL"}) ($lines -join "; ") $ev
}
Invoke-ManualCheck "CAL-08" "Batch ≥3 books: at most 2 external conversions run concurrently; queued work does not start after Cancel; partial failure preserves successful outputs."
Invoke-ManualCheck "CAL-09" "During Calibre conversion, unrelated local/remote book access and navigation remain usable."

# Packaged Windows / updater / DPAPI / DPI.
Invoke-ManualCheck "WIN-01" "Packaged candidate clean install succeeds for a standard user."
Invoke-ManualCheck "WIN-02" "Portable candidate launches and keeps portable data behavior."
Invoke-ManualCheck "WIN-03" "Upgrade from the real previous candidate succeeds with expected data retention."
Invoke-ManualCheck "WIN-04" "Updater download → stage → restart completes on the packaged candidate."
Invoke-ManualCheck "WIN-05" "Updater handles process/file locks and helper replacement without stale locked files."
Invoke-ManualCheck "WIN-06" "Successful updated launch writes/observes the expected health marker."
Invoke-ManualCheck "WIN-07" "Forced failed-start exercises rollback and restores the prior working installation."
Invoke-ManualCheck "WIN-08" "Downgrade, wrong-platform and invalid-signature update attempts are rejected."
Invoke-ManualCheck "WIN-09" "DPAPI-bound secrets remain usable after restart and supported upgrade and are not silently exposed in files/logs."
Invoke-ManualCheck "WIN-10" "Normal/high-DPI packaged UI remains usable; detailed 100/125/150/200 evidence is collected by windows-ui-acceptance.ps1."
Invoke-ManualCheck "WIN-11" "Uninstall/rollback cleanup matches documented behavior without deleting preserved user data unexpectedly."
Invoke-ManualCheck "WIN-12" "Authenticode/signing/distribution path is verified on the exact candidate; if infrastructure is unavailable, keep this row BLOCKED rather than claiming PASS."

$failed = @($results | Where-Object { $_.outcome -eq "FAIL" }).Count
$blocked = @($results | Where-Object { $_.outcome -eq "BLOCKED" }).Count
$pending = @($results | Where-Object { $_.outcome -eq "PENDING" }).Count
$overall = if ($failed -eq 0 -and $blocked -eq 0 -and $pending -eq 0) { "PASS" } elseif ($ChecklistOnly) { "PENDING" } else { "FAIL" }
$timestamp = (Get-Date).ToString("o")
$osCaption = if ($ChecklistOnly) { "CHECKLIST-ONLY" } else { try { (Get-CimInstance Win32_OperatingSystem).Caption } catch { [Environment]::OSVersion.VersionString } }

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# MyHomeLib — post-R5 Windows functional acceptance")
$lines.Add("")
$lines.Add("- Timestamp: $timestamp")
$lines.Add("- Candidate SHA: ``$($CandidateSha.ToLowerInvariant())``")
$lines.Add("- Host: $env:COMPUTERNAME")
$lines.Add("- Launcher SHA-256: ``$launcherSha256``")
$lines.Add("- Desktop acceptance evidence: ``$desktopAcceptanceEvidence``")
$lines.Add("- Desktop acceptance SHA-256: ``$desktopAcceptanceSha256``")
$lines.Add("- Overall: **$overall**")
$lines.Add("")
$lines.Add("| ID | Result | Check | Evidence | Evidence SHA-256 | Note |")
$lines.Add("|---|---|---|---|---|---|")
foreach ($row in $results) {
    $check = ([string]$row.check).Replace("|","\|")
    $note = ([string]$row.note).Replace("|","\|").Replace("`r"," ").Replace("`n"," ")
    $evidence = ([string]$row.evidence).Replace("|","\|")
    $lines.Add("| $($row.id) | $($row.outcome) | $check | $evidence | $($row.evidenceSha256) | $note |")
}
[IO.File]::WriteAllLines($ReportPath, $lines, [Text.UTF8Encoding]::new($false))
$jsonPath = [IO.Path]::ChangeExtension($ReportPath, ".json")
[ordered]@{
    schemaVersion = 1
    scenario = "windows-post-r5-functional"
    timestamp = $timestamp
    overall = $overall
    candidateSha = $CandidateSha.ToLowerInvariant()
    host = $env:COMPUTERNAME
    user = [Environment]::UserName
    os = $osCaption
    acceptanceSessionId = [string]$hostBinding.acceptanceSessionId
    hostFingerprintSha256 = [string]$hostBinding.hostFingerprintSha256
    userFingerprintSha256 = [string]$hostBinding.userFingerprintSha256
    osVersion = [string]$hostBinding.osVersion
    osBuild = [string]$hostBinding.osBuild
    osArchitecture = [string]$hostBinding.osArchitecture
    launcher = $Launcher
    launcherSha256 = $launcherSha256
    desktopAcceptanceEvidence = $desktopAcceptanceEvidence
    desktopAcceptanceSha256 = $desktopAcceptanceSha256
    calibreExe = $CalibreExe
    libmobiExe = $LibmobiExe
    results = $results
} | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding utf8

Write-Host "Report: $jsonPath"
Write-Host "Overall: $overall"
if ($ChecklistOnly) { exit 0 }
if ($overall -ne "PASS") { exit 1 }
