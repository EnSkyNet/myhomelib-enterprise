$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Pin the upstream fb2cng Windows x64 asset by its official GitHub release SHA-256.
# Never use a mutable "latest" URL or trust an unverified third-party executable.
$version = "v1.9.4"
$assetUri = "https://github.com/rupor-github/fb2cng/releases/download/$version/fbc-windows-amd64.zip"
$expectedSha256 = "e2737bbacf0832dd71290c7f451dea66be7c1d75b52e98e8ab7736c74055575f"
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root "tools\converters\fbc-windows-amd64"
$temp = Join-Path ([IO.Path]::GetTempPath()) ("mhl-fbc-provision-" + [Guid]::NewGuid().ToString("N"))
$archive = Join-Path $temp "fbc-windows-amd64.zip"
$extract = Join-Path $temp "extracted"

try {
    New-Item -ItemType Directory -Force $temp, $extract | Out-Null
    Invoke-WebRequest -Uri $assetUri -OutFile $archive -MaximumRedirection 5
    $actualSha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $expectedSha256) {
        throw "Pinned fb2cng asset SHA-256 mismatch: expected $expectedSha256, got $actualSha256"
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $extract
    $executables = @(Get-ChildItem -LiteralPath $extract -Recurse -File -Filter "fbc.exe")
    if ($executables.Count -ne 1) {
        throw "Expected exactly one upstream fbc.exe, found $($executables.Count)"
    }
    $sourceDir = $executables[0].DirectoryName
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    New-Item -ItemType Directory -Force $target | Out-Null
    Get-ChildItem -LiteralPath $sourceDir -Force | Copy-Item -Destination $target -Recurse -Force
    $binary = Join-Path $target "fbc.exe"
    if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) {
        throw "Verified upstream asset did not stage fbc.exe"
    }

    # Preserve license/source attribution alongside the separately executed GPLv3 converter.
    $license = Join-Path $target "LICENSE"
    if (-not (Test-Path -LiteralPath $license -PathType Leaf)) {
        # Bundled official GPLv3 LICENSE from the pinned upstream v1.9.4 tag.
        # Remote raw.githubusercontent.com repeatedly rate-limits Actions with HTTP 429.
        # The executable asset remains an official release download, SHA-256 verified above.
        $upstreamLicense = Join-Path $PSScriptRoot "converters\fbc-license-v1.9.4.txt"
        if (-not (Test-Path -LiteralPath $upstreamLicense -PathType Leaf)) {
            throw "Pinned fb2cng GPLv3 license file is missing from source"
        }
        Copy-Item -LiteralPath $upstreamLicense -Destination $license -Force
    }
    if (-not (Test-Path -LiteralPath $license -PathType Leaf) -or (Get-Item -LiteralPath $license).Length -lt 1000) {
        throw "Upstream fb2cng GPLv3 license is missing or incomplete"
    }
    @(
        "fb2cng / fbc $version (Windows x64)",
        "Source: https://github.com/rupor-github/fb2cng/tree/$version",
        "Binary asset: $assetUri",
        "Archive SHA-256: $expectedSha256",
        "License: GPL-3.0; see LICENSE in this directory"
    ) | Set-Content -LiteralPath (Join-Path $target "UPSTREAM-NOTICE.txt") -Encoding utf8

    $probe = (& $binary --version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($probe)) {
        throw "Verified upstream fbc.exe failed the Windows version probe"
    }
    Write-Host "Pinned official fb2cng Windows x64 provisioning: PASS ($version)"
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}
