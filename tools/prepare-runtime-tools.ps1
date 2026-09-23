$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
$Epub = Join-Path $Root "tools\validators\epubcheck"
$Libmobi = Join-Path $Root "tools\converters\libmobi"
$Source = Join-Path $Libmobi "build-src"
$Build = Join-Path $Libmobi "build-windows"
$Out = Join-Path $Libmobi "mobitool.exe"

$Jar = Join-Path $Epub "epubcheck.jar"
if (-not (Test-Path $Jar -PathType Leaf)) { throw "EPUBCheck JAR missing: $Jar" }
if (-not (Test-Path (Join-Path $Epub "lib") -PathType Container)) { throw "EPUBCheck lib directory missing." }

$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java.exe" }
$EpubVersion = (& $Java -jar $Jar --version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $EpubVersion -notmatch 'EPUBCheck v5\.4\.0') {
    throw "EPUBCheck 5.4.0 probe failed: $EpubVersion"
}

$NeedBuild = $true
if (Test-Path $Out -PathType Leaf) {
    $Version = (& $Out -v 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -and $Version -match 'libmobi:\s+0\.12') { $NeedBuild = $false }
}

if ($NeedBuild) {
    if (-not (Get-Command cmake.exe -ErrorAction SilentlyContinue)) { throw "CMake is required to build mobitool.exe." }
    Remove-Item -Recurse -Force $Build -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $Build | Out-Null

    # Prefer an initialized MSVC environment; otherwise locate Visual Studio Build Tools.
    if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
        $Vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
        if (-not (Test-Path $Vswhere -PathType Leaf)) { throw "Visual Studio Build Tools (C++) are required." }
        $VsInstall = & $Vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if ([string]::IsNullOrWhiteSpace($VsInstall)) { throw "Visual Studio C++ toolchain was not found." }
        $VsDevCmd = Join-Path $VsInstall "Common7\Tools\VsDevCmd.bat"
        $Cmd = "`"$VsDevCmd`" -arch=x64 -host_arch=x64 && set"
        cmd.exe /s /c $Cmd | ForEach-Object {
            $i = $_.IndexOf("=")
            if ($i -gt 0) { Set-Item -Path ("Env:" + $_.Substring(0,$i)) -Value $_.Substring($i+1) }
        }
    }

    & cmake.exe -S $Source -B $Build -G "NMake Makefiles" -DCMAKE_BUILD_TYPE=Release
    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed for libmobi." }
    & cmake.exe --build $Build --config Release
    if ($LASTEXITCODE -ne 0) { throw "libmobi build failed." }

    $Candidate = Join-Path $Build "mobitool.exe"
    if (-not (Test-Path $Candidate -PathType Leaf)) { throw "mobitool.exe was not produced." }
    Copy-Item -Force $Candidate $Out
}

$LibmobiVersion = (& $Out -v 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $LibmobiVersion -notmatch 'libmobi:\s+0\.12') {
    throw "libmobi 0.12 probe failed: $LibmobiVersion"
}
Write-Host "Runtime tools ready: EPUBCheck 5.4.0, libmobi 0.12"
