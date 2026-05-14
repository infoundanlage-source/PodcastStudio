# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

Das Projekt nutzt **kein Maven/Gradle** — Build via portables JDK + javac/jar. Alle Befehle laufen vom Projekt-Root.

**Standard-Build (kompiliert + packt JAR + startet App):**
```powershell
.\SETUP.bat
```
SETUP.bat ist idempotent — überspringt Downloads wenn `jdk\`, `javafx-sdk\` und `ffmpeg.exe` schon existieren.

**Inkrementeller Compile + JAR (typischer Entwicklungs-Zyklus):**
```powershell
Get-Process -Name "java*" -ErrorAction SilentlyContinue | Stop-Process -Force
Remove-Item app\classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory app\classes -Force | Out-Null
& .\jdk\bin\javac.exe --module-path .\javafx-sdk\lib `
    --add-modules javafx.controls,javafx.graphics,javafx.base `
    -encoding UTF-8 -d app\classes src\main\java\com\podcast\PodcastStudio.java

# Manifest MUSS ASCII sein (kein BOM) — sonst "invalid header field name" beim jar-Start
[System.IO.File]::WriteAllText("app\manifest.txt",
    "Manifest-Version: 1.0`r`nMain-Class: com.podcast.PodcastStudio`r`n`r`n",
    [System.Text.Encoding]::ASCII)
