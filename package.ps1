$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& .\mvnw.cmd -pl myhomelib-bootstrap -am clean package -DskipTests @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Executable Spring Boot JAR: myhomelib-bootstrap/target/myhomelib-bootstrap-7.1.0.jar"
