@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0compilar-apk.ps1"
if errorlevel 1 (
    echo.
    echo No se pudo generar el APK. Revisa el error de arriba.
    pause
    exit /b 1
)
echo.
echo APK generado correctamente. Puedes copiar NASTuner-prueba.apk a tu telefono.
pause
