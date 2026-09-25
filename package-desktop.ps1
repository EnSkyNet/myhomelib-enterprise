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
    & .\tools\invoke-maven.ps1 -pl myhomelib-bootstrap -am package -DskipTests -Pproduction
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

& .\tools\prepare-runtime-tools.ps1

$jar = "myhomelib-bootstrap\target\myhomelib-bootstrap-$BuildVersion.jar"
if (-not (Test-Path $jar -PathType Leaf)) { throw "Missing $jar" }
$stage = "myhomelib-bootstrap\target\jpackage-input"
$dest = "dist"
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage, $dest | Out-Null
Copy-Item $jar $stage

$toolsStage = Join-Path $stage "tools"
$converterStage = Join-Path $toolsStage "converters"
New-Item -ItemType Directory -Force (Join-Path $toolsStage "validators"), (Join-Path $converterStage "libmobi") | Out-Null
Copy-Item -Recurse -Force "tools\validators\epubcheck" (Join-Path $toolsStage "validators\epubcheck")
Copy-Item -Force "tools\converters\libmobi\mobitool.exe" (Join-Path $converterStage "libmobi\mobitool.exe")
Copy-Item -Force "tools\converters\libmobi\build-src\COPYING" (Join-Path $converterStage "libmobi\COPYING")

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

$args = @(
    "--type", $Type,
    "--name", "MyHomeLib",
    "--app-version", $Version,
    "--vendor", "MyHomeLib Corp",
    "--description", "MyHomeLib Enterprise library manager",
    "--input", $stage,
    "--main-jar", (Split-Path $jar -Leaf),
    "--dest", $dest,
    "--java-options", "-Dfile.encoding=UTF-8",
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
