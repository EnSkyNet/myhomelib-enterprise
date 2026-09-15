$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
& .\mvnw.cmd -o -B -ntp clean verify @args
exit $LASTEXITCODE
