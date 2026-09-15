$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
Write-Host "Synchronizing Maven dependencies for Spring Boot 4.1.1 / JavaFX 21.0.12 / SQLite JDBC 3.53.4.0..."
& .\mvnw.cmd -B -ntp -U dependency:go-offline
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& .\mvnw.cmd -B -ntp -U test-compile -DskipTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Dependency cache prepared at $Root\.mvn\repository"
Write-Host "Now run: .\tools\verify-upgraded-offline.ps1"
