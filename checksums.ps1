param([string]$Directory = "dist")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Test-Path $Directory -PathType Container)) { throw "Directory not found: $Directory" }
$out = Join-Path $Directory "SHA256SUMS"
$base = (Resolve-Path $Directory).Path
$lines = Get-ChildItem $Directory -File -Recurse | Where-Object Name -ne "SHA256SUMS" | Sort-Object FullName | ForEach-Object {
    $hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $relative = $_.FullName.Substring($base.Length).TrimStart('\\','/').Replace('\\','/')
    "$hash  $relative"
}
Set-Content -Path $out -Value $lines -Encoding ascii
Write-Host "Wrote $out"
