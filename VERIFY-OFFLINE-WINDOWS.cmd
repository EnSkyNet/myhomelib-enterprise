@echo off
setlocal
cd /d "%~dp0"
echo === MyHomeLib offline clean verify ===
echo Project: %CD%
echo.
where java >nul 2>&1
if errorlevel 1 (
  echo ERROR: Java was not found on PATH. JDK 21 is required. 1>&2
  exit /b 127
)
call "%~dp0mvnw.cmd" -o -B -ntp clean verify
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" (
  echo.
  echo OFFLINE VERIFY FAILED with exit code %RC%. 1>&2
  exit /b %RC%
)
echo.
echo OFFLINE VERIFY PASSED.
exit /b 0
