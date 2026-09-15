$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Root = Split-Path -Parent $PSScriptRoot
$Repo = Join-Path $Root ".mvn\repository"
$Mvn = Join-Path $Root "mvnw.cmd"
$Log = Join-Path $Root "PREPARE-OFFLINE-REPO.log"
$Zip = Join-Path $Root "maven-offline-repo-upgraded.zip"
$Sha = "$Zip.sha256.txt"

Set-Location $Root
"MyHomeLib upgraded dependency preparation - $(Get-Date -Format o)" | Set-Content -Encoding UTF8 $Log

function Write-Step([string]$Text) {
    Write-Host "`n=== $Text ===" -ForegroundColor Cyan
    "`n=== $Text ===" | Add-Content -Encoding UTF8 $Log
}

function Invoke-MavenLogged {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    Write-Step $Label

    # Windows PowerShell 5.1 can turn perfectly valid native stderr output into
    # NativeCommandError when $ErrorActionPreference is Stop. Maven/JVM tools
    # are allowed to write diagnostics to stderr, so judge native success by
    # the process exit code instead of PowerShell's stderr wrapper.
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Mvn @Arguments 2>&1 | ForEach-Object {
            $line = $_.ToString()
            Write-Host $line
            $line | Add-Content -Encoding UTF8 $Log
        }
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $savedPreference
    }

    if ($exitCode -ne 0) {
        throw "$Label failed with exit code $exitCode"
    }
}

Write-Step "Checking Java"
$javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
if (-not $javaCommand) {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
}
if (-not $javaCommand) {
    throw "Java was not found in PATH. Install JDK 21, open a new terminal, and rerun PREPARE-OFFLINE-REPO.cmd."
}

# java -version intentionally writes its version banner to stderr. Capture both
# streams as files so Windows PowerShell never mistakes the banner for a script error.
$javaStdout = [System.IO.Path]::GetTempFileName()
$javaStderr = [System.IO.Path]::GetTempFileName()
try {
    $javaProcess = Start-Process -FilePath $javaCommand.Source `
        -ArgumentList @("-version") `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $javaStdout `
        -RedirectStandardError $javaStderr

    $javaVersion = ""
    if (Test-Path $javaStdout) { $javaVersion += (Get-Content -Raw -ErrorAction SilentlyContinue $javaStdout) }
    if (Test-Path $javaStderr) { $javaVersion += (Get-Content -Raw -ErrorAction SilentlyContinue $javaStderr) }

    $javaVersion = $javaVersion.Trim()
    Write-Host $javaVersion
    $javaVersion | Add-Content -Encoding UTF8 $Log

    if ($javaProcess.ExitCode -ne 0) {
        throw "Java failed to start correctly (exit code $($javaProcess.ExitCode))."
    }
    if ($javaVersion -notmatch 'version\s+"21(?:[\._][0-9]+)*') {
        throw "JDK 21 is required. Detected output: $javaVersion"
    }
}
finally {
    Remove-Item -Force -ErrorAction SilentlyContinue $javaStdout, $javaStderr
}

if (-not (Test-Path $Mvn)) {
    throw "Bundled Maven launcher is missing: $Mvn"
}
if (-not (Test-Path $Repo)) {
    New-Item -ItemType Directory -Force -Path $Repo | Out-Null
}

Write-Host "Project: $Root"
Write-Host "Java: $($javaCommand.Source)"
Write-Host "Local Maven repository: $Repo"
Write-Host "Target versions: Spring Boot 4.1.1 / JavaFX 21.0.12 / SQLite JDBC 3.53.4.0 / Flyway 12.4.0"

# A real online clean verify is intentionally used instead of relying only on
# dependency:go-offline: it resolves build plugins and transitive dependencies
# that are actually required by the complete reactor.
Invoke-MavenLogged -Label "Online clean verify and dependency synchronization" -Arguments @(
    "-B", "-ntp", "-U", "clean", "verify"
)

# Resolve optional plugin/report dependencies and sources used by common
# developer/release workflows. Failure here is fatal so the returned cache is
# suitable for an independent offline verification.
Invoke-MavenLogged -Label "Maven dependency:go-offline" -Arguments @(
    "-B", "-ntp", "-U", "dependency:go-offline"
)

Invoke-MavenLogged -Label "Offline verification using the populated repository" -Arguments @(
    "-o", "-B", "-ntp", "clean", "verify"
)

Write-Step "Cleaning transient Maven cache markers"
Get-ChildItem -Path $Repo -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like '*.lastUpdated' -or $_.Name -like '*.tmp' -or $_.Name -like '*.part' } |
    Remove-Item -Force -ErrorAction SilentlyContinue

Write-Step "Creating maven-offline-repo-upgraded.zip"
if (Test-Path $Zip) { Remove-Item -Force $Zip }
if (Test-Path $Sha) { Remove-Item -Force $Sha }
Compress-Archive -Path $Repo -DestinationPath $Zip -CompressionLevel Optimal

$hash = (Get-FileHash -Algorithm SHA256 -Path $Zip).Hash.ToLowerInvariant()
"$hash  $(Split-Path -Leaf $Zip)" | Set-Content -Encoding ASCII $Sha

$sizeMb = [math]::Round((Get-Item $Zip).Length / 1MB, 1)
Write-Step "Completed"
Write-Host "Offline repository ZIP: $Zip ($sizeMb MB)" -ForegroundColor Green
Write-Host "SHA-256: $hash" -ForegroundColor Green
Write-Host "Upload maven-offline-repo-upgraded.zip back to ChatGPT." -ForegroundColor Yellow
"SUCCESS`nZIP=$Zip`nSIZE_MB=$sizeMb`nSHA256=$hash" | Add-Content -Encoding UTF8 $Log
