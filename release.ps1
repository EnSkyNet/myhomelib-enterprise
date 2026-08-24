$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& .\mvnw.cmd clean verify -Pproduction
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& .\package-desktop.ps1 -Type app-image
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item -Force "myhomelib-mcp\target\myhomelib-mcp-1.0.0.jar" dist
Copy-Item -Force "myhomelib-bootstrap\target\myhomelib-bootstrap-1.0.0.jar" dist
& .\checksums.ps1 -Directory dist
Write-Host "Release candidate artifacts are in dist/. Run the clean-machine checklist before signing a FINAL release."
