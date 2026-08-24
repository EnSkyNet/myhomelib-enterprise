@echo off
setlocal EnableExtensions
set "BASE_DIR=%~dp0"
set "PROPS=%BASE_DIR%.mvn\wrapper\maven-wrapper.properties"
if not exist "%PROPS%" (
  echo Missing %PROPS% 1>&2
  exit /b 1
)

for /f "tokens=1,* delims==" %%A in ('findstr /b "distributionUrl=" "%PROPS%"') do set "DIST_URL=%%B"
if not defined DIST_URL (
  echo distributionUrl is not configured 1>&2
  exit /b 1
)

for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "$u='%DIST_URL%'; if($u -match '/apache-maven/([^/]+)/apache-maven-'){ $Matches[1] }"`) do set "MVN_VERSION=%%V"
if not defined MVN_VERSION (
  echo Cannot determine Maven version from distributionUrl 1>&2
  exit /b 1
)

if defined MAVEN_WRAPPER_HOME (
  set "WRAPPER_HOME=%MAVEN_WRAPPER_HOME%"
) else (
  set "WRAPPER_HOME=%USERPROFILE%\.m2\wrapper\dists"
)
set "DIST_DIR=%WRAPPER_HOME%\apache-maven-%MVN_VERSION%"
set "MVN_CMD=%DIST_DIR%\apache-maven-%MVN_VERSION%\bin\mvn.cmd"
set "TMP_ZIP=%DIST_DIR%\apache-maven-%MVN_VERSION%-bin.zip.part"

if not exist "%MVN_CMD%" (
  if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
  if exist "%TMP_ZIP%" del /q "%TMP_ZIP%"
  echo Downloading Maven %MVN_VERSION%... 1>&2
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing -Uri '%DIST_URL%' -OutFile '%TMP_ZIP%'; $dst='%DIST_DIR%'; $mvn=Join-Path $dst 'apache-maven-%MVN_VERSION%'; if(Test-Path $mvn){Remove-Item -Recurse -Force $mvn}; Expand-Archive -LiteralPath '%TMP_ZIP%' -DestinationPath $dst -Force; Remove-Item -Force '%TMP_ZIP%'" || exit /b 1
)

call "%MVN_CMD%" %*
exit /b %ERRORLEVEL%
