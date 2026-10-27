@echo off
rem Usage: make-cert.bat SERVER_IP   (run "pip install -r requirements.txt" in .venv first)
cd /d %~dp0
if "%~1"=="" (
  echo Usage: make-cert.bat SERVER_IP
  exit /b 2
)
.venv\Scripts\python.exe make-cert.py %*
pause
