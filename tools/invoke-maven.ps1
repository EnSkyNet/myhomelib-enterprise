$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Wrapper = Join-Path $Root "mvnw.cmd"

if (Test-Path $Wrapper -PathType Leaf) {
    & $Wrapper @args
    return
}

$Maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $Maven) { $Maven = Get-Command mvn -ErrorAction SilentlyContinue }
if (-not $Maven) {
    [Console]::Error.WriteLine("ERROR: Maven is required. The formal source archive intentionally does not bundle Maven or Maven Wrapper. Install Maven 3.9.6+ (or use a repository checkout that provides the wrapper) and retry.")
    $global:LASTEXITCODE = 127
    return
}

& $Maven.Source @args
