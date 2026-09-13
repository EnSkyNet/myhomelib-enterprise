@echo off
setlocal EnableExtensions
set "ROOT=%~dp0.."

if exist "%ROOT%\mvnw.cmd" (
  call "%ROOT%\mvnw.cmd" %*
  exit /b %ERRORLEVEL%
)

where mvn.cmd >nul 2>nul
if not errorlevel 1 (
  call mvn.cmd %*
  exit /b %ERRORLEVEL%
)
where mvn >nul 2>nul
if not errorlevel 1 (
  call mvn %*
  exit /b %ERRORLEVEL%
)

echo ERROR: Maven is required. The formal source archive intentionally does not bundle Maven or Maven Wrapper. 1>&2
echo Install Maven 3.9.6+ ^(or use a repository checkout that provides the wrapper^) and retry. 1>&2
exit /b 127
