@echo off
rem Double-click: one-step HTTPS setup (API key + certificate + connection code).
cd /d %~dp0
.venv\Scripts\python.exe setup.py %*
pause
