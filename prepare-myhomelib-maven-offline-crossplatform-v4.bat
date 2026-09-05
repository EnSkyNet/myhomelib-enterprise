@echo off
setlocal
cd /d "%~dp0"

echo.
echo MyHomeLib Maven offline repository preparation v4
echo Project directory: %CD%
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-myhomelib-maven-offline-crossplatform-v4.ps1" %*
set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo SUCCESS. Check maven-offline-repo.zip, .sha256 and the v4 log file.
) else (
  echo FAILED with exit code %EXITCODE%.
  echo Check: prepare-myhomelib-maven-offline-v4.log
  echo If present, also upload: maven-offline-repo-PARTIAL.zip
)
echo.
echo This window will stay open until you press a key.
pause
exit /b %EXITCODE%
