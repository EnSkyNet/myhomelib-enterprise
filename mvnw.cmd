@echo off
setlocal
set "ROOT=%~dp0"
set "MAVEN=%ROOT%.mvn\maven\apache-maven-3.9.6\bin\mvn.cmd"
set "REPO=%ROOT%.mvn\repository"
if not exist "%MAVEN%" (
  echo ERROR: bundled Maven 3.9.6 is missing: %MAVEN% 1>&2
  exit /b 127
)
if not exist "%REPO%" mkdir "%REPO%"
call "%MAVEN%" -Dmaven.repo.local="%REPO%" %*
exit /b %ERRORLEVEL%
