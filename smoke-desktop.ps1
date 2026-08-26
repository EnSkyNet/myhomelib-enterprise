$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$launcher = "dist\MyHomeLib\MyHomeLib.exe"
if (-not (Test-Path $launcher -PathType Leaf)) { throw "Packaged MyHomeLib launcher not found: $launcher" }
# jpackage Windows launchers are GUI executables by default; use the process exit
# code rather than relying on inherited stdout/console attachment.
$process = Start-Process -FilePath $launcher -ArgumentList "--release-smoke" -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Packaged launcher smoke failed with exit code $($process.ExitCode)" }
Write-Host "Desktop packaged-launcher smoke: PASS"
