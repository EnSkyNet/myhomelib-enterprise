param(
    [string]$Directory = "dist"
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$base = (Resolve-Path $Directory).Path
$checksumFile = Join-Path $base "SHA256SUMS"
$files = Get-ChildItem $base -Recurse -File |
    Where-Object { $_.Name -ne 'SHA256SUMS' -and $_.Name -ne '.SHA256SUMS.tmp' } |
    Sort-Object FullName

$results = foreach ($file in $files) {
    $relativePath = [System.IO.Path]::GetRelativePath($base, $file.FullName).Replace('\', '/')
    $hash = (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $relativePath"
}

$results | Set-Content -Path $checksumFile -Encoding utf8
Write-Host "Created $checksumFile with $($results.Count) entries"
