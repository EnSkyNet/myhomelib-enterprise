$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& .\tools\invoke-maven.ps1 -pl myhomelib-bootstrap -am install -DskipTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& .\tools\invoke-maven.ps1 -f myhomelib-bootstrap/pom.xml javafx:run @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
