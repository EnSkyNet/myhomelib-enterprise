@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify-upgraded-offline.ps1" %*
exit /b %ERRORLEVEL%