& .\jdk\bin\jar.exe cfm app\PodcastStudio.jar app\manifest.txt -C app\classes .
```

**Starten:**
- `PodcastStudio_Starten.bat` — Normal (javaw, kein Konsolen-Output)
- `PodcastStudio_DEBUG.bat` — Mit STDERR sichtbar (java statt javaw)
- Direkt mit STDERR-Capture: `& .\jdk\bin\java.exe --module-path .\javafx-sdk\lib --add-modules javafx.controls,javafx.graphics,javafx.base -jar .\app\PodcastStudio.jar`

**Whisper (Speech-to-Text, optional):**
- `WHISPER_SETUP.bat` lädt `whisper-cli.exe` + `ggml-small.bin` (~500 MB) in `whisper\`
- App findet sie automatisch via `findWhisper()` / `findWhisperModel()`

**Es gibt keine Tests** — Verifikation läuft per Smoke-Test (App startet, STDERR leer, Screenshot vergleichen).

## Architektur

Die gesamte App ist **eine einzige Klasse**: `src/main/java/com/podcast/PodcastStudio.java` (~3100 Zeilen). Bewusste Entscheidung — vereinfacht Distribution als Single-File-JAR ohne Maven-Build-Komplexität.

### Audio-Pipeline (Threading-Modell)

Das Audio läuft über **mehrere Daemon-Threads**, die über `volatile`-Flags und `AtomicBoolean` synchronisiert sind:

- `recordThread` — liest vom `inputLine` (`TargetDataLine`), schreibt in `recordBuf` (`ByteArrayOutputStream`)
- `monitorMicThread` — eigene `TargetDataLine` für Live-Monitor wenn NICHT aufgenommen wird
- `monitorThread` — liest aus `monitorQueue` und schreibt zum `monitorLine` (Lautsprecher)
- `backingThread` / `playThread` / `editorPlayThread` — verschiedene `SourceDataLine`-Wiedergaben

**Wichtige Regel:** Der `recordThread` und `monitorMicThread` dürfen **nie gleichzeitig** dieselbe Mic-Linie offen halten. Wenn Aufnahme startet, schließt `startRecord()` zuerst die `monitorMicLine`. Nach `stopRecord()` wird `monitorMicReader` nach 300ms (`STOP_RECORD_DELAY_MS`) neu gestartet.

### Audio-Verzögerung (Live-Monitor)

Die Verzögerung wird **NICHT** über `Thread.sleep()` zwischen Chunks implementiert (das würde Stottern erzeugen), sondern über ein **Silence-Pre-Buffer**:

- Bei Start des Monitor-Threads: `writeSilenceMs(spk, monitorDelayMs)` schreibt N ms Stille in den Lautsprecher-Buffer
- Alle nachfolgenden Audio-Chunks werden um diese Zeit "nach hinten" geschoben
- Dynamische Änderung: bei Increase mehr Stille einschieben, bei Decrease Bytes aus Queue verwerfen

### State Machine

```
recording=false, paused=false  →  IDLE     (statusLbl "BEREIT")
recording=true,  paused=false  →  REC      (statusLbl "● REC", rot)
recording=true,  paused=true   →  PAUSE    (statusLbl "⏸ PAUSE", orange)
```

Pause-Tracking via `Instant pauseStart` + `long totalPausedMs` — der Timer rechnet
`elapsed = (wallMs - totalPausedMs) / 1000`, NICHT einfache Zeitdifferenz.

### Editor-Wiedergabe Cursor

`editorTogglePlay()` spawnt **zwei** Threads:
1. **Audio-Player** — schreibt blockierend zum `SourceDataLine`
2. **Cursor-Updater** — liest `line.getLongFramePosition()` alle 40ms (`EDITOR_FRAME_INTERVAL_MS`), updated `cursorPos` via `Platform.runLater(() -> { cursorPos = ...; drawEditor(); })`

`drawEditor()` hat **Peak-Caching** (`cachedPeaks` / `cachedPeaksWidth` / `cachedPeaksAudio`) — bei in-place Modifikation (`applyFade`, `applyNormalize`) MUSS `cachedPeaks = null;` gesetzt werden bevor `drawEditor()` aufgerufen wird, sonst zeigt der Editor veraltete Wellenform.

### UI-Skalierung

Die ganze BorderPane wird in einer `Group` mit `Scale`-Transform gewrappt (Pivot 0,0):
- `root.prefWidth = scene.width / uiScaleProp`
- `scaleT.x = scaleT.y = uiScaleProp`

Damit skaliert die gesamte UI live ohne Layout-Neuberechnung. Initial-Wert via `Screen.getPrimary().getVisualBounds()` clamped, damit App auf kleinen Monitoren nicht über den Bildschirmrand wächst.

### Dark-Theme

Global CSS-Stylesheet wird in `applyDarkThemeStylesheet(scene)` als Data-URI angehängt. Stylt Sub-Komponenten die inline-Style nicht erreicht: Tab-Labels (`.tab:selected .tab-label`), Scrollbar-Thumbs, ComboBox-Popup-Listen, Checkbox-Boxen.

**`styledLbl(text, color, font, size, weight)`** Helper erkennt `weight == "italic"` und mapped zu `-fx-font-style:italic; -fx-font-weight:normal` — sonst gibt es CSS-Parsing-Warnings beim Start.

## Hot-Plug Geräte-Erkennung

Ein Daemon-Thread `device-watcher` ruft alle 2 Sek (`DEVICE_WATCH_INTERVAL_MS`) `currentMixerNames()` auf und vergleicht mit bekannter Liste. Bei Änderung: `populateMics() + populateOutputs()` via `Platform.runLater`. Kein OS-Event — bewusst Polling, weil JDK keine Hot-Plug-API hat.

## Externe Prozesse

**FFmpeg** wird für MP3-Export, Speed-Change (atempo-Filter), und Format-Konvertierung von Backing-Tracks/Editor-Loads aufgerufen. Pfad-Resolution in `findFFmpeg()`: zuerst `./ffmpeg.exe`, dann PATH-Suche.

**Whisper.cpp** wird via SRT-Output aufgerufen (`-osrt`), das Ergebnis durch `parseSrt()` zu `List<TranscriptSegment>` parsiert. Process-stdout wird in einem separaten Thread konsumiert um Pipe-Blocking zu vermeiden.

## Repo

- GitHub: https://github.com/infoundanlage-source/PodcastStudio
- Default-Branch: `main`
- Auth via `gh` CLI mit Token (`~/.claude.json.bak` enthält Backup der Claude-Config bei MCP-Setup)

## PowerShell-Gotchas

- `.ps1`-Scripts blockiert durch Execution-Policy — immer `.cmd`-Endung verwenden: `npx.cmd`, `npm.cmd` statt `npx`, `npm`
- Manifest-Datei MUSS in ASCII geschrieben werden — UTF-8 BOM bricht `jar`-Tool
- `git` selber muss NICHT mit `cd` prefixed werden — das System-Reminder verbietet `cd <project> && git ...`
- App-Prozess hält `app\PodcastStudio.jar` gesperrt — vor jeder Neu-Kompilierung `Stop-Process -Name "java*" -Force`
