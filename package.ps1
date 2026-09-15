$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& .\tools\invoke-maven.ps1 -pl myhomelib-bootstrap -am clean package -DskipTests @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Executable Spring Boot JAR: myhomelib-bootstrap/target/myhomelib-bootstrap-8.0.0.jar"
