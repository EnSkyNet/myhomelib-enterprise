@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ============================================================
echo MyHomeLib 8.0.0 release gate
echo ============================================================

set "PYTHON_EXE="
where py >nul 2>nul
if not errorlevel 1 set "PYTHON_EXE=py -3"
if not defined PYTHON_EXE (
  where python >nul 2>nul
  if not errorlevel 1 set "PYTHON_EXE=python"
)

if not defined PYTHON_EXE (
  echo ERROR: Python 3 is required for the offline 8.0 pre-check.
  exit /b 2
)

echo [1/2] Running 8.0 offline regression, migration, security and architecture checks...
%PYTHON_EXE% tools\build-check-v7.py
if errorlevel 1 (
  echo ERROR: 8.0 offline checks failed. Maven was not started.
  exit /b 3
)

echo [2/2] Running Maven clean verify...
call tools\invoke-maven.cmd clean verify -Pproduction
if errorlevel 1 (
  echo ERROR: Maven clean verify failed.
  exit /b %ERRORLEVEL%
)

echo PASS: MyHomeLib 8.0.0 release checks completed.
exit /b 0
