@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync-upgraded-dependencies.ps1"
exit /b %ERRORLEVEL%
