@echo off
title Whisper Setup - Speech-to-Text fuer PodcastStudio
color 0A

REM ===============================================
REM  Whisper.cpp + Sprachmodell herunterladen
REM ===============================================

echo.
echo ===============================================
echo   WHISPER SETUP - Speech-to-Text
echo ===============================================
echo.
echo Dieses Script laedt herunter:
echo   [1] whisper.cpp Binary (Windows x64)   ~10 MB
echo   [2] Sprachmodell ggml-small.bin        ~488 MB
echo.
echo Gesamt: ca. 500 MB
echo Speicherort: %~dp0whisper\
echo.
echo Whisper laeuft komplett offline und ist gratis.
echo Es transkribiert deutsche und englische Audio sehr gut.
echo.
echo Druecke eine Taste zum Starten - oder Strg+C zum Abbrechen
pause >nul
echo.

cd /d "%~dp0"

REM Whisper-Ordner anlegen
if not exist "whisper" mkdir "whisper"
cd whisper

REM ===============================================
REM  [1] Whisper.cpp Binary herunterladen
REM ===============================================
echo ===============================================
echo  [1/2] Whisper-Binary herunterladen
echo ===============================================
echo.

if exist "whisper-cli.exe" (
    echo [OK] whisper-cli.exe bereits vorhanden.
    goto :model
)
if exist "main.exe" (
    echo [OK] main.exe bereits vorhanden.
    goto :model
)

echo Lade Whisper.cpp Windows-Binary herunter...
echo.

powershell -ExecutionPolicy Bypass -NoProfile -Command ^
  "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
  "$ProgressPreference='Continue';" ^
  "Write-Host 'Suche aktuelle Whisper-Release...';" ^
  "$api='https://api.github.com/repos/ggml-org/whisper.cpp/releases/latest';" ^
  "$rel=Invoke-RestMethod -Uri $api -UseBasicParsing;" ^
  "$asset=$rel.assets | Where-Object { $_.name -eq 'whisper-blas-bin-x64.zip' } | Select-Object -First 1;" ^
  "if (-not $asset) { $asset=$rel.assets | Where-Object { $_.name -eq 'whisper-bin-x64.zip' } | Select-Object -First 1 };" ^
  "Write-Host ('Download ' + $asset.name + ' (' + [math]::Round($asset.size/1MB,1) + ' MB)');" ^
  "Invoke-WebRequest -Uri $asset.browser_download_url -OutFile 'whisper.zip' -UseBasicParsing;" ^
  "Write-Host 'Entpacke...';" ^
  "Expand-Archive -Path 'whisper.zip' -DestinationPath 'whisper_tmp' -Force;" ^
  "$exe = Get-ChildItem 'whisper_tmp' -Recurse -Filter 'whisper-cli.exe' | Select-Object -First 1;" ^
  "if (-not $exe) { $exe = Get-ChildItem 'whisper_tmp' -Recurse -Filter 'main.exe' | Select-Object -First 1 };" ^
  "if ($exe) {" ^
  "  $dir = $exe.Directory.FullName;" ^
  "  Get-ChildItem $dir | ForEach-Object { Copy-Item $_.FullName -Destination '.' -Force }" ^
  "};" ^
  "Remove-Item 'whisper.zip','whisper_tmp' -Recurse -Force -ErrorAction SilentlyContinue;" ^
  "Write-Host 'Whisper-Binary fertig.'"

if not exist "whisper-cli.exe" if not exist "main.exe" (
    echo.
    echo [FEHLER] Whisper-Download fehlgeschlagen!
    echo.
    echo Manuelle Loesung:
    echo   1. https://github.com/ggerganov/whisper.cpp/releases im Browser oeffnen
    echo   2. whisper-bin-x64.zip herunterladen
    echo   3. Entpacken und alle Dateien in den Ordner kopieren:
    echo      %~dp0whisper\
    echo.
    pause
    exit /b 1
)
echo [OK] Whisper-Binary installiert.

:model
echo.
REM ===============================================
REM  [2] Sprachmodell ggml-small.bin herunterladen
REM ===============================================
echo ===============================================
echo  [2/2] Sprachmodell herunterladen
echo ===============================================
echo.

if exist "ggml-small.bin" (
    echo [OK] ggml-small.bin bereits vorhanden.
    goto :done
)

echo Lade Sprachmodell ggml-small.bin herunter (~488 MB)...
echo Das kann je nach Internet ein paar Minuten dauern...
echo.

powershell -ExecutionPolicy Bypass -NoProfile -Command ^
  "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
  "$ProgressPreference='Continue';" ^
  "$url='https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin?download=true';" ^
  "Write-Host 'Download von Hugging Face...';" ^
  "Invoke-WebRequest -Uri $url -OutFile 'ggml-small.bin' -UseBasicParsing;" ^
  "Write-Host 'Sprachmodell fertig.'"

if not exist "ggml-small.bin" (
    echo.
    echo [FEHLER] Modell-Download fehlgeschlagen!
    echo.
    echo Manuelle Loesung:
    echo   1. https://huggingface.co/ggerganov/whisper.cpp im Browser oeffnen
    echo   2. ggml-small.bin herunterladen
    echo   3. In den Ordner kopieren:
    echo      %~dp0whisper\
    echo.
    pause
    exit /b 1
)

REM Pruefe Mindestgroesse (~400 MB)
for %%I in ("ggml-small.bin") do set size=%%~zI
if %size% LSS 400000000 (
    echo [WARNUNG] Modell scheint unvollstaendig - bitte WHISPER_SETUP.bat erneut ausfuehren.
)
echo [OK] Sprachmodell installiert.

:done
echo.
echo ===============================================
echo                  FERTIG!
echo ===============================================
echo.
echo Whisper ist jetzt einsatzbereit.
echo.
echo So nutzt du es:
echo   1. PodcastStudio_Starten.bat starten
echo   2. Im EDITOR-Tab eine Aufnahme laden
echo   3. Auf "Transkribieren" klicken (lila Button)
echo   4. Warten - Whisper transkribiert das Audio
echo   5. Ergebnis erscheint mit Zeitstempeln
echo.
echo Installiertes Modell:  ggml-small.bin (sehr gute deutsche Qualitaet)
echo.
pause
exit /b 0
