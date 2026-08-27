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