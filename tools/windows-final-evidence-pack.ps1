param(
    [string]$Root = "target",
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

if ($env:OS -ne "Windows_NT") {
    throw "Final Windows acceptance evidence packaging must run on Windows."
}

$AcceptanceRoot = [System.IO.Path]::GetFullPath($Root)
if (-not (Test-Path $AcceptanceRoot -PathType Container)) {
    throw "Acceptance evidence root not found: $AcceptanceRoot"
}
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $AcceptanceRoot "windows-final-acceptance-evidence.zip"
}
$Output = [System.IO.Path]::GetFullPath($Output)
$ChecksumPath = "$Output.sha256"
$Staging = Join-Path $AcceptanceRoot "windows-final-acceptance-evidence-staging"

Write-Host "[1/4] Validate strict MHL-011/MHL-012 evidence"
& python "tools\windows-acceptance-evidence-check.py" `
    --root $AcceptanceRoot `
    --require-standard-user `
    --require-real-previous `
    --dpi `
    --release-desktop `
    --require-host-binding
if ($LASTEXITCODE -ne 0) {
    throw "Strict Windows acceptance evidence validation failed with exit code $LASTEXITCODE"
}

try {
    Write-Host "[2/4] Stage validated reports, logs and screenshots"
    Remove-Item -Recurse -Force $Staging -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $Staging | Out-Null

    foreach ($dirName in @("windows-host-binding", "windows-installer-acceptance", "windows-portable-acceptance", "windows-release-desktop-acceptance")) {
        $source = Join-Path $AcceptanceRoot $dirName
        if (-not (Test-Path $source -PathType Container)) { throw "Missing evidence directory: $source" }
        Copy-Item -Recurse -Force $source (Join-Path $Staging $dirName)
    }

    foreach ($scale in @(100, 125, 150, 200)) {
        foreach ($extension in @("json", "md")) {
            $source = Join-Path $AcceptanceRoot "windows-ui-acceptance-$scale.$extension"
            if (-not (Test-Path $source -PathType Leaf)) { throw "Missing DPI report: $source" }
            Copy-Item -Force $source $Staging
        }
        $evidenceDir = Join-Path $AcceptanceRoot "dpi-$scale-evidence"
        if (-not (Test-Path $evidenceDir -PathType Container)) { throw "Missing DPI evidence directory: $evidenceDir" }
        Copy-Item -Recurse -Force $evidenceDir (Join-Path $Staging "dpi-$scale-evidence")
    }

    Write-Host "[3/4] Generate SHA-256 manifest and evidence archive"
    $manifestLines = New-Object System.Collections.Generic.List[string]
    $stagingPrefix = $Staging.TrimEnd([char[]]@('\', '/')) + [System.IO.Path]::DirectorySeparatorChar
    foreach ($file in Get-ChildItem -Path $Staging -File -Recurse | Sort-Object FullName) {
        $relative = $file.FullName.Substring($stagingPrefix.Length).Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 $file.FullName).Hash.ToLowerInvariant()
        $manifestLines.Add("$hash  $relative") | Out-Null
    }
    $manifest = Join-Path $Staging "manifest.sha256"
    [System.IO.File]::WriteAllLines($manifest, $manifestLines, [System.Text.UTF8Encoding]::new($false))

    New-Item -ItemType Directory -Force (Split-Path -Parent $Output) | Out-Null
    Remove-Item -Force $Output, $ChecksumPath -ErrorAction SilentlyContinue
    Compress-Archive -Path (Join-Path $Staging "*") -DestinationPath $Output -CompressionLevel Optimal
    $archiveHash = (Get-FileHash -Algorithm SHA256 $Output).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText(
        $ChecksumPath,
        "$archiveHash  $(Split-Path -Leaf $Output)`n",
        [System.Text.UTF8Encoding]::new($false)
    )

    Write-Host "[4/4] Final Windows acceptance evidence bundle: PASS"
    Write-Host "Archive: $Output"
    Write-Host "SHA-256: $archiveHash"
}
finally {
    Remove-Item -Recurse -Force $Staging -ErrorAction SilentlyContinue
}
