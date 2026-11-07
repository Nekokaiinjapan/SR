@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================
echo   Building SR.apk ...
echo ============================================
echo.

pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_apk.ps1"
set RC=%ERRORLEVEL%

echo.
if not "%RC%"=="0" (
  echo [FAILED] exit code %RC% - see the message above.
) else (
  echo [OK] SR.apk is ready in this folder.
)

echo.
echo Press any key to close this window...
pause >nul
