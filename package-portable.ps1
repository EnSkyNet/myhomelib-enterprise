param([string]$Version = "")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Version)) {
    [xml]$rootPom = Get-Content -Raw "pom.xml"
    $Version = if ($env:MHL_VERSION) { $env:MHL_VERSION } else { [string]$rootPom.project.version }
}
if ([string]::IsNullOrWhiteSpace($Version)) { throw "Cannot determine application version" }
# Portable Windows builds must include the specialized FB2 converter. Without it,
# AUTO can legitimately fall back to a system Calibre installation, turning a normal
# FB2 -> EPUB conversion into a many-minute external conversion.
$previousRequireFbc = $env:MHL_REQUIRE_FBC
try {
    $env:MHL_REQUIRE_FBC = "1"
    & .\package-desktop.ps1 -Type app-image
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    $env:MHL_REQUIRE_FBC = $previousRequireFbc
}
$image = "dist\MyHomeLib"
if (-not (Test-Path $image -PathType Container)) { throw "jpackage app-image not found: $image" }
$launcher = Join-Path $image "MyHomeLib.exe"
if (-not (Test-Path $launcher -PathType Leaf)) { throw "jpackage launcher not found: $launcher" }
# A portable archive must be portable immediately after extraction.  The marker
# is intentionally created beside the native launcher, which is the location
# used by AppPaths even when the process starts from an unrelated working dir.
$marker = Join-Path (Split-Path $launcher -Parent) "myhomelib2.ini"
Set-Content -Path $marker -Value "" -NoNewline -Encoding ascii

# Keep the historical portable entry points expected by the Windows acceptance
# contract. They are compatibility launchers only: the jpackage app-image already
# contains its own Java 21 runtime, so neither launcher depends on system Java.
$cmdLauncher = Join-Path (Split-Path $launcher -Parent) "Start-MyHomeLib.cmd"
@'
@echo off
setlocal
cd /d "%~dp0"
start "" /wait "%~dp0MyHomeLib.exe" %*
set "MHL_EXIT_CODE=%ERRORLEVEL%"
exit /b %MHL_EXIT_CODE%
'@ | Set-Content -Path $cmdLauncher -Encoding ascii

$vbsLauncher = Join-Path (Split-Path $launcher -Parent) "MyHomeLib.vbs"
@'
Option Explicit
Dim shell, fso, baseDir, commandLine, index, exitCode
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
baseDir = fso.GetParentFolderName(WScript.ScriptFullName)
commandLine = Chr(34) & fso.BuildPath(baseDir, "MyHomeLib.exe") & Chr(34)
For index = 0 To WScript.Arguments.Count - 1
    commandLine = commandLine & " " & QuoteArgument(WScript.Arguments(index))
Next
exitCode = shell.Run(commandLine, 1, True)
WScript.Quit exitCode

Function QuoteArgument(value)
    QuoteArgument = Chr(34) & Replace(CStr(value), Chr(34), Chr(34) & Chr(34)) & Chr(34)
End Function
'@ | Set-Content -Path $vbsLauncher -Encoding ascii
$rawArch = if ($env:PROCESSOR_ARCHITECTURE) { $env:PROCESSOR_ARCHITECTURE.ToLowerInvariant() } else { "unknown" }
$portableArch = switch -Regex ($rawArch) {
    '^(amd64|x86_64)$' { 'x64'; break }
    '^(arm64|aarch64)$' { 'arm64'; break }
    '^(x86|i[3-6]86)$' { 'x86'; break }
    default { ($rawArch -replace '[^a-z0-9._-]', '-') }
}
# R18 has a single canonical Windows portable artifact name.  Keeping this
# stable is part of the release contract used by smoke/evidence tooling.
$archive = "dist\MyHomeLib-R18-$Version-portable-windows-$portableArch.zip"
Get-ChildItem -Path "dist" -Filter "MyHomeLib-R18-$Version-portable-windows-*.zip" -File -ErrorAction SilentlyContinue | Remove-Item -Force
# Remove a stale pre-R18-name Windows portable for the same version so release
# collection cannot accidentally publish two portable candidates.
Get-ChildItem -Path "dist" -Filter "myhomelib-$Version-windows-*.zip" -File -ErrorAction SilentlyContinue | Remove-Item -Force
Compress-Archive -Path $image -DestinationPath $archive -CompressionLevel Optimal
Write-Host "Portable desktop archive: $archive"
