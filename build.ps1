$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if ($args.Count -gt 0 -and $args[0] -eq "--fast") {
    $extra = @()
    if ($args.Count -gt 1) { $extra = @($args[1..($args.Count - 1)]) }
    & .\tools\invoke-maven.ps1 -pl myhomelib-bootstrap -am package -DskipTests @extra
} else {
    & .\tools\invoke-maven.ps1 clean verify @args
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
