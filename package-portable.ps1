param([string]$Version = "")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Version)) {
    [xml]$rootPom = Get-Content -Raw "pom.xml"
    $Version = if ($env:MHL_VERSION) { $env:MHL_VERSION } else { [string]$rootPom.project.version }
}
if ([string]::IsNullOrWhiteSpace($Version)) { throw "Cannot determine application version" }
& .\package-desktop.ps1 -Type app-image
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$image = "dist\MyHomeLib"
if (-not (Test-Path $image -PathType Container)) { throw "jpackage app-image not found: $image" }
$launcher = Join-Path $image "MyHomeLib.exe"
if (-not (Test-Path $launcher -PathType Leaf)) { throw "jpackage launcher not found: $launcher" }
# A portable archive must be portable immediately after extraction.  The marker
# is intentionally created beside the native launcher, which is the location
# used by AppPaths even when the process starts from an unrelated working dir.
$marker = Join-Path (Split-Path $launcher -Parent) "myhomelib2.ini"
Set-Content -Path $marker -Value "" -NoNewline -Encoding ascii
$arch = if ($env:PROCESSOR_ARCHITECTURE) { $env:PROCESSOR_ARCHITECTURE.ToLowerInvariant() } else { "unknown" }
$archive = "dist\myhomelib-$Version-windows-$arch.zip"
Remove-Item -Force $archive -ErrorAction SilentlyContinue
Compress-Archive -Path $image -DestinationPath $archive -CompressionLevel Optimal
Write-Host "Portable desktop archive: $archive"
