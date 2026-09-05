param([string]$Archive = "")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

[xml]$rootPom = Get-Content -Raw "pom.xml"
$Version = if ($env:MHL_VERSION) { $env:MHL_VERSION } else { [string]$rootPom.project.version }
if ([string]::IsNullOrWhiteSpace($Version)) { throw "Cannot determine application version from pom.xml" }

if ([string]::IsNullOrWhiteSpace($Archive)) {
    $matches = @(Get-ChildItem -Path "dist" -Filter "myhomelib-$Version-windows-*.zip" -File -ErrorAction SilentlyContinue)
    if ($matches.Count -ne 1) { throw "Expected exactly one Windows portable archive, found $($matches.Count)" }
    $Archive = $matches[0].FullName
}
if (-not (Test-Path $Archive -PathType Leaf)) { throw "Portable archive not found: $Archive" }

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("myhomelib-portable-smoke-" + [Guid]::NewGuid().ToString("N"))
$extract = Join-Path $tempRoot "extract"
$home = Join-Path $tempRoot "home"
$cwd = Join-Path $tempRoot "cwd"
$oldJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$pushed = $false
try {
    New-Item -ItemType Directory -Force $extract, $home, $cwd | Out-Null
    Expand-Archive -Path $Archive -DestinationPath $extract -Force
    $launcher = Join-Path $extract "MyHomeLib\MyHomeLib.exe"
    if (-not (Test-Path $launcher -PathType Leaf)) { throw "Extracted launcher not found: $launcher" }
    $launcherDir = Split-Path $launcher -Parent
    $marker = Join-Path $launcherDir "myhomelib2.ini"
    if (-not (Test-Path $marker -PathType Leaf)) { throw "Portable marker is missing beside launcher: $marker" }

    # Keep the profile assertion isolated even on a developer workstation.  The
    # portable path itself must win over this synthetic user.home value.
    $env:JAVA_TOOL_OPTIONS = "-Duser.home=`"$home`""
    Push-Location $cwd
    $pushed = $true
    $process = Start-Process -FilePath $launcher -ArgumentList "--release-smoke" -Wait -PassThru
    Pop-Location
    $pushed = $false
    if ($process.ExitCode -ne 0) { throw "Extracted portable launcher smoke failed with exit code $($process.ExitCode)" }

    $dataDir = Join-Path $launcherDir "data"
    if (-not (Test-Path $dataDir -PathType Container)) { throw "Portable data directory was not created beside launcher: $dataDir" }
    if (Test-Path (Join-Path $home ".myhomelibcorp")) { throw "Portable launch wrote to the user profile" }
    Write-Host "Extracted portable archive smoke: PASS ($Archive)"
}
finally {
    if ($pushed) { Pop-Location }
    $env:JAVA_TOOL_OPTIONS = $oldJavaToolOptions
    Remove-Item -Recurse -Force $tempRoot -ErrorAction SilentlyContinue
}
