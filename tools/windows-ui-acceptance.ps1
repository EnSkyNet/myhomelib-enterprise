param(
    [Parameter(Mandatory=$true)]
    [ValidateSet(100, 125, 150, 200)]
    [int]$Scale,
    [string]$Launcher = "",
    [string]$ReportPath = "",
    [string]$EvidenceDir = "",
    [switch]$ChecklistOnly,
    [switch]$SkipLauncherSmoke,
    [string]$HostBindingPath = "target\windows-host-binding\windows-host-binding.json"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

if ($env:OS -ne "Windows_NT") { throw "Windows UI/DPI acceptance must run on Windows." }
. .\tools\windows-acceptance-host.ps1
$hostBinding = Get-VerifiedMyHomeLibWindowsAcceptanceHostBinding -Path $HostBindingPath
if ([string]::IsNullOrWhiteSpace($Launcher)) {
    $Launcher = Join-Path $env:LOCALAPPDATA "MyHomeLib\MyHomeLib.exe"
}
$Launcher = [System.IO.Path]::GetFullPath($Launcher)
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $Root "target\windows-ui-acceptance-$Scale.md"
}
$ReportPath = [System.IO.Path]::GetFullPath($ReportPath)
New-Item -ItemType Directory -Force (Split-Path -Parent $ReportPath) | Out-Null
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) { $EvidenceDir = Join-Path (Split-Path -Parent $ReportPath) "dpi-$Scale-evidence" }
$EvidenceDir = [System.IO.Path]::GetFullPath($EvidenceDir)
New-Item -ItemType Directory -Force $EvidenceDir | Out-Null

$observedDpi = "unavailable"
$observedDpiValue = $null
$monitorCount = $null
try {
    if (-not ("MyHomeLibNativeDpi" -as [type])) {
        Add-Type @"
using System.Runtime.InteropServices;
public static class MyHomeLibNativeDpi {
    [DllImport("user32.dll")]
    public static extern uint GetDpiForSystem();
    [DllImport("user32.dll")]
    public static extern int GetSystemMetrics(int nIndex);
}
"@
    }
    $dpi = [MyHomeLibNativeDpi]::GetDpiForSystem()
    $monitors = [MyHomeLibNativeDpi]::GetSystemMetrics(80) # SM_CMONITORS
    if ($monitors -gt 0) { $monitorCount = [int]$monitors }
    if ($dpi -gt 0) {
        $observedDpiValue = [int]$dpi
        $observedDpi = "$dpi DPI (~$([math]::Round(($dpi / 96.0) * 100))%)"
    }
}
catch {
    $observedDpi = "unavailable: $($_.Exception.Message)"
}


