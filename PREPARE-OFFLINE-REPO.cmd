@echo off
setlocal
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\prepare-offline-repo.ps1"
set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (
  echo SUCCESS: maven-offline-repo-upgraded.zip has been created in the project root.
  echo Upload that ZIP back to ChatGPT.
) else (
  echo FAILED with exit code %RC%.
  echo See PREPARE-OFFLINE-REPO.log in the project root.
)
echo.
pause
exit /b %RC%
