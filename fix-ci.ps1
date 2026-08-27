# fix-ci.ps1
# Скрипт для виправлення CI проблем у проєкті MyHomeLib (Windows)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  MyHomeLib CI Fix Script (Windows)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# ============================================================
# 1. Перевірка, що ми в корені проєкту
# ============================================================
Write-Host "[1/6] Checking project root..." -ForegroundColor Yellow

# Перевіряємо наявність pom.xml
if (-not (Test-Path "pom.xml")) {
    Write-Host "  ❌ pom.xml not found in current directory!" -ForegroundColor Red
    Write-Host "  Current directory: $(Get-Location)" -ForegroundColor Yellow
    Write-Host "  Please run this script from the project root." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  Expected structure:" -ForegroundColor Cyan
    Write-Host "  D:\JavaProjects\myhomelib-enterprise\" -ForegroundColor White
    Write-Host "    ├── .github\" -ForegroundColor White
    Write-Host "    ├── .mvn\" -ForegroundColor White
    Write-Host "    ├── myhomelib-application\" -ForegroundColor White
    Write-Host "    ├── myhomelib-bootstrap\" -ForegroundColor White
    Write-Host "    ├── ...\" -ForegroundColor White
    Write-Host "    ├── pom.xml" -ForegroundColor White
    Write-Host "    ├── mvnw.cmd" -ForegroundColor White
    Write-Host "    └── ..." -ForegroundColor White
    exit 1
}

Write-Host "  ✅ Project root verified: $(Get-Location)" -ForegroundColor Green

# ============================================================
# 2. Створення структури .mvn/wrapper
# ============================================================
Write-Host "[2/6] Creating .mvn/wrapper..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path ".mvn\wrapper" | Out-Null
Write-Host "  ✅ .mvn/wrapper created" -ForegroundColor Green

# ============================================================
# 3. Створення maven-wrapper.properties
# ============================================================
Write-Host "[3/6] Creating maven-wrapper.properties..." -ForegroundColor Yellow
@"
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip
wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar
"@ | Out-File -FilePath ".mvn\wrapper\maven-wrapper.properties" -Encoding UTF8
Write-Host "  ✅ maven-wrapper.properties created" -ForegroundColor Green

# ============================================================
# 4. Завантаження maven-wrapper.jar
# ============================================================
Write-Host "[4/6] Downloading maven-wrapper.jar..." -ForegroundColor Yellow
$jarUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"
$jarPath = ".mvn\wrapper\maven-wrapper.jar"