function Get-RelativeEvidencePath {
    param(
        [Parameter(Mandatory=$true)][string]$BaseDirectory,
        [Parameter(Mandatory=$true)][string]$TargetPath
    )
    $baseFull = [System.IO.Path]::GetFullPath($BaseDirectory).TrimEnd([char[]]@('\', '/')) + [System.IO.Path]::DirectorySeparatorChar
    $targetFull = [System.IO.Path]::GetFullPath($TargetPath)
    $baseUri = New-Object System.Uri -ArgumentList $baseFull
    $targetUri = New-Object System.Uri -ArgumentList $targetFull
    $relative = [System.Uri]::UnescapeDataString($baseUri.MakeRelativeUri($targetUri).ToString())
    return $relative.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
}

$results = New-Object System.Collections.Generic.List[object]
function Add-Result {
    param([string]$Id, [string]$Check, [string]$Outcome, [string]$Note)
    $results.Add([pscustomobject]@{ Id=$Id; Check=$Check; Outcome=$Outcome; Note=$Note; Evidence="" }) | Out-Null
}

# Guard against an accidentally mislabelled DPI run.  GetDpiForSystem is an
# automatic cross-check of the Windows system DPI; P4-01 remains mandatory
# because a multi-monitor test must still confirm the scale of the monitor that
# actually hosts the MyHomeLib window.
$expectedDpi = [int][math]::Round(96.0 * $Scale / 100.0)
if ($null -eq $observedDpiValue) {
    Add-Result -Id "AUTO-0" -Check "Observed Windows system DPI matches requested $Scale% ($expectedDpi DPI)" -Outcome "BLOCKED" -Note $observedDpi
}
elseif ([math]::Abs($observedDpiValue - $expectedDpi) -le 1) {
    Add-Result -Id "AUTO-0" -Check "Observed Windows system DPI matches requested $Scale% ($expectedDpi DPI)" -Outcome "PASS" -Note $observedDpi
}
elseif ($null -ne $monitorCount -and $monitorCount -gt 1) {
    Add-Result -Id "AUTO-0" -Check "Observed Windows system DPI matches requested $Scale% ($expectedDpi DPI)" -Outcome "BLOCKED" -Note "Observed $observedDpi with $monitorCount monitors. System DPI can differ from the monitor hosting MyHomeLib; verify P4-01 on that monitor or rerun on a single-monitor acceptance VM."
}
else {
    Add-Result -Id "AUTO-0" -Check "Observed Windows system DPI matches requested $Scale% ($expectedDpi DPI)" -Outcome "FAIL" -Note "Observed $observedDpi. Change Windows Display scaling and sign out/restart the test session if Windows requires it, then rerun this scale."
}

function Save-DesktopScreenshot {
    param([Parameter(Mandatory=$true)][string]$Id)
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms
    $bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
    $bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($bounds.Left, $bounds.Top, 0, 0, $bitmap.Size)
        $file = Join-Path $EvidenceDir ("{0}-{1}.png" -f $Scale, $Id)
        $bitmap.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
        return Get-RelativeEvidencePath -BaseDirectory (Split-Path -Parent $ReportPath) -TargetPath $file
    }
    finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function Invoke-ManualCheck {
    param([string]$Id, [string]$Check)
    if ($ChecklistOnly) {
        Add-Result -Id $Id -Check $Check -Outcome "PENDING" -Note ""
        return
    }
    Write-Host ""
    Write-Host "$Id $Check"
    Read-Host "Prepare the exact UI state for this check, then press Enter to capture screenshot evidence" | Out-Null
    $evidence = Save-DesktopScreenshot -Id $Id
    do {
        $answer = (Read-Host "$Id result [PASS/FAIL/BLOCKED]").Trim().ToUpperInvariant()
    } while ($answer -notin @("PASS", "FAIL", "BLOCKED"))
    $note = Read-Host "Note (optional)"
    $row = [pscustomobject]@{ Id=$Id; Check=$Check; Outcome=$answer; Note=$note; Evidence=$evidence }
    $results.Add($row) | Out-Null
    if ($answer -eq "PASS") {
        # Save-DesktopScreenshot records a path relative to the report directory so the
        # evidence bundle stays portable. Resolve it against that same directory here;
        # resolving from $Root would incorrectly reject the default target\dpi-*-evidence path.
        $evidencePath = [System.IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $ReportPath) $evidence))
        if (-not (Test-Path $evidencePath -PathType Leaf)) {
            throw "$Id cannot PASS without screenshot evidence: $evidencePath"
        }
    }
}

if (-not $SkipLauncherSmoke) {
    if (-not (Test-Path $Launcher -PathType Leaf)) {
        Add-Result -Id "AUTO-1" -Check "Packaged launcher exists and --release-smoke exits 0" -Outcome "FAIL" -Note "Launcher not found: $Launcher"
    }
    else {
        $process = Start-Process -FilePath $Launcher -ArgumentList "--release-smoke" -Wait -PassThru
        $outcome = if ($process.ExitCode -eq 0) { "PASS" } else { "FAIL" }
        Add-Result -Id "AUTO-1" -Check "Packaged launcher exists and --release-smoke exits 0" -Outcome $outcome -Note "exit=$($process.ExitCode)"
    }
}

Invoke-ManualCheck "P4-01" "Windows Display scaling for the tested monitor is exactly $Scale%."

# GetDpiForSystem reports the system DPI, not necessarily the DPI of the monitor that
# hosts MyHomeLib. On a multi-monitor setup, allow the screenshot-backed P4-01 check
# to resolve the automatic BLOCKED diagnostic. A single-monitor mismatch remains FAIL.
$autoDpiRow = $results | Where-Object { $_.Id -eq "AUTO-0" } | Select-Object -First 1
$p4ScaleRow = $results | Where-Object { $_.Id -eq "P4-01" } | Select-Object -First 1
if ($null -ne $autoDpiRow -and $autoDpiRow.Outcome -eq "BLOCKED" -and
    $null -ne $monitorCount -and $monitorCount -gt 1 -and
    $null -ne $p4ScaleRow -and $p4ScaleRow.Outcome -eq "PASS") {
    $autoDpiRow.Outcome = "PASS"
    $autoDpiRow.Note = "$($autoDpiRow.Note) P4-01 supplied screenshot-backed confirmation for the monitor hosting MyHomeLib."
}

Invoke-ManualCheck "P4-02" "Main Window opens fully inside the client area; no sidebar/toolbar extends beyond the window."
Invoke-ManualCheck "P4-03" "Main Window left sidebar OFF -> ON repeated at least 3 times; center shrinks correctly and geometry does not grow."
Invoke-ManualCheck "P4-04" "Main Window right sidebar OFF -> ON repeated at least 3 times; center shrinks correctly and geometry does not grow."
Invoke-ManualCheck "P4-05" "Search opens and Author is the first meaningful column after the checkbox."
Invoke-ManualCheck "P4-06" "Search 'дорничев' returns promptly and the expected author/books are usable."
Invoke-ManualCheck "P4-07" "Search 'дорб' returns promptly and the expected prefix/substring result is usable."
Invoke-ManualCheck "P4-08" "Search 'Дмитрий Дорничев' works."
Invoke-ManualCheck "P4-09" "Search 'Дорничев Дмитрий' works."
Invoke-ManualCheck "P4-10" "Search remains correct with different case, extra spaces and Cyrillic input."
Invoke-ManualCheck "P4-11" "Search clear buttons and Select All work; Select All does not depend on expanded series."
Invoke-ManualCheck "P4-12" "Book Details opens; content/images/TOC remain inside the client area and are usable."
Invoke-ManualCheck "P4-13" "Reader opens a book, closes it and navigates pages without layout overflow."
Invoke-ManualCheck "P4-14" "Reader toolbar visible -> hidden -> visible restores the canvas to its original usable height."
Invoke-ManualCheck "P4-15" "Reader right sidebar OFF -> ON repeated at least 3 times; it never extends beyond the scene/client area."
Invoke-ManualCheck "P4-16" "Collection Wizard active-step highlighting is correct and online collection URL INPX handling is usable."
Invoke-ManualCheck "P4-17" "Backup and Restore complete successfully with the test library."
Invoke-ManualCheck "P4-18" "Back / Forward navigation returns to the expected workspace/filter state."
Invoke-ManualCheck "P4-19" "Followed Authors opens and remains usable after navigation/search."
Invoke-ManualCheck "P4-20" "Final geometry check: no sidebar, toolbar or content pane expanded the layout beyond the client area during this scale run."

$osCaption = try { (Get-CimInstance Win32_OperatingSystem).Caption } catch { [Environment]::OSVersion.VersionString }
$timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss K")
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# MyHomeLib Windows UI/DPI acceptance — $Scale%")
$lines.Add("")
$lines.Add("- Timestamp: $timestamp")
$lines.Add("- Host: $env:COMPUTERNAME")
$lines.Add("- OS: $osCaption")
$lines.Add("- Requested/manual scale: $Scale%")
$lines.Add("- GetDpiForSystem observation: $observedDpi")
$lines.Add("- Active monitor count (SM_CMONITORS): $(if ($null -eq $monitorCount) { 'unavailable' } else { $monitorCount })")
$lines.Add("- Launcher: ``$Launcher``")
$lines.Add("")
$lines.Add("| ID | Result | Check | Evidence | Note |")
$lines.Add("|---|---|---|---|---|")
foreach ($row in $results) {
    $check = ([string]$row.Check).Replace("|", "\\|")
    $note = ([string]$row.Note).Replace("|", "\\|").Replace("`r", " ").Replace("`n", " ")
    $evidence = ([string]$row.Evidence).Replace("|", "\|")
    $lines.Add("| $($row.Id) | $($row.Outcome) | $check | $evidence | $note |")
}
$lines.Add("")
$failed = @($results | Where-Object { $_.Outcome -eq "FAIL" }).Count
$blocked = @($results | Where-Object { $_.Outcome -eq "BLOCKED" }).Count
$pending = @($results | Where-Object { $_.Outcome -eq "PENDING" }).Count
$overall = if ($failed -eq 0 -and $blocked -eq 0 -and $pending -eq 0) { "PASS" } elseif ($ChecklistOnly) { "PENDING" } else { "FAIL" }
$lines.Add("**Overall: $overall** (FAIL=$failed, BLOCKED=$blocked, PENDING=$pending)")
[System.IO.File]::WriteAllLines($ReportPath, $lines, [System.Text.UTF8Encoding]::new($false))
$jsonPath = [System.IO.Path]::ChangeExtension($ReportPath, ".json")
[ordered]@{
    schemaVersion = 1
    scenario = "windows-ui-dpi-acceptance"
    timestamp = $timestamp
    host = $env:COMPUTERNAME
    user = [Environment]::UserName
    acceptanceSessionId = [string]$hostBinding.acceptanceSessionId
    hostFingerprintSha256 = [string]$hostBinding.hostFingerprintSha256
    userFingerprintSha256 = [string]$hostBinding.userFingerprintSha256
    osVersion = [string]$hostBinding.osVersion
    osBuild = [string]$hostBinding.osBuild
    osArchitecture = [string]$hostBinding.osArchitecture
    os = $osCaption
    scale = $Scale
    observedDpi = $observedDpi
    observedDpiValue = $observedDpiValue
    monitorCount = $monitorCount
    launcher = $Launcher
    overall = $overall
    results = $results
} | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding utf8
Write-Host "Windows UI/DPI acceptance report: $ReportPath"
Write-Host "Overall: $overall"

if ($ChecklistOnly) { exit 0 }
if ($overall -ne "PASS") { exit 1 }
