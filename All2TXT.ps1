# Отримуємо всі файли в поточній папці та підпапках
$allFiles = Get-ChildItem -Path . -Recurse -File

# Задаємо ім'я вихідного файлу – він з'явиться в поточній папці
$outFile = "merged_output.txt"

# Відкриваємо потік для запису (UTF-8)
$stream = [System.IO.StreamWriter]::new($outFile, $false, [System.Text.Encoding]::UTF8)

foreach ($file in $allFiles) {
    $stream.WriteLine("`n`n" + ("=" * 80))
    $stream.WriteLine("ФАЙЛ: $($file.FullName)")
    $stream.WriteLine("=" * 80)
    $lines = Get-Content $file.FullName -ErrorAction SilentlyContinue
    foreach ($line in $lines) { $stream.WriteLine($line) }
}

$stream.Close()
Write-Host "Збережено у $($PWD)\$outFile"