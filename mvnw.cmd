@echo off
setlocal
set "BASE_DIR=%~dp0"
set "EMBEDDED_MAVEN=%BASE_DIR%.mvn\maven\apache-maven-3.9.6\bin\mvn.cmd"
set "WRAPPER_JAR=%BASE_DIR%.mvn\wrapper\maven-wrapper.jar"

if exist "%EMBEDDED_MAVEN%" (
  call "%EMBEDDED_MAVEN%" %*
  exit /b %ERRORLEVEL%
)

if exist "%WRAPPER_JAR%" (
  java -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
  exit /b %ERRORLEVEL%
)

where mvn >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  call mvn %*
  exit /b %ERRORLEVEL%
)

echo ERROR: Maven is unavailable. Keep .mvn\wrapper\maven-wrapper.jar or install Maven 3.9.6+.
exit /b 127
