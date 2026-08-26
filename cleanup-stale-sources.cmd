@echo off
setlocal
set "ROOT=%~dp0"
set "STALE=%ROOT%myhomelib-infrastructure\src\main\java\com\myhomelibcorp\infrastructure\persistence\postgres\PostgresBookRepository.java"
if exist "%STALE%" (
  echo Removing stale source: %STALE%
  del /f /q "%STALE%"
  if errorlevel 1 (
    echo ERROR: Could not remove stale PostgresBookRepository.java
    exit /b 1
  )
) else (
  echo No stale PostgresBookRepository.java found.
)
echo Cleanup complete.
exit /b 0
