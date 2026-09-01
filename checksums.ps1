param(
    [string]$Directory = "."
)

$checksumFile = Join-Path $Directory "SHA256SUMS"
$files = Get-ChildItem $Directory -Recurse -File | 
    Where-Object { $_.Name -ne 'SHA256SUMS' } |
    Sort-Object FullName

$results = @()

foreach ($file in $files) {
    # Правильний спосіб отримати відносний шлях
    $relativePath = $file.FullName
    if ($relativePath.StartsWith($Directory)) {
        $relativePath = $relativePath.Substring($Directory.Length).TrimStart('\')
    }
    
    # АБО використовуйте цей спосіб:
    # $relativePath = Resolve-Path -Relative $file.FullName -RelativeBase $Directory
    # $relativePath = $relativePath -replace '^\.\\', ''
    
    $hash = (Get-FileHash $file.FullName -Algorithm SHA256).Hash
    $results += "$hash  $relativePath"
}

$results | Out-File $checksumFile -Encoding UTF8
Write-Host "Created $checksumFile with $($results.Count) entries"