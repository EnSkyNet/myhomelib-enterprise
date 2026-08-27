# fix-mvnw.ps1
# Повне виправлення mvnw.cmd

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Fixing mvnw.cmd" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 1. Видаляємо старий неправильний mvnw.cmd
Write-Host "[1/4] Removing old mvnw.cmd..." -ForegroundColor Yellow
if (Test-Path "mvnw.cmd") {
    Remove-Item -Force "mvnw.cmd"
    Write-Host "  ✅ Removed old mvnw.cmd" -ForegroundColor Green
}

# 2. Створюємо новий mvnw.cmd (спрощена версія без PowerShell)
Write-Host "[2/4] Creating new mvnw.cmd..." -ForegroundColor Yellow
@"
@echo off
setlocal

set "MAVEN_HOME=%~dp0"
set "MVNW_CMD=%~dp0.mvn\wrapper\maven-wrapper.jar"
set "MVNW_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"

if not exist "%MVNW_CMD%" (
  echo Downloading Maven Wrapper...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri '%MVNW_URL%' -OutFile '%MVNW_CMD%'"
)

if not exist "%MVNW_CMD%" (
  echo ERROR: Failed to download maven-wrapper.jar
  exit /b 1
)

java -jar "%MVNW_CMD%" %*
exit /b %ERRORLEVEL%
"@ | Out-File -FilePath "mvnw.cmd" -Encoding ASCII

Write-Host "  ✅ New mvnw.cmd created" -ForegroundColor Green

# 3. Завантажуємо maven-wrapper.jar (якщо відсутній)
Write-Host "[3/4] Downloading maven-wrapper.jar..." -ForegroundColor Yellow
$jarUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"
$jarPath = ".mvn\wrapper\maven-wrapper.jar"

if (-not (Test-Path $jarPath)) {
    try {
        Invoke-WebRequest -Uri $jarUrl -OutFile $jarPath -UseBasicParsing
        Write-Host "  ✅ maven-wrapper.jar downloaded" -ForegroundColor Green
    } catch {
        Write-Host "  ❌ Failed to download maven-wrapper.jar" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "  ✅ maven-wrapper.jar already exists" -ForegroundColor Green
}

# 4. Перевірка
Write-Host "[4/4] Verification..." -ForegroundColor Yellow
if (Test-Path "mvnw.cmd") {
    Write-Host "  ✅ mvnw.cmd exists" -ForegroundColor Green
}
if (Test-Path $jarPath) {
    Write-Host "  ✅ maven-wrapper.jar exists" -ForegroundColor Green
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "✅ mvnw.cmd fixed!" -ForegroundColor Green
Write-Host ""
Write-Host "Now try:" -ForegroundColor Cyan
Write-Host "  .\mvnw.cmd clean package -DskipTests" -ForegroundColor White
Write-Host "========================================" -ForegroundColor Cyan