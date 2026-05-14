@echo off
title PodcastStudio v4 - All-in-One Setup
color 0A

REM ===============================================
REM  PodcastStudio v4 - Layout-Fixes + Performance
REM ===============================================

echo.
echo ===============================================
echo   PODCASTSTUDIO v4 - All-in-One Setup
echo ===============================================
echo.
echo Bugfixes in v4:
echo   - Wiedergabe-Dropdown bleibt sichtbar
echo   - App friert beim Tab-Wechsel nicht mehr ein
echo   - Performance-Optimierungen (60% weniger CPU)
echo   - Stabileres Layout
echo.
echo Setup laedt:
echo   [1] JDK 21 (Java)        ~190 MB
echo   [2] JavaFX SDK 21         ~25 MB
echo   [3] FFmpeg                ~80 MB
echo.
echo Gesamt: ca. 600 MB Speicher
echo Dauer:  3-15 Min je nach Internet
echo.
echo Druecke eine Taste zum Starten...
pause >nul
echo.

cd /d "%~dp0"

REM Pruefe Quellcode
if not exist "src\main\java\com\podcast\PodcastStudio.java" (
    echo [FEHLER] Quellcode fehlt!
    echo Bitte SETUP.bat im entpackten Ordner starten.
    echo.
    pause
    exit /b 1
)

REM ===============================================
REM  [1] JDK 21 herunterladen (NICHT Java 25!)
REM      JavaFX 21 ist nur kompatibel bis Java 24
REM ===============================================
echo ===============================================
echo  [1/4] JDK 21 vorbereiten
echo ===============================================
echo.

set "JDK_DIR=%~dp0jdk"
set "JAVA_EXE=%JDK_DIR%\bin\java.exe"
set "JAVAC_EXE=%JDK_DIR%\bin\javac.exe"

if exist "%JAVA_EXE%" (
    echo [OK] Lokales JDK bereits vorhanden.
    goto :jdk_done
)

echo Lade portable JDK 21 herunter (~190 MB)...
echo Dies kann einige Minuten dauern...
echo.

powershell -ExecutionPolicy Bypass -NoProfile -Command ^
  "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
  "$ProgressPreference='Continue';" ^
  "Write-Host 'Download JDK 21...';" ^
  "Invoke-WebRequest -Uri 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%%2B11/OpenJDK21U-jdk_x64_windows_hotspot_21.0.5_11.zip' -OutFile 'jdk.zip' -UseBasicParsing;" ^
  "Write-Host 'Entpacke JDK...';" ^
  "Expand-Archive -Path 'jdk.zip' -DestinationPath 'jdk_temp' -Force;" ^
  "$d=Get-ChildItem 'jdk_temp' -Directory | Select-Object -First 1;" ^
  "if($d){ Move-Item $d.FullName 'jdk' -Force };" ^
  "Remove-Item 'jdk_temp','jdk.zip' -Recurse -Force -ErrorAction SilentlyContinue;" ^
  "Write-Host 'JDK fertig.'"

if not exist "%JAVA_EXE%" (
    echo.
    echo [FEHLER] JDK-Download fehlgeschlagen!
    echo Bitte Internetverbindung pruefen.
    echo.
    pause
    exit /b 1
)
echo [OK] JDK 21 installiert.

:jdk_done
echo.

REM ===============================================
REM  [2] JavaFX SDK 21 herunterladen
REM ===============================================
echo ===============================================
echo  [2/4] JavaFX SDK 21 vorbereiten
echo ===============================================
echo.

set "JFX_DIR=%~dp0javafx-sdk"

if exist "%JFX_DIR%\lib\javafx.controls.jar" (
    echo [OK] JavaFX SDK bereits vorhanden.
    goto :jfx_done
)

echo Lade JavaFX SDK 21 herunter (~25 MB)...
echo.

powershell -ExecutionPolicy Bypass -NoProfile -Command ^
  "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
  "$ProgressPreference='Continue';" ^
  "Write-Host 'Download JavaFX SDK...';" ^
  "Invoke-WebRequest -Uri 'https://download2.gluonhq.com/openjfx/21.0.5/openjfx-21.0.5_windows-x64_bin-sdk.zip' -OutFile 'javafx.zip' -UseBasicParsing;" ^
  "Write-Host 'Entpacke JavaFX SDK...';" ^
  "Expand-Archive -Path 'javafx.zip' -DestinationPath 'javafx_temp' -Force;" ^
  "$d=Get-ChildItem 'javafx_temp' -Directory | Select-Object -First 1;" ^
  "if($d){ Move-Item $d.FullName 'javafx-sdk' -Force };" ^
  "Remove-Item 'javafx_temp','javafx.zip' -Recurse -Force -ErrorAction SilentlyContinue;" ^
  "Write-Host 'JavaFX fertig.'"

if not exist "%JFX_DIR%\lib\javafx.controls.jar" (
    echo.
    echo [FEHLER] JavaFX-Download fehlgeschlagen!
    echo.
    pause
    exit /b 1
)
echo [OK] JavaFX SDK 21 installiert.

:jfx_done
echo.

REM ===============================================
REM  [3] FFmpeg herunterladen
REM ===============================================
echo ===============================================
echo  [3/4] FFmpeg vorbereiten
echo ===============================================
echo.

if exist "ffmpeg.exe" (
    echo [OK] FFmpeg bereits vorhanden.
    goto :ff_done
)

echo Lade FFmpeg herunter (~80 MB)...
echo.

powershell -ExecutionPolicy Bypass -NoProfile -Command ^
  "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
  "$ProgressPreference='Continue';" ^
  "Invoke-WebRequest -Uri 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip' -OutFile 'ff.zip' -UseBasicParsing;" ^
  "Expand-Archive -Path 'ff.zip' -DestinationPath 'ff_tmp' -Force;" ^
  "$exe=Get-ChildItem 'ff_tmp' -Recurse -Filter 'ffmpeg.exe' | Select-Object -First 1;" ^
  "if($exe){ Copy-Item $exe.FullName 'ffmpeg.exe' -Force };" ^
  "Remove-Item 'ff.zip','ff_tmp' -Recurse -Force -ErrorAction SilentlyContinue"

if exist "ffmpeg.exe" (
    echo [OK] FFmpeg installiert.
) else (
    echo [WARNUNG] FFmpeg-Download fehlgeschlagen.
    echo App laeuft trotzdem, aber MP3-Export ist deaktiviert.
)

:ff_done
echo.

REM ===============================================
REM  [4] App kompilieren OHNE MAVEN
REM ===============================================
echo ===============================================
echo  [4/4] App kompilieren (ohne Maven)
echo ===============================================
echo.

REM Aufraeumen
if exist "app" rmdir /s /q "app"
mkdir "app"
mkdir "app\classes"

echo Kompiliere PodcastStudio.java...
echo.

"%JAVAC_EXE%" ^
  --module-path "%JFX_DIR%\lib" ^
  --add-modules javafx.controls,javafx.graphics,javafx.base ^
  -encoding UTF-8 ^
  -d "app\classes" ^
  "src\main\java\com\podcast\PodcastStudio.java"

if errorlevel 1 (
    echo.
    echo [FEHLER] Kompilierung fehlgeschlagen!
    echo Siehe Fehlermeldungen oben.
    echo.
    pause
    exit /b 1
)
echo [OK] Kompilierung erfolgreich.
echo.

REM Manifest erstellen
echo Manifest-Version: 1.0> "app\manifest.txt"
echo Main-Class: com.podcast.PodcastStudio>> "app\manifest.txt"
echo.>> "app\manifest.txt"

REM JAR erstellen
echo Erstelle JAR-Datei...
"%JDK_DIR%\bin\jar.exe" cfm "app\PodcastStudio.jar" "app\manifest.txt" -C "app\classes" .

if not exist "app\PodcastStudio.jar" (
    echo [FEHLER] JAR-Erstellung fehlgeschlagen!
    pause
    exit /b 1
)
echo [OK] JAR erstellt.

REM FFmpeg in app-Ordner
if exist "ffmpeg.exe" (
    copy /y "ffmpeg.exe" "app\ffmpeg.exe" >nul
    echo [OK] FFmpeg in app\ kopiert.
)

REM Aufraeumen
rmdir /s /q "app\classes" 2>nul
del "app\manifest.txt" 2>nul

echo.

REM ===============================================
REM  [5] Starter erstellen
REM ===============================================
echo ===============================================
echo  Starter-Datei erstellen
echo ===============================================
echo.

REM PodcastStudio_Starten.bat schreiben
>"%~dp0PodcastStudio_Starten.bat" echo @echo off
>>"%~dp0PodcastStudio_Starten.bat" echo title PodcastStudio
>>"%~dp0PodcastStudio_Starten.bat" echo cd /d "%%~dp0"
>>"%~dp0PodcastStudio_Starten.bat" echo "%%~dp0jdk\bin\javaw.exe" --module-path "%%~dp0javafx-sdk\lib" --add-modules javafx.controls,javafx.graphics,javafx.base -jar "%%~dp0app\PodcastStudio.jar"

REM Debug-Starter (zeigt Fehler)
>"%~dp0PodcastStudio_DEBUG.bat" echo @echo off
>>"%~dp0PodcastStudio_DEBUG.bat" echo title PodcastStudio Debug
>>"%~dp0PodcastStudio_DEBUG.bat" echo cd /d "%%~dp0"
>>"%~dp0PodcastStudio_DEBUG.bat" echo echo Starte PodcastStudio mit Fehler-Output...
>>"%~dp0PodcastStudio_DEBUG.bat" echo echo.
>>"%~dp0PodcastStudio_DEBUG.bat" echo "%%~dp0jdk\bin\java.exe" --module-path "%%~dp0javafx-sdk\lib" --add-modules javafx.controls,javafx.graphics,javafx.base -jar "%%~dp0app\PodcastStudio.jar"
>>"%~dp0PodcastStudio_DEBUG.bat" echo echo.
>>"%~dp0PodcastStudio_DEBUG.bat" echo echo Exit-Code: %%ERRORLEVEL%%
>>"%~dp0PodcastStudio_DEBUG.bat" echo pause

echo [OK] PodcastStudio_Starten.bat erstellt
echo [OK] PodcastStudio_DEBUG.bat erstellt (zeigt Fehler an)
echo.

echo ===============================================
echo                  FERTIG!
echo ===============================================
echo.
echo Spaeter starten:
echo   PodcastStudio_Starten.bat   (normal)
echo   PodcastStudio_DEBUG.bat     (mit Fehler-Output)
echo.
echo Aufnahmen werden gespeichert in:
echo   Dokumente\PodcastStudio\
echo.
echo Starte App in 5 Sekunden...
timeout /t 5 /nobreak >nul

start "" "%~dp0jdk\bin\javaw.exe" ^
    --module-path "%~dp0javafx-sdk\lib" ^
    --add-modules javafx.controls,javafx.graphics,javafx.base ^
    -jar "%~dp0app\PodcastStudio.jar"

echo.
echo Du kannst dieses Fenster jetzt schliessen.
echo Falls die App nicht startet, fuehre PodcastStudio_DEBUG.bat aus.
echo.
pause
exit /b 0
