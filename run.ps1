$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& .\mvnw.cmd -pl myhomelib-bootstrap -am install -DskipTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& .\mvnw.cmd -f myhomelib-bootstrap/pom.xml javafx:run @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
