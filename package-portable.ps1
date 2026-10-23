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
if (-not (Test-Path -LiteralPath $image -PathType Container)) { throw "jpackage app-image not found: $image" }

# Work on a private copy: the native jpackage launcher may be read-locked by
# Windows scanning/inspection. Never patch the original desktop app-image in place.
$portableStageRoot = Join-Path $PSScriptRoot "target\windows-portable-stage"
Remove-Item -LiteralPath $portableStageRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $portableStageRoot -Force | Out-Null
Copy-Item -LiteralPath $image -Destination $portableStageRoot -Recurse -Force
$portableImage = Join-Path $portableStageRoot (Split-Path $image -Leaf)
if (-not (Test-Path -LiteralPath $portableImage -PathType Container)) {
    throw "Portable staging copy was not created: $portableImage"
}
$launcher = Join-Path $portableImage "MyHomeLib.exe"
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) { throw "Staged jpackage launcher not found: $launcher" }

# A portable archive must be portable immediately after extraction. The marker
# is intentionally created beside the native launcher, which is the location
# used by AppPaths even when the process starts from an unrelated working dir.
$marker = Join-Path $portableImage "myhomelib2.ini"
Set-Content -Path $marker -Value "" -NoNewline -Encoding ascii

# Keep the historical portable entry points expected by the Windows acceptance
# contract. They are compatibility launchers only: the jpackage app-image already
# contains its own Java 21 runtime, so neither launcher depends on system Java.
$cmdLauncher = Join-Path $portableImage "Start-MyHomeLib.cmd"
@'
@echo off
setlocal
cd /d "%~dp0"
start "" /wait "%~dp0MyHomeLib.exe" %*
set "MHL_EXIT_CODE=%ERRORLEVEL%"
exit /b %MHL_EXIT_CODE%
'@ | Set-Content -Path $cmdLauncher -Encoding ascii

$vbsLauncher = Join-Path $portableImage "MyHomeLib.vbs"
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
# R18 has a single canonical Windows portable artifact name. Keeping this
# stable is part of the acceptance contract used by smoke/evidence tooling.
$archive = "dist\MyHomeLib-R18-$Version-portable-windows-$portableArch.zip"
Get-ChildItem -Path "dist" -Filter "MyHomeLib-R18-$Version-portable-windows-*.zip" -File -ErrorAction SilentlyContinue | Remove-Item -Force
# Remove a stale pre-R18-name Windows portable for the same version so release
# collection cannot accidentally publish two portable candidates.
Get-ChildItem -Path "dist" -Filter "myhomelib-$Version-windows-*.zip" -File -ErrorAction SilentlyContinue | Remove-Item -Force

# Opt the native process into UTF-8 for Win32 ANSI APIs (Windows 10 1903+).
# The patched file lives in the isolated Portable staging copy, not the desktop image.
$utf8Manifest = Join-Path $PSScriptRoot "tools\windows-portable-utf8.manifest"
if (-not (Test-Path -LiteralPath $utf8Manifest -PathType Leaf)) {
    throw "Windows native launcher UTF-8 manifest is missing: $utf8Manifest"
}
$mtCommand = Get-Command "mt.exe" -ErrorAction SilentlyContinue
$mtPath = if ($null -ne $mtCommand) { $mtCommand.Source } else { "" }
if ([string]::IsNullOrWhiteSpace($mtPath)) {
    $kitsBin = Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10\bin"
    $candidates = @(Get-ChildItem -Path (Join-Path $kitsBin "*\x64\mt.exe") -File -ErrorAction SilentlyContinue |
        Sort-Object -Property FullName -Descending)
    if ($candidates.Count -gt 0) { $mtPath = $candidates[0].FullName }
}
if ([string]::IsNullOrWhiteSpace($mtPath) -or -not (Test-Path -LiteralPath $mtPath -PathType Leaf)) {
    throw "Windows SDK mt.exe is required to embed the UTF-8 launcher manifest"
}
# Copy-Item can preserve read-only attributes from the app-image; explicitly make
# the staged PE writable before mt.exe replaces its resource section.
[System.IO.File]::SetAttributes($launcher, [System.IO.FileAttributes]::Normal)
$outputResourceArgument = "-outputresource:$launcher;#1"
& $mtPath -manifest $utf8Manifest $outputResourceArgument
if ($LASTEXITCODE -ne 0) { throw "Failed to embed native launcher UTF-8 manifest (mt.exe exit $LASTEXITCODE) at staged path $launcher" }
$manifestProof = Join-Path $PSScriptRoot "target\windows-portable-native-manifest.xml"
New-Item -ItemType Directory -Path (Split-Path -Parent $manifestProof) -Force | Out-Null
Remove-Item -LiteralPath $manifestProof -Force -ErrorAction SilentlyContinue
& $mtPath "-inputresource:$launcher;#1" "-out:$manifestProof"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $manifestProof -PathType Leaf)) {
    throw "Cannot read back embedded UTF-8 native launcher manifest from staged path $launcher"
}
[xml]$embeddedManifest = Get-Content -LiteralPath $manifestProof -Raw
$activeCodePage = $embeddedManifest.SelectSingleNode("//*[local-name()='activeCodePage']")
if ($null -eq $activeCodePage -or $activeCodePage.InnerText.Trim() -ne "UTF-8") {
    throw "Embedded native launcher manifest does not declare UTF-8 activeCodePage"
}
Write-Host "Windows Portable native launcher UTF-8 manifest embedded and verified in isolated staging copy."

Compress-Archive -Path $portableImage -DestinationPath $archive -CompressionLevel Optimal
Write-Host "Portable desktop archive: $archive"
