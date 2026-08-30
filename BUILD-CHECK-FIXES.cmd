@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ============================================================
echo MyHomeLib Enterprise v7.1 release gate
echo ============================================================

set "PYTHON_EXE="
where py >nul 2>nul
if not errorlevel 1 set "PYTHON_EXE=py -3"
if not defined PYTHON_EXE (
  where python >nul 2>nul
  if not errorlevel 1 set "PYTHON_EXE=python"
)

if not defined PYTHON_EXE (
  echo ERROR: Python 3 is required for the offline v7.1 pre-check.
  exit /b 2
)

echo [1/2] Running v7.1 offline regression, migration, security and architecture checks...
%PYTHON_EXE% tools\build-check-v7.py
if errorlevel 1 (
  echo ERROR: v7.1 offline checks failed. Maven was not started.
  exit /b 3
)

echo [2/2] Running Maven clean verify...
call mvnw.cmd clean verify -Pproduction
if errorlevel 1 (
  echo ERROR: Maven clean verify failed.
  exit /b %ERRORLEVEL%
)

echo PASS: MyHomeLib Enterprise v7.1 release checks completed.
exit /b 0
