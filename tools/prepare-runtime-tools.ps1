$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
$Epub = Join-Path $Root "tools\validators\epubcheck"
$Converters = Join-Path $Root "tools\converters"
$Libmobi = Join-Path $Converters "libmobi"
$Source = Join-Path $Libmobi "build-src"
$Build = Join-Path $Libmobi "build-windows"
$Out = Join-Path $Libmobi "mobitool.exe"
$RuntimeLicense = Join-Path $Libmobi "COPYING"
$SourceLicense = Join-Path $Source "COPYING"
$RequireLibmobi = $env:MHL_REQUIRE_LIBMOBI -eq "1"
$RequireFbc = $env:MHL_REQUIRE_FBC -eq "1"

function Find-ConverterBundle {
    $Candidates = @(
        (Join-Path $Root "tools.zip"),
        (Join-Path (Split-Path $Root -Parent) "tools.zip"),
        (Join-Path $Root "tools.7z"),
        (Join-Path (Split-Path $Root -Parent) "tools.7z")
    )
    foreach ($Candidate in $Candidates) {
        if (Test-Path $Candidate -PathType Leaf) { return $Candidate }
    }
    return $null
}

function Expand-CompatibilityBundleIfNeeded {
    $Fbc = Join-Path $Converters "fbc-windows-amd64\fbc.exe"
    $Pandoc = Join-Path $Converters "pandoc-3.11\pandoc.exe"
    $Calibre = Join-Path $Converters "Calibre\ebook-convert.exe"
    $CalibreWorker = Join-Path $Converters "Calibre\calibre-parallel.exe"
    if ((Test-Path $Fbc -PathType Leaf) -and (Test-Path $Pandoc -PathType Leaf) `
            -and (Test-Path $Calibre -PathType Leaf) -and (Test-Path $CalibreWorker -PathType Leaf)) {
        return
    }

    $Bundle = Find-ConverterBundle
    if (-not $Bundle) { return }

    Write-Host "Extracting converter compatibility bundle: $Bundle"
    if ([System.IO.Path]::GetExtension($Bundle).Equals(".zip", [System.StringComparison]::OrdinalIgnoreCase)) {
        Expand-Archive -Path $Bundle -DestinationPath $Root -Force
    } else {
        $SevenZip = Get-Command 7z.exe -ErrorAction SilentlyContinue
        if (-not $SevenZip) { $SevenZip = Get-Command 7zz.exe -ErrorAction SilentlyContinue }
        if ($SevenZip) {
            & $SevenZip.Source x -y ("-o" + $Root) $Bundle | Out-Host
            if ($LASTEXITCODE -ne 0) { throw "Failed to extract converter compatibility bundle: $Bundle" }
        } else {
            $Tar = Get-Command tar.exe -ErrorAction SilentlyContinue
            if (-not $Tar) {
                throw "Converter bundle found at '$Bundle', but neither 7z.exe/7zz.exe nor tar.exe is available. Extract tools.7z into '$Root' manually."
            }
            & $Tar.Source -xf $Bundle -C $Root
            if ($LASTEXITCODE -ne 0) { throw "Failed to extract converter compatibility bundle: $Bundle" }
        }
    }

    if (-not (Test-Path $Fbc -PathType Leaf)) {
        throw "Converter bundle extraction completed, but fbc.exe was not found at '$Fbc'."
    }
}

Expand-CompatibilityBundleIfNeeded

$Jar = Join-Path $Epub "epubcheck.jar"
if (-not (Test-Path $Jar -PathType Leaf)) { throw "EPUBCheck JAR missing: $Jar" }
if (-not (Test-Path (Join-Path $Epub "lib") -PathType Container)) { throw "EPUBCheck lib directory missing." }

$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java.exe" }
$EpubVersion = (& $Java -jar $Jar --version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $EpubVersion -notmatch 'EPUBCheck v5\.4\.0') {
    throw "EPUBCheck 5.4.0 probe failed: $EpubVersion"
}

$NeedBuild = $true
$LibmobiAvailable = $false
if (Test-Path $Out -PathType Leaf) {
    $Version = (& $Out -v 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -and $Version -match 'libmobi:\s+0\.12') {
        $NeedBuild = $false
        $LibmobiAvailable = $true
    }
}

if ($NeedBuild) {
    $CMakeLists = Join-Path $Source "CMakeLists.txt"
    if (-not (Test-Path $CMakeLists -PathType Leaf)) {
        if ($RequireLibmobi) {
            throw "Bundled libmobi 0.12 source/runtime is missing: $Source. Full release packaging requires libmobi."
        }
        Write-Warning "Bundled libmobi 0.12 source/runtime is absent; desktop packaging will continue without bundled mobitool."
    } else {
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
        $LibmobiVersion = (& $Out -v 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0 -or $LibmobiVersion -notmatch 'libmobi:\s+0\.12') {
            throw "libmobi 0.12 probe failed: $LibmobiVersion"
        }
        $LibmobiAvailable = $true
    }
}

$License = if (Test-Path $RuntimeLicense -PathType Leaf) { $RuntimeLicense } else { $SourceLicense }
if ($LibmobiAvailable -and -not (Test-Path $License -PathType Leaf)) {
    if ($RequireLibmobi) {
        throw "Bundled libmobi license is missing. Expected '$RuntimeLicense' or '$SourceLicense'. Refusing incomplete full-release packaging."
    }
    Write-Warning "Bundled mobitool is present but its COPYING file is missing; it will not be embedded in the desktop package."
    $LibmobiAvailable = $false
}

$Fbc = Join-Path $Converters "fbc-windows-amd64\fbc.exe"
$Pandoc = Join-Path $Converters "pandoc-3.11\pandoc.exe"
$Calibre = Join-Path $Converters "Calibre\ebook-convert.exe"
$CalibreWorker = Join-Path $Converters "Calibre\calibre-parallel.exe"

$Optional = @()
if (Test-Path $Fbc -PathType Leaf) {
    $Optional += "fb2cng/fbc"
} elseif ($RequireFbc) {
    throw "Required portable FB2 conversion engine is missing: $Fbc"
} else {
    Write-Warning "FB2 conversion engine is missing: $Fbc"
}
if (Test-Path $Pandoc -PathType Leaf) { $Optional += "Pandoc 3.11" } else { Write-Warning "Pandoc compatibility engine is missing: $Pandoc" }
if ((Test-Path $Calibre -PathType Leaf) -and (Test-Path $CalibreWorker -PathType Leaf)) {
    $Optional += "Calibre fallback"
} elseif (Test-Path $Calibre -PathType Leaf) {
    Write-Warning "Calibre fallback is incomplete: required worker is missing: $CalibreWorker"
} else {
    Write-Warning "Calibre fallback is missing: $Calibre"
}

$Suffix = if ($Optional.Count -gt 0) { "; detected: " + ($Optional -join ", ") } else { "" }
$LibmobiStatus = if ($LibmobiAvailable) { "libmobi 0.12" } else { "bundled libmobi unavailable" }
Write-Host "Runtime tools ready: EPUBCheck 5.4.0, $LibmobiStatus$Suffix"
