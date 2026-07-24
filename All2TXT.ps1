# Задаємо ім'я вихідного файлу
$outFile = "merged_output.txt"

# Створюємо чистий UTF-8 без BOM (false скасовує маркер байтів)
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

# Отримуємо всі файли, ігноруючи сам вихідний файл та службові папки IDE
$allFiles = Get-ChildItem -Path . -Recurse -File | Where-Object { 
    $_.Name -ne $outFile -and 
    $_.FullName -notlike "*\.idea\*" -and 
    $_.FullName -notlike "*\target\*"
}

# Відкриваємо потік для запису з кодуванням без BOM
$stream = [System.IO.StreamWriter]::new($outFile, $false, $utf8NoBom)

foreach ($file in $allFiles) {
    # Швидке розділення файлів візуальною межею
    $stream.WriteLine("`n`n" + ("=" * 80))
    $stream.WriteLine("ФАЙЛ: $($file.FullName)")
    $stream.WriteLine("=" * 80)
    
    # Оптимізоване швидке зчитування всього файлу одним махом (-Raw)
    $content = Get-Content -Path $file.FullName -Raw -ErrorAction SilentlyContinue
    if ($content) {
        $stream.WriteLine($content)
    }
}

# Обов'язково закриваємо потік, щоб зберегти дані на диск
$stream.Close()
Write-Host "Збережено у $($PWD)\$outFile (UTF-8 без BOM, оптимізовано)" -ForegroundColor Green