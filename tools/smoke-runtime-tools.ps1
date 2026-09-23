$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
$Image = Join-Path $Root "dist\MyHomeLib"
$Java = Join-Path $Image "runtime\bin\java.exe"
$EpubCheck = Join-Path $Image "app\tools\validators\epubcheck\epubcheck.jar"
$MobiTool = Join-Path $Image "app\tools\converters\libmobi\mobitool.exe"

foreach ($File in @($Java, $EpubCheck, $MobiTool)) {
    if (-not (Test-Path $File -PathType Leaf)) { throw "Packaged runtime tool is missing: $File" }
}

$EpubVersion = (& $Java -jar $EpubCheck --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $EpubVersion -notmatch 'EPUBCheck v5\.4\.0') {
    throw "Unexpected packaged EPUBCheck version: $EpubVersion"
}

$MobiVersion = (& $MobiTool -v 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $MobiVersion -notmatch 'libmobi:\s+0\.12') {
    throw "Unexpected packaged libmobi version: $MobiVersion"
}

Write-Host "Packaged runtime tools smoke: PASS"
Write-Host $EpubVersion
Write-Host $MobiVersion
