$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
[xml]$rootPom = Get-Content -Raw "pom.xml"
$Version = if ($env:MHL_VERSION) { $env:MHL_VERSION } else { [string]$rootPom.project.version }
if ([string]::IsNullOrWhiteSpace($Version)) { throw "Cannot determine application version" }

& .\mvnw.cmd clean verify -Pproduction
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$env:MHL_SKIP_BUILD = "1"
& .\package-portable.ps1 -Version $Version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& .\smoke-desktop.ps1
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& .\smoke-portable.ps1
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($env:MHL_SKIP_INSTALLER -ne "1") {
    & .\package-desktop.ps1 -Type exe
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# The app-image is an intermediate packaging directory. The versioned portable
# archive already contains it, so keep checksums/publication limited to artifacts.
Remove-Item -Recurse -Force "dist\MyHomeLib" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item -Force "myhomelib-mcp\target\myhomelib-mcp-$Version.jar" dist
Copy-Item -Force "myhomelib-bootstrap\target\myhomelib-bootstrap-$Version.jar" dist
& .\checksums.ps1 -Directory dist
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$validationArgs = @("tools/stage23-cross-platform-release-check.py", "--dist", "dist", "--require-checksums", "--require-portable")
if ($env:MHL_SKIP_INSTALLER -ne "1") { $validationArgs += "--expect-installer" }
& python @validationArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Release candidate artifacts are in dist/ and passed artifact validation."