try {
    Invoke-WebRequest -Uri $jarUrl -OutFile $jarPath -UseBasicParsing
    if (Test-Path $jarPath) {
        Write-Host "  ✅ maven-wrapper.jar downloaded" -ForegroundColor Green
    } else {
        Write-Host "  ❌ Failed to download maven-wrapper.jar" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "  ❌ Error downloading maven-wrapper.jar: $_" -ForegroundColor Red
    exit 1
}

# ============================================================
# 5. Створення mvnw.cmd (Windows)
# ============================================================
Write-Host "[5/6] Creating mvnw.cmd..." -ForegroundColor Yellow
@"
@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "BASE_DIR=%~dp0"
set "PROPS=%BASE_DIR%.mvn\wrapper\maven-wrapper.properties"

if not exist "%PROPS%" (
  echo ERROR: Missing %PROPS% 1>&2
  exit /b 1
)

REM Читаємо distributionUrl
for /f "usebackq tokens=1,* delims==" %%A in ("%PROPS%") do (
  if "%%A"=="distributionUrl" set "DIST_URL=%%B"
)

if not defined DIST_URL (
  echo ERROR: distributionUrl is not configured in %PROPS% 1>&2
  exit /b 1
)

REM Визначаємо версію Maven з URL
for /f "tokens=5 delims=/" %%V in ("%DIST_URL%") do set "MVN_VERSION=%%V"
if not defined MVN_VERSION (
  echo ERROR: Cannot determine Maven version from distributionUrl: %DIST_URL% 1>&2
  exit /b 1
)

REM Визначаємо директорію для завантаження
if defined MAVEN_WRAPPER_HOME (
  set "WRAPPER_HOME=%MAVEN_WRAPPER_HOME%"
) else (
  set "WRAPPER_HOME=%USERPROFILE%\.m2\wrapper\dists"
)

set "DIST_DIR=%WRAPPER_HOME%\apache-maven-%MVN_VERSION%"
set "MVN_CMD=%DIST_DIR%\apache-maven-%MVN_VERSION%\bin\mvn.cmd"
set "TMP_ZIP=%DIST_DIR%\apache-maven-%MVN_VERSION%-bin.zip.part"

REM Перевіряємо, чи Maven вже завантажено
if not exist "%MVN_CMD%" (
  if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
  
  if exist "%TMP_ZIP%" del /q "%TMP_ZIP%"
  
  echo Downloading Maven %MVN_VERSION%... 1>&2
  echo Source: %DIST_URL% 1>&2
  
  REM Завантажуємо Maven
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; ^
     $url='%DIST_URL%'; ^
     $out='%TMP_ZIP%'; ^
     Write-Host 'Downloading...' -ForegroundColor Yellow; ^
     Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $out; ^
     if(-not (Test-Path $out)){ throw 'Download failed' }; ^
     $dst='%DIST_DIR%'; ^
     $mvn=Join-Path $dst 'apache-maven-%MVN_VERSION%'; ^
     if(Test-Path $mvn){ Remove-Item -Recurse -Force $mvn }; ^
     Write-Host 'Extracting...' -ForegroundColor Yellow; ^
     Expand-Archive -LiteralPath $out -DestinationPath $dst -Force; ^
     Remove-Item -Force $out; ^
     Write-Host 'Maven %MVN_VERSION% installed successfully' -ForegroundColor Green"
  
  if errorlevel 1 (
    echo ERROR: Failed to download or extract Maven %MVN_VERSION% 1>&2
    if exist "%TMP_ZIP%" del /q "%TMP_ZIP%"
    exit /b 1
  )
)

REM Запускаємо Maven
if not exist "%MVN_CMD%" (
  echo ERROR: Maven command not found: %MVN_CMD% 1>&2
  exit /b 1
)

call "%MVN_CMD%" %*
exit /b %ERRORLEVEL%
"@ | Out-File -FilePath "mvnw.cmd" -Encoding ASCII
Write-Host "  ✅ mvnw.cmd created" -ForegroundColor Green

# ============================================================
# 6. Перевірка наявності модулів
# ============================================================
Write-Host "[6/6] Checking modules..." -ForegroundColor Yellow

$modules = @(
    "myhomelib-shared",
    "myhomelib-domain", 
    "myhomelib-application",
    "myhomelib-infrastructure",
    "myhomelib-reader",
    "myhomelib-opds",
    "myhomelib-mcp",
    "myhomelib-ui",
    "myhomelib-bootstrap",
    "myhomelib-architecture-tests",
    "myhomelib-e2e-tests",
    "myhomelib-benchmark"
)

$existingModules = @()
$missingModules = @()

foreach ($module in $modules) {
    $pomPath = "$module\pom.xml"
    if (Test-Path $pomPath) {
        Write-Host "  ✅ $module\pom.xml exists" -ForegroundColor Green
        $existingModules += $module
    } else {
        Write-Host "  ⚠️ $module\pom.xml missing" -ForegroundColor Yellow
        $missingModules += $module
    }
}

if ($missingModules.Count -gt 0) {
    Write-Host ""
    Write-Host "  ⚠️ Missing modules: $($missingModules -join ', ')" -ForegroundColor Yellow
    Write-Host "  This may be normal if modules are located elsewhere." -ForegroundColor Yellow
}

# ============================================================
# Фінальна перевірка
# ============================================================
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan

$files = @(
    ".mvn\wrapper\maven-wrapper.properties",
    ".mvn\wrapper\maven-wrapper.jar",
    "mvnw.cmd"
)

$allOk = $true
foreach ($file in $files) {
    if (Test-Path $file) {
        Write-Host "  ✅ $file" -ForegroundColor Green
    } else {
        Write-Host "  ❌ $file" -ForegroundColor Red
        $allOk = $false
    }
}

if (Test-Path "pom.xml") {
    Write-Host "  ✅ pom.xml" -ForegroundColor Green
} else {
    Write-Host "  ❌ pom.xml" -ForegroundColor Red
    $allOk = $false
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

if ($allOk) {
    Write-Host "✅ ALL FILES CREATED SUCCESSFULLY!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Cyan
    Write-Host "  1. git add .mvn/ mvnw.cmd" -ForegroundColor White
    Write-Host "  2. git commit -m 'fix: add Maven wrapper for CI'" -ForegroundColor White
    Write-Host "  3. git push" -ForegroundColor White
    Write-Host ""
    Write-Host "To test locally:" -ForegroundColor Cyan
    Write-Host "  .\mvnw.cmd clean package -DskipTests" -ForegroundColor White
} else {
    Write-Host "❌ SOME FILES ARE MISSING!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Make sure you are in the project root:" -ForegroundColor Yellow
    Write-Host "  D:\JavaProjects\myhomelib-enterprise\" -ForegroundColor White
    Write-Host ""
    Write-Host "And that your project has this structure:" -ForegroundColor Yellow
    Write-Host "  pom.xml (root)" -ForegroundColor White
    Write-Host "  myhomelib-*/pom.xml (modules)" -ForegroundColor White
}