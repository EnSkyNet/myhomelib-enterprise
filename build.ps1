$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& .\tools\invoke-maven.ps1 clean verify @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
