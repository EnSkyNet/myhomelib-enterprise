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
