# Задаємо ім'я вихідного файлу
$outFile = "merged_output.txt"

# UTF-8 без BOM
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

# Отримуємо всі файли, ігноруючи вихідний файл та службові папки IDE
$allFiles = Get-ChildItem -Path . -Recurse -File | Where-Object { 
    $_.Name -ne $outFile -and 
    $_.Name -ne "All2TXT.ps1" -and
    $_.FullName -notlike "*\.idea\*" -and 
    $_.FullName -notlike "*\target\*" -and
    $_.FullName -notlike "*\.git\*" -and
    $_.FullName -notlike "*\node_modules\*"
}

# Сортуємо файли
$allFiles = $allFiles | Sort-Object -Property Name

if ($allFiles.Count -eq 0) {
    Write-Host "No files found" -ForegroundColor Yellow
    Read-Host "Press Enter"
    exit
}

# Відкриваємо потік для запису
$stream = [System.IO.StreamWriter]::new($outFile, $false, $utf8NoBom)

try {
    $count = 0
    foreach ($file in $allFiles) {
        $count++
        Write-Host "Processing $count/$($allFiles.Count): $($file.Name)" -ForegroundColor Gray
        
        # Розділювач
        $stream.WriteLine("`n`n" + ("=" * 80))
        $stream.WriteLine("FILE: $($file.FullName)")
        $stream.WriteLine("=" * 80)
        
        # ЧИТАЄМО ФАЙЛ В ПРАВИЛЬНОМУ КОДУВАННІ
        # Java файли зазвичай в UTF-8
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        
        # Якщо не прочиталось як UTF-8, пробуємо Windows-1251
        if (-not $content) {
            $content = Get-Content -Path $file.FullName -Raw -Encoding Default -ErrorAction SilentlyContinue
        }
        
        if ($content) {
            $stream.Write($content)
        }
    }
}
finally {
    $stream.Close()
}

Write-Host "`n========================================" -ForegroundColor Green
Write-Host "Saved: $($PWD)\$outFile" -ForegroundColor Green
Write-Host "Files merged: $($allFiles.Count)" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green

Read-Host "Press Enter to exit"