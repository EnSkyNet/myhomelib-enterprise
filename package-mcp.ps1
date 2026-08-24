$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& .\mvnw.cmd -pl myhomelib-mcp -am package @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "MCP shaded JAR: myhomelib-mcp/target/myhomelib-mcp-1.0.0.jar"
