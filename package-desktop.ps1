param(
    [ValidateSet("app-image", "exe", "msi")]
    [string]$Type = "app-image",
    [string]$PackageVersion = ""
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

[xml]$rootPom = Get-Content -Raw "pom.xml"
$BuildVersion = if ($env:MHL_VERSION) { $env:MHL_VERSION } else { [string]$rootPom.project.version }
if ([string]::IsNullOrWhiteSpace($BuildVersion)) { throw "Cannot determine application version from pom.xml" }
$Version = if (-not [string]::IsNullOrWhiteSpace($PackageVersion)) { $PackageVersion } elseif ($env:MHL_PACKAGE_VERSION) { $env:MHL_PACKAGE_VERSION } else { $BuildVersion }

if ($env:MHL_SKIP_BUILD -ne "1") {
    & .\tools\invoke-maven.ps1 -pl myhomelib-bootstrap,myhomelib-updater -am package -DskipTests -Pproduction
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

& .\tools\prepare-runtime-tools.ps1

$jar = "myhomelib-bootstrap\target\myhomelib-bootstrap-$BuildVersion.jar"
$updaterJar = "myhomelib-updater\target\myhomelib-updater-$BuildVersion.jar"
if (-not (Test-Path $jar -PathType Leaf)) { throw "Missing $jar" }
if (-not (Test-Path $updaterJar -PathType Leaf)) { throw "Missing $updaterJar" }
$stage = "myhomelib-bootstrap\target\jpackage-input"
$content = "myhomelib-bootstrap\target\jpackage-content"
$appContent = Join-Path $content "app"
$dest = "dist"
Remove-Item -Recurse -Force $stage, $content -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage, $appContent, $dest | Out-Null
Copy-Item $jar $stage

$toolsStage = Join-Path $appContent "tools"
$converterStage = Join-Path $toolsStage "converters"
New-Item -ItemType Directory -Force (Join-Path $toolsStage "validators"), (Join-Path $toolsStage "updater"), $converterStage | Out-Null
Copy-Item -Recurse -Force "tools\validators\epubcheck" (Join-Path $toolsStage "validators\epubcheck")
Copy-Item -Force $updaterJar (Join-Path $toolsStage "updater\$(Split-Path $updaterJar -Leaf)")
$libmobiExe = "tools\converters\libmobi\mobitool.exe"
$libmobiRuntimeLicense = "tools\converters\libmobi\COPYING"
$libmobiSourceLicense = "tools\converters\libmobi\build-src\COPYING"
$libmobiLicense = if (Test-Path $libmobiRuntimeLicense -PathType Leaf) { $libmobiRuntimeLicense } else { $libmobiSourceLicense }
if ((Test-Path $libmobiExe -PathType Leaf) -and (Test-Path $libmobiLicense -PathType Leaf)) {
    $libmobiStage = Join-Path $converterStage "libmobi"
    New-Item -ItemType Directory -Force $libmobiStage | Out-Null
    Copy-Item -Force $libmobiExe (Join-Path $libmobiStage "mobitool.exe")
    Copy-Item -Force $libmobiLicense (Join-Path $libmobiStage "COPYING")
}

# Optional compatibility pack.  Keep upstream directory names so licenses/resources
# stay next to the executable; runtime discovery understands these layouts.
foreach ($name in @("fbc-windows-amd64", "pandoc-3.11", "Calibre")) {
    $source = Join-Path "tools\converters" $name
    if (Test-Path $source -PathType Container) {
        if ($name -eq "Calibre") {
            $ebookConvert = Join-Path $source "ebook-convert.exe"
            $parallel = Join-Path $source "calibre-parallel.exe"
            if (-not ((Test-Path $ebookConvert -PathType Leaf) -and (Test-Path $parallel -PathType Leaf))) {
                Write-Warning "Skipping incomplete Calibre bundle; ebook-convert.exe and calibre-parallel.exe are both required."
                continue
            }
        }
        Copy-Item -Recurse -Force $source (Join-Path $converterStage $name)
    }
}

if ($Type -eq "app-image") {
    Remove-Item -Recurse -Force "$dest\MyHomeLib" -ErrorAction SilentlyContinue
}

$runtimeModules = "java.base,java.desktop,java.instrument,java.management,java.net.http,jdk.management,java.prefs,java.scripting,java.sql.rowset,jdk.compiler,jdk.httpserver,jdk.incubator.vector,jdk.jfr,jdk.unsupported,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.zipfs"

$args = @(
    "--type", $Type,
    "--name", "MyHomeLib",
    "--app-version", $Version,
    "--vendor", "MyHomeLib Corp",
    "--description", "MyHomeLib Enterprise library manager",
    "--input", $stage,
    "--main-jar", (Split-Path $jar -Leaf),
    "--app-content", $appContent,
    "--dest", $dest,
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Dspring.profiles.active=prod",
    "--java-options", "-Xms32m",
    "--java-options", "-Xmx1024m",
    "--java-options", "-XX:MinHeapFreeRatio=10",
    "--java-options", "-XX:MaxHeapFreeRatio=20",
    "--add-modules", $runtimeModules,
    "--jlink-options", "--strip-debug --no-man-pages --no-header-files"
)

if ($Type -in @("exe", "msi")) {
    # Stable upgrade identity is mandatory: every MyHomeLib installer must upgrade the same product
    # instead of creating side-by-side Windows Installer registrations.
    $args += @(
        "--win-menu",
        "--win-shortcut",
        "--win-per-user-install",
        "--win-upgrade-uuid", "165df5fc-27a9-516a-91ec-f596f0d5fabb"
    )
}

& jpackage @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Desktop package created under: $dest (version $Version, type $Type)"
