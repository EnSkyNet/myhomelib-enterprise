param([string]$Version = "")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = if ($env:MHL_VERSION) { $env:MHL_VERSION } else { "1.0.0" }
}
& .\package-desktop.ps1 -Type app-image
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$image = "dist\MyHomeLib"
if (-not (Test-Path $image -PathType Container)) { throw "jpackage app-image not found: $image" }
$arch = if ($env:PROCESSOR_ARCHITECTURE) { $env:PROCESSOR_ARCHITECTURE.ToLowerInvariant() } else { "unknown" }
$archive = "dist\myhomelib-$Version-windows-$arch.zip"
Remove-Item -Force $archive -ErrorAction SilentlyContinue
Compress-Archive -Path $image -DestinationPath $archive -CompressionLevel Optimal
Write-Host "Portable desktop archive: $archive"
