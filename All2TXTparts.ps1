# ==========================================
# Налаштування
# ==========================================

$projectRoot = (Get-Location).Path
$outputDir = Join-Path $projectRoot "audit"

$maxLinesPerPart = 3000

$excludedDirs = @(
    ".git",
    ".idea",
    ".vscode",
    ".gradle",
    "target",
    "build",
    "out",
    "logs",
    "backup",
    "cache",
    "database"
)

$allowedExtensions = @(
    ".java",
    ".xml",
    ".fxml",
    ".css",
    ".properties",
    ".txt",
    ".md",
    ".yml",
    ".yaml",
    ".json",
    ".sql",
    ".csv",
    ".kt",
    ".groovy",
    ".bat",
    ".sh"
)

# ==========================================
# Створення каталогу
# ==========================================

if (Test-Path $outputDir) {
    Remove-Item $outputDir -Recurse -Force
}

New-Item -ItemType Directory -Path $outputDir | Out-Null

# ==========================================
# Tree
# ==========================================

tree /f > (Join-Path $outputDir "project_tree.txt")

# ==========================================
# Отримання файлів
# ==========================================

$allFiles =
Get-ChildItem $projectRoot -Recurse -File |
Where-Object {

    $relativePath = $_.FullName.Substring($projectRoot.Length)

    foreach ($dir in $excludedDirs) {

        if ($relativePath -match "\\$dir\\") {
            return $false
        }
    }

    return $allowedExtensions -contains $_.Extension
} |
Sort-Object FullName

# ==========================================
# Статистика
# ==========================================

$javaCount = 0
$fxmlCount = 0
$xmlCount = 0
$totalLines = 0

# ==========================================
# Writer
# ==========================================

$currentLine = 1
$partStart = 1
$partLines = 0
$writer = $null

function Open-Part {

    $script:partStart = $script:currentLine

    $tempFile =
        Join-Path $outputDir ("temp_{0:D6}.txt" -f $script:partStart)

    $script:writer =
        [System.IO.StreamWriter]::new(
            $tempFile,
            $false,
            [System.Text.Encoding]::UTF8
        )

    $script:partLines = 0
}

function Close-Part {

    if ($script:writer -ne $null) {

        $script:writer.Close()

        $endLine = $script:currentLine - 1

        Move-Item `
            (Join-Path $outputDir ("temp_{0:D6}.txt" -f $script:partStart)) `
            (Join-Path $outputDir ("merged_output_{0:D6}_{1:D6}.txt" -f $script:partStart, $endLine))
    }
}

function Write-Line {

    param([string]$text)

    if ($script:writer -eq $null) {
        Open-Part
    }

    if ($script:partLines -ge $maxLinesPerPart) {

        Close-Part
        Open-Part
    }

    $script:writer.WriteLine($text)

    $script:partLines++
    $script:currentLine++
}

# ==========================================
# Обхід файлів
# ==========================================

foreach ($file in $allFiles) {

    $relativeFile =
        $file.FullName.Substring($projectRoot.Length + 1)

    switch ($file.Extension) {

        ".java" { $javaCount++ }
        ".fxml" { $fxmlCount++ }
        ".xml" { $xmlCount++ }
    }

    Write-Line ""
    Write-Line ""
    Write-Line ("=" * 100)
    Write-Line ("FILE: " + $relativeFile)
    Write-Line ("=" * 100)

    try {

        $lines = Get-Content $file.FullName -ErrorAction Stop

        foreach ($line in $lines) {

            Write-Line $line
            $totalLines++
        }
    }
    catch {

        Write-Line "[ERROR READING FILE]"
    }
}

Close-Part

# ==========================================
# Summary
# ==========================================

$summaryFile =
    Join-Path $outputDir "project_summary.txt"

@"
Project root: $projectRoot

Files: $($allFiles.Count)

Java files: $javaCount
FXML files: $fxmlCount
XML files: $xmlCount

Total source lines: $totalLines
Generated: $(Get-Date)
"@ | Set-Content $summaryFile -Encoding UTF8

Write-Host ""
Write-Host "=================================="
Write-Host "Audit package generated"
Write-Host "Files:" $allFiles.Count
Write-Host "Java:" $javaCount
Write-Host "FXML:" $fxmlCount
Write-Host "XML:" $xmlCount
Write-Host "Lines:" $totalLines
Write-Host "Output:" $outputDir
Write-Host "=================================="