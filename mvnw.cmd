@echo off
setlocal

set "MAVEN_URL=https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.zip"
set "MAVEN_DIR=%~dp0.mvn\maven"
set "MAVEN_BIN=%MAVEN_DIR%\apache-maven-3.9.6\bin\mvn.cmd"

if not exist "%MAVEN_BIN%" (
  echo Downloading Maven 3.9.6...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri '%MAVEN_URL%' -OutFile 'maven.zip'"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path 'maven.zip' -DestinationPath '%MAVEN_DIR%' -Force; Remove-Item -Force 'maven.zip'"
)

if not exist "%MAVEN_BIN%" (
  echo ERROR: Failed to download Maven
  exit /b 1
)

call "%MAVEN_BIN%" %*
exit /b %ERRORLEVEL%