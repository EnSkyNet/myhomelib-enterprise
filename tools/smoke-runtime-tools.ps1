$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
$Image = Join-Path $Root "dist\MyHomeLib"
$Java = Join-Path $Image "runtime\bin\java.exe"
$EpubCheck = Join-Path $Image "app\tools\validators\epubcheck\epubcheck.jar"
$MobiTool = Join-Path $Image "app\tools\converters\libmobi\mobitool.exe"
$Fbc = Join-Path $Image "app\tools\converters\fbc-windows-amd64\fbc.exe"
$PortableMarker = Join-Path $Image "myhomelib2.ini"

foreach ($File in @($Java, $EpubCheck)) {
    if (-not (Test-Path $File -PathType Leaf)) { throw "Packaged runtime tool is missing: $File" }
}
$RequireLibmobi = $env:MHL_REQUIRE_LIBMOBI -eq "1"
$RequireFbc = ($env:MHL_REQUIRE_FBC -eq "1") -or (Test-Path $PortableMarker -PathType Leaf)

$EpubVersion = (& $Java -jar $EpubCheck --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $EpubVersion -notmatch 'EPUBCheck v5\.4\.0') {
    throw "Unexpected packaged EPUBCheck version: $EpubVersion"
}

$MobiVersion = ""
if (Test-Path $MobiTool -PathType Leaf) {
    $MobiVersion = (& $MobiTool -v 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $MobiVersion -notmatch 'libmobi:\s+0\.12') {
        throw "Unexpected packaged libmobi version: $MobiVersion"
    }
} elseif ($RequireLibmobi) {
    throw "Packaged runtime tool is missing: $MobiTool"
} else {
    Write-Warning "Packaged mobitool is absent; optional MOBI/AZW conversion tool was not bundled."
}


$FbcVersion = ""
if (Test-Path $Fbc -PathType Leaf) {
    $FbcVersion = (& $Fbc --version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Packaged FBC version probe failed: $FbcVersion" }

    $SampleFb2 = Join-Path $Root "myhomelib-reader\src\test\resources\golden\reader-rich.fb2"
    if (-not (Test-Path $SampleFb2 -PathType Leaf)) { throw "FBC smoke source is missing: $SampleFb2" }
    $SmokeDir = Join-Path ([System.IO.Path]::GetTempPath()) ("myhomelib-fbc-smoke-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force $SmokeDir | Out-Null
    try {
        $SmokeEpub = Join-Path $SmokeDir "fbc-smoke.epub"
        $FbcOutput = (& $Fbc convert --to epub3 --output-file $SmokeEpub $SampleFb2 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $SmokeEpub -PathType Leaf) -or (Get-Item $SmokeEpub).Length -le 0) {
            throw "Packaged FBC real conversion failed: $FbcOutput"
        }
        $ValidationOutput = (& $Java -jar $EpubCheck $SmokeEpub 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) {
            throw "EPUB produced by packaged FBC failed EPUBCheck: $ValidationOutput"
        }
    } finally {
        Remove-Item -Recurse -Force $SmokeDir -ErrorAction SilentlyContinue
    }
} elseif ($RequireFbc) {
    throw "Packaged runtime tool is missing: $Fbc"
} else {
    Write-Warning "Packaged FBC is absent; FB2 conversion smoke was skipped for this non-portable package."
}

Write-Host "Packaged runtime tools smoke: PASS"
Write-Host $EpubVersion
if (-not [string]::IsNullOrWhiteSpace($MobiVersion)) { Write-Host $MobiVersion }
if (-not [string]::IsNullOrWhiteSpace($FbcVersion)) { Write-Host $FbcVersion }
