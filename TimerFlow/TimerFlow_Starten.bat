@echo off
title TimerFlow - Android Emulator
color 0A

echo.
echo ===============================================
echo   TimerFlow - Android Emulator in Docker
echo ===============================================
echo.

REM Docker pruefen
docker info >nul 2>&1
if errorlevel 1 (
    echo [FEHLER] Docker Desktop ist nicht gestartet!
    echo Bitte Docker Desktop starten und erneut versuchen.
    echo.
    pause
    exit /b 1
)
echo [OK] Docker Desktop laeuft.
echo.

REM APK bauen
echo ===============================================
echo  [1/3] APK wird gebaut (kann 10-15 Min dauern)
echo ===============================================
echo.
docker-compose --profile build run --rm build-apk
if errorlevel 1 (
    echo [FEHLER] APK-Build fehlgeschlagen!
    pause
    exit /b 1
)
echo [OK] APK gebaut.
echo.

REM Emulator starten
echo ===============================================
echo  [2/3] Android-Emulator wird gestartet
echo ===============================================
echo.
docker-compose up -d android-emulator
echo.
echo Emulator startet - das dauert ca. 2-3 Minuten...
echo.

REM Warten bis Emulator bereit
echo Warte auf Android-Boot...
:wait_loop
timeout /t 10 /nobreak >nul
docker exec timerflow-android-emulator-1 adb shell getprop sys.boot_completed 2>nul | find "1" >nul
if errorlevel 1 goto wait_loop
echo [OK] Android ist gestartet!
echo.

REM APK installieren
echo ===============================================
echo  [3/3] TimerFlow wird installiert
echo ===============================================
echo.
docker exec timerflow-android-emulator-1 adb install -r /apk/TimerFlow.apk
if errorlevel 1 (
    echo [WARNUNG] Installation fehlgeschlagen - App manuell im Browser installieren.
) else (
    echo [OK] TimerFlow installiert!
    REM App starten
    docker exec timerflow-android-emulator-1 adb shell am start -n com.timerflow/.MainActivity
)

echo.
echo ===============================================
echo   FERTIG! Emulator im Browser oeffnen:
echo ===============================================
echo.
echo   http://localhost:6080
echo.
echo Druecke eine Taste um den Browser zu oeffnen...
pause >nul
start "" http://localhost:6080

echo.
echo Zum Beenden: docker-compose down
echo.
pause
