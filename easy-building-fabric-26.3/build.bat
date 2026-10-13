@echo off
setlocal
cd /d "%~dp0"

echo ===================================================
echo   Building Easy Building Mod for Fabric 26.2
echo ===================================================
echo.

call gradlew.bat build

if %ERRORLEVEL% equ 0 (
    echo.
    echo ===================================================
    echo   BUILD SUCCESSFUL!
    echo   Your mod jar file is located in:
    echo   %~dp0build\libs
    echo ===================================================
    echo.
    dir /b "%~dp0build\libs\*.jar" 2>nul
) else (
    echo.
    echo ===================================================
    echo   BUILD FAILED!
    echo   Please check the error output above.
    echo ===================================================
)

echo.
pause
