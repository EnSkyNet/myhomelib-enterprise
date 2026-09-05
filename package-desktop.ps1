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
    & .\mvnw.cmd -pl myhomelib-bootstrap -am package -DskipTests -Pproduction
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$jar = "myhomelib-bootstrap\target\myhomelib-bootstrap-$BuildVersion.jar"
if (-not (Test-Path $jar -PathType Leaf)) { throw "Missing $jar" }
$stage = "myhomelib-bootstrap\target\jpackage-input"
$dest = "dist"
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage, $dest | Out-Null
Copy-Item $jar $stage
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
    "--java-options", "-Dfile.encoding=UTF-8"
)

if ($Type -in @("exe", "msi")) {
    # Stable upgrade identity is mandatory: every 7.x installer must upgrade the same product
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
