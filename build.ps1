$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& .\mvnw.cmd clean verify @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
