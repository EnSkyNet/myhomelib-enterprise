param(
    [ValidateSet("app-image", "exe", "msi")]
    [string]$Type = "app-image"
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& .\mvnw.cmd -pl myhomelib-bootstrap -am package -DskipTests -Pproduction
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$jar = "myhomelib-bootstrap\target\myhomelib-bootstrap-1.0.0.jar"
if (-not (Test-Path $jar)) { throw "Missing $jar" }
$stage = "myhomelib-bootstrap\target\jpackage-input"
$dest = "dist"
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage, $dest | Out-Null
Copy-Item $jar $stage
Remove-Item -Recurse -Force "$dest\MyHomeLib" -ErrorAction SilentlyContinue
$args = @(
    "--type", $Type,
    "--name", "MyHomeLib",
    "--app-version", "1.0.0",
    "--input", $stage,
    "--main-jar", (Split-Path $jar -Leaf),
    "--dest", $dest,
    "--java-options", "-Dfile.encoding=UTF-8"
)
if ($Type -in @("exe", "msi")) {
    $args += @("--win-menu", "--win-shortcut")
}
& jpackage @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Desktop package created under: $dest"
