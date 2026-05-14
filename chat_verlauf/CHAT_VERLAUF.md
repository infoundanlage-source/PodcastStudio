# PodcastStudio v4 — Kompletter Entwicklungs-Chat-Verlauf

**Projekt:** PodcastStudio Pro v4.0 (JavaFX)
**Sprache:** Java mit JavaFX 21
**Zeitraum:** Mehrere Sitzungen — Bug-Fixes, Features, UI-Redesign
**Datei-Ort:** `C:\Users\cad\Downloads\23233\PodcastStudio_v4_AllInOne\PodcastStudio_v4_AllInOne\`

---

## 📚 Inhaltsverzeichnis

1. [Erste Analyse & Bugfixes](#1-erste-analyse--bugfixes)
2. [UI-Drift-Bug (schwimmende Oberfläche)](#2-ui-drift-bug)
3. [Mikrofon-Erkennung & Einstellungen](#3-mikrofon-erkennung--einstellungen)
4. [Fenster-Buttons wiederhergestellt](#4-fenster-buttons-wiederhergestellt)
5. [Diskussion: React/Vite-Alternative](#5-diskussion-reactvite-alternative)
6. [YouTube-Design + Auto-Scaling](#6-youtube-design--auto-scaling)
7. [Hot-Plug Geräteerkennung](#7-hot-plug-geräteerkennung)
8. [Live-Monitor Bug-Fix](#8-live-monitor-bug-fix)
9. [Intensive Code-Prüfung](#9-intensive-code-prüfung)
10. [Verzögerungs-Bug (Stottern)](#10-verzögerungs-bug-stottern)
11. [Editor-Cursor Animation](#11-editor-cursor-animation)
12. [Quick-Buttons + Mikro-Mute (Auto)](#12-quick-buttons--mikro-mute-auto)
13. [Mikrofon-Stummschalter](#13-mikrofon-stummschalter)
14. [Speech-to-Text mit Whisper](#14-speech-to-text-mit-whisper)
15. [Calibri-Font + Sichtbarkeit](#15-calibri-font--sichtbarkeit)
16. [Phase-2 UI-Politur](#16-phase-2-ui-politur)
17. [Doppelter-Rahmen Bug](#17-doppelter-rahmen-bug)

---

## 1. Erste Analyse & Bugfixes

**User:** Ordner lesen. Kannst du das Programm analysieren, optimieren und eine lauffähige Version erstellen?

**Ausgangspunkt:** Java-Projekt mit `PodcastStudio.java` (2327 Zeilen) — JavaFX-Podcast-Recorder mit Aufnahme + Editor + FFmpeg-Export. Setup-Skript für portable JDK 21 + JavaFX-SDK + FFmpeg.

**Gefixt:**
- `onClose()` Fehler: `List.of(...)` → `Arrays.asList(...)` (List.of erlaubt keine null-Werte, was die App beim Schließen abstürzen ließ)
- Pause-Tracking: `Instant pauseStart` + `long totalPausedMs` für korrekte Zeitberechnung
- Timer-Bug: `elapsed = (int)((wallMs - totalPausedMs) / 1000)` statt einfacher Zeitdifferenz
- Marker-Löschung: `markers.remove(idxFinal)` → `markers.remove(mFinal)` (Off-by-One-Fix mit Marker-Referenz)
- Waveform wird beim Aufnahme-Stop gelöscht

---

## 2. UI-Drift-Bug

**User:** Im Programm selbst schwimmt die ganze Oberfläche nach rechts und es hört nicht auf.

**Diagnose:** Layout-Feedback-Loop:
- GridPane mit 48% + 48% + 50px + Gaps = mehr als 100%
- `Line.endX` an `width` gebunden → triggert Layout → triggert width-Änderung → Endlosschleife

**Fix:**
```java
// Vorher: GridPane mit percent widths
// Nachher: HBox mit HGrow für stabiles Layout
HBox deviceRow = new HBox(12);
micBox.setFillWidth(true);
HBox.setHgrow(micBox, Priority.ALWAYS);
```
- `Line` für Divider durch `Region` ersetzt
- `setPrefWidth(9999)` entfernt

---

## 3. Mikrofon-Erkennung & Einstellungen

**User:** Programm geöffnet, Mikrophon wird es nicht sofort erkannt. Programm neustarten, Mikrophon wird erkannt. Die Schrift ist zu klein auf der Oberfläche. Erstelle eine Option in der man die Schrift grösse und Schriftfarbe anzupassen kann.

**Lösungen:**

### Mic-Retry-Mechanismus
```java
Thread micRetry = new Thread(() -> {
    Thread.sleep(900);
    Platform.runLater(() -> { populateMics(); populateOutputs(); });
    Thread.sleep(1500);
    Platform.runLater(() -> { populateMics(); populateOutputs(); });
}, "mic-retry");
micRetry.setDaemon(true);
micRetry.start();
```

### Einstellungs-Dialog
- `DoubleProperty uiScaleProp` für Live-Skalierung (0.8x bis 2.0x)
- `static String FG` (mutable) für Textfarbe — gilt nach Neustart
- `Group` als Scene-Root für Scale-Transform ohne Clipping
- `java.util.prefs.Preferences` zum Speichern

```java
Scale scaleT = new Scale();
scaleT.xProperty().bind(uiScaleProp);
scaleT.yProperty().bind(uiScaleProp);
root.getTransforms().add(scaleT);
```

---

## 4. Fenster-Buttons wiederhergestellt

**User:** Die Symbole Minimieren, Maximieren und Beenden wurden anscheinend entfernt.

**Diagnose:** Default-Scene-Größe 1280×1.2 = 1536 × 950×1.2 = 1140 Pixel → größer als Bildschirm → Titelleiste off-screen.

**Fix:** Screen-aware Initial-Size:
```java
javafx.geometry.Rectangle2D vis = Screen.getPrimary().getVisualBounds();
double initW = Math.min(1280 * uiScaleProp.get(), vis.getWidth()  - 80);
double initH = Math.min(950  * uiScaleProp.get(), vis.getHeight() - 80);
Scene scene = new Scene(sceneRoot, initW, initH);
```

---

## 5. Diskussion: React/Vite-Alternative

**User:** Erstelle eine-App mit vite ... React aber von dieser app ... Bei welcher Programmiersprache wäre es möglich [Live-UI-Editing]?

**Antwort:** Vergleich der Optionen:

| Framework | Sprache | Live-Editing |
|---|---|---|
| **React + Vite** | TS/JS | ✅ HMR — 50ms |
| **Flutter** | Dart | ✅ Hot Reload |
| **SwiftUI** | Swift | ✅ Xcode Canvas |
| **Jetpack Compose** | Kotlin | ✅ AS Live Edit |
| **WPF/MAUI** | C# | ⚠️ XAML HR |
| **JavaFX** | Java | ❌ Kein eingebautes |

**User:** Da kann ich aber nicht alle Funktionen umsetzen.

**Entscheidung:** Weiter mit JavaFX — Browser kann viele Features nicht (Mic-Geräte-Auswahl, FFmpeg-Subprozess, Dateisystem-Zugriff, Bluetooth-Erkennung).

---

## 6. YouTube-Design + Auto-Scaling

**User:** Bessere Oberfläche ist ein guter Vorschlag optisch soll es an YouTube erinnern. Die Schrift muss etwas grösser sein. Die App soll sich an die Bildschirmgröße des Anwenders skalieren.

**Umgesetzt:**
- Farbpalette: YouTube-inspiriert
  - `BG = #0f0f0f` (Haupt-Hintergrund)
  - `SURFACE = #212121`
  - `CARD = #282828`
  - `ACCENT = #ff0000` (YouTube-Rot)
  - `FG = #f1f1f1` (Heller Text)
- Größerer Header mit rotem Logo-Badge (52×36px)
- Auto-Skalierung beim Start:
  ```java
  double autoScale = Math.max(0.8, Math.min(2.0,
      Math.min(vis.getWidth() / 1600.0, vis.getHeight() / 900.0)));
  ```
- Alle Schriften größer (8→11, 9→12, 10→13, 11→14, 28→34)
- Buttons mit mehr Padding

---

## 7. Hot-Plug Geräteerkennung

**User:** Das Mikrophon wird nach dem einstecken nicht erkannt, Programm muss beendet werden und neugestartet.

**Lösung:** `startDeviceWatcher()` Thread alle 2 Sekunden:
```java
Thread watcher = new Thread(() -> {
    Set<String> known = currentMixerNames();
    while (true) {
        Thread.sleep(2000);
        Set<String> current = currentMixerNames();
        if (!current.equals(known)) {
            Platform.runLater(() -> { populateMics(); populateOutputs(); });
            showHotplugHint("🔌 Neues Gerät erkannt: " + name);
        }
    }
}, "device-watcher");
watcher.setDaemon(true);
```

---

## 8. Live-Monitor Bug-Fix

**User:** Der Ton beim sprechen wird nicht über die Lautsprecher ausgegeben.

**Diagnose:** Monitor las nur aus Queue — Queue wurde nur vom Record-Thread befüllt → ohne Aufnahme kein Ton.

**Fix:** Dual-Mode-Monitor mit eigener Mic-Linie:
- **Keine Aufnahme + Monitor an** → `monitorMicLine` (eigene TargetDataLine) liest direkt vom Mikrofon
- **Aufnahme aktiv + Monitor an** → Record-Thread füllt monitorQueue, Monitor liest davon
- **Aufnahme stoppt** → Mic-Reader-Thread wird automatisch wieder gestartet

```java
volatile boolean monitorSuppressedByEditor = false;
TargetDataLine monitorMicLine;
Thread monitorMicThread;

void startMonitorMicReader() {
    monitorMicThread = new Thread(() -> {
        TargetDataLine mic = ...;
        mic.open(FORMAT, 4096);
        mic.start();
        monitorMicLine = mic;
        byte[] buf = new byte[2048];
        while (monitorActive.get() && !recording) {
            int read = mic.read(buf, 0, buf.length);
            if (read < 0) break;   // Line wurde geschlossen
            monitorQueue.offer(Arrays.copyOf(buf, read));
            updateLevel(buf, read);
        }
    });
    monitorMicThread.setDaemon(true);
    monitorMicThread.start();
}
```

---

## 9. Intensive Code-Prüfung

**User:** Kontrolliere den Code intensiv auf Fehler.

**Gefundene Bugs:**

### KRITISCH
1. **100%-CPU-Spin im Mic-Reader**: Bei geschlossener Mic-Linie return `mic.read()` sofort -1 → Endlos-Loop ohne Sleep
   - Fix: `if (read < 0) break;`
2. **`monitorMicLine` nicht in `onClose()`** geschlossen → Resource-Leak
3. **`stage.setMinWidth/Height`** ohne Screen-Clamping → unsichtbare App auf kleinen Monitoren

### MINOR
4-9. Sechs Threads ohne `setDaemon(true)` (hotplug-hint, monitor-mic-restart, backing-load, editor-load, speed-thread, mp3-export)

Alle Fixes angewendet.

---

## 10. Verzögerungs-Bug (Stottern)

**User:** Kontrolliere die Verzögerung in Verbindung mit den Auswahlmöglichkeiten 0 ms, 50 ms usw.

**Diagnose:** Der Monitor-Thread machte `Thread.sleep(monitorDelayMs)` **vor jedem Audio-Chunk** (2048 Bytes ≈ 21ms). Bei 50ms Delay:
- 21ms Audio gespielt
- 29ms Stille (während Sleep)
- 21ms Audio
- 29ms Stille...

→ Stotterndes Audio statt einer echten Verzögerung.

**Fix:** Stille-Pre-Buffer-Methode:
```java
// Helper
void writeSilenceMs(SourceDataLine line, int ms) {
    int remaining = msToBytes(ms);
    byte[] silence = new byte[Math.min(remaining, 4096)];
    while (remaining > 0 && monitorActive.get()) {
        int chunk = Math.min(remaining, silence.length);
        line.write(silence, 0, chunk);
        remaining -= chunk;
    }
}

// Initial Delay anwenden
int appliedDelayMs = 0;
if (monitorDelayMs > 0) {
    writeSilenceMs(spk, monitorDelayMs);
    appliedDelayMs = monitorDelayMs;
}

// In der Schleife: dynamische Delay-Änderung
while (monitorActive.get()) {
    byte[] data = monitorQueue.poll(100, TimeUnit.MILLISECONDS);
    if (data == null) continue;

    int targetDelay = monitorDelayMs;
    if (targetDelay > appliedDelayMs) {
        // Delay erhöht → mehr Stille einschieben
        writeSilenceMs(spk, targetDelay - appliedDelayMs);
        appliedDelayMs = targetDelay;
    } else if (targetDelay < appliedDelayMs) {
        // Delay verringert → Audio überspringen
        int dropBytes = msToBytes(appliedDelayMs - targetDelay);
        while (dropBytes > 0 && !monitorQueue.isEmpty()) {
            byte[] dropChunk = monitorQueue.poll();
            if (dropChunk != null) dropBytes -= dropChunk.length;
        }
        appliedDelayMs = targetDelay;
    }
    // ... write data ...
}
```

---

## 11. Editor-Cursor Animation

**User:** Kontrolliere den Editor. Beim abspielen einer Audio Datei bewegt sich die Tonspur nicht.

**Diagnose:** `editorTogglePlay()` schrieb das gesamte Audio in den Buffer und wartete — `cursorPos` wurde nie aktualisiert.

**Fix:** 25-fps Cursor-Updater mit `line.getLongFramePosition()`:
```java
Thread cursorUpd = new Thread(() -> {
    while (editorPlaying.get()) {
        long playedFrames = lineFinal.getLongFramePosition();
        int newCursor = fromFinal + (int) playedFrames;
        Platform.runLater(() -> {
            cursorPos = newCursor;
            drawEditor();
        });
        Thread.sleep(40);  // ~25 fps
    }
});
cursorUpd.setDaemon(true);
cursorUpd.start();
```

---

## 12. Quick-Buttons + Mikro-Mute (Auto)

**User:** Die Schnellauswahl der Verzögerung funktioniert nicht. Ich benötige noch eine Funktion für das Mikro. Damit ich beim Schneiden der Tonspur mich nicht selber höre.

**Quick-Buttons Fix:**
```java
b.setOnAction(e -> {
    monitorDelayMs = v;                    // Direkt setzen
    monitorDelayLbl.setText(v + " ms");    // Label
    monitorDelaySlider.setValue(v);        // Slider visuell
    // Bonus: Lila-Highlight für visuelles Feedback
});
```

**Editor-Auto-Mute:** Beim Wechsel auf Editor-Tab wird der Monitor automatisch gestoppt:
```java
volatile boolean monitorSuppressedByEditor = false;

tp.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
    if (n == editorTab && monitorActive.get()) {
        monitorSuppressedByEditor = true;
        stopMonitor();
    } else if (n == recorderTab && monitorSuppressedByEditor) {
        monitorSuppressedByEditor = false;
        if (monitorCheck.isSelected()) startMonitor();
    }
});
```

Lila Banner im Editor-Tab: **"🔇 MIKROFON STUMM während der Bearbeitung"**

---

## 13. Mikrofon-Stummschalter

**User:** Ich benötige eine Stummfunktion für das Mikrofon.

**Lösung:** `ToggleButton micMuteBtn` neben Kopfhörer-Modus mit zwei Zuständen:

| Zustand | Aussehen | Wirkung |
|---|---|---|
| 🎙 **Mikrofon AN** | Grauer Button | Normal aufnehmen, Monitor hörbar |
| 🔇 **MIKROFON STUMM** | **Roter Button** | Stille überall |

Implementierung — beide Audio-Pfade beachten:
```java
// In recordThread:
if (read > 0 && !paused) {
    if (micMuted) Arrays.fill(buf, 0, read, (byte) 0);  // Stille
    synchronized (recordBuf) { recordBuf.write(buf, 0, read); }
    updateLevel(buf, read);
    if (monitorActive.get()) monitorQueue.offer(Arrays.copyOf(buf, read));
}

// In monitorMicThread:
if (read > 0) {
    if (micMuted) Arrays.fill(buf, 0, read, (byte) 0);  // Stille
    monitorQueue.offer(Arrays.copyOf(buf, read));
}
```

---

## 14. Speech-to-Text mit Whisper

**User:** Ist es möglich Speech to text einzubauen? ... Ich möchte nur, dass das gesprochene mit geschrieben wird und zusätzlich übersichtlich dargestellt wird. Was ist dafür am besten geeignet? ... Ja erstmal Whisper.

**Empfehlung:** Whisper.cpp (offline, gratis, exzellente deutsche Qualität)

**Komponenten:**

### WHISPER_SETUP.bat
Lädt automatisch:
- `whisper-bin-x64.zip` von GitHub (ggml-org/whisper.cpp)
- `ggml-small.bin` Sprachmodell von Hugging Face (~488 MB)
- Speicherort: `whisper/` Unterordner

### Java-Integration
```java
String whisperExe   = findWhisper();    // sucht in app-Ordner
String whisperModel = findWhisperModel();

void editorTranscribe() {
    File wav = File.createTempFile("ps_whisper_", ".wav");
    writeWav(wav, editAudio);
    
    ProcessBuilder pb = new ProcessBuilder(
        whisperExe, "-m", whisperModel,
        "-f", wav.getAbsolutePath(),
        "-l", "de", "-osrt",
        "-of", outBase.getAbsolutePath()
    );
    Process p = pb.start();
    p.waitFor();
    
    // SRT parsen
    List<TranscriptSegment> segs = parseSrt(srt);
    showTranscript(segs);
}
```

### Transkript-Fenster
- Klickbare Liste mit Zeitstempeln
- Klick auf Segment → Cursor springt zur Stelle im Audio
- Export als TXT / SRT
- Clipboard-Kopie
- Zebra-Streifen für Lesbarkeit

---

## 15. Calibri-Font + Sichtbarkeit

**User:** [Screenshot mit grünen Markierungen] Verwende die Schriftart Calibri für das gesamte Programm.

**Globale Font-Ersetzung:**
- `Segoe UI,Arial` → `Calibri,Segoe UI,Arial`
- `Consolas,Monospace` → `Calibri,Segoe UI,Arial`
- `Bahnschrift,Arial` → `Calibri,Segoe UI,Arial`

**Sichtbarkeits-Fixes (von MUTED 11px → FG bold 14px):**
- MIKROFON · Eingang
- WIEDERGABE · Ausgang
- VERZÖGERUNG (optional)
- LAUTSTÄRKE
- SCHNELLAUSWAHL
- LIVE · WAVEFORM
- EINGANGSPEGEL
- EPISODE / TITEL
- AUFNAHMEN (16px)

**Neue `controlBtn`** für Pause/Stopp/Marker — bleiben auch im disabled-State lesbar (Opacity 1.0).

---

## 16. Phase-2 UI-Politur

**User:** Prüfe den Code nochmal, da sind einige Fehler in der Oberfläche.

**CSS-Warning behoben:** `styledLbl` mit "italic" als font-weight produzierte CSS-Fehler.
```java
boolean isItalic = "italic".equalsIgnoreCase(weight);
String w = isItalic ? "normal" : weight;
String s = isItalic ? "italic" : "normal";
l.setStyle("...-fx-font-weight:" + w + ";-fx-font-style:" + s + ";");
```

**Globales Dark-Theme-Stylesheet:**
```java
void applyDarkThemeStylesheet(Scene scene) {
    String css =
        // Tabs: aktiv rot+fett
        ".tab-pane > .tab-header-area > .headers-region > .tab:selected > .tab-container > .tab-label { -fx-text-fill:" + ACCENT + "; -fx-font-weight:bold; }" +
        // Scrollbars dunkel
        ".scroll-bar { -fx-background-color:" + SURFACE + "; }" +
        ".scroll-bar .thumb { -fx-background-color:" + BORDER + "; }" +
        // Checkbox-Box dunkel
        ".check-box .box { -fx-background-color:" + CARD + "; }" +
        ".check-box:selected .box { -fx-background-color:" + ACCENT + "; }" +
        // ComboBox-Display + Popup
        ".combo-box .list-cell { -fx-background-color:" + CARD + "; -fx-text-fill:" + FG + "; }" +
        ".combo-box-popup .list-view .list-cell { -fx-background-color:" + CARD + "; -fx-text-fill:" + FG + "; }" +
        ...;
    scene.getStylesheets().add("data:text/css," + URLEncoder.encode(css, UTF_8));
}
```

**Tooltips:** Neue `styledTooltip()` Helper-Methode für Dark-Theme-Tooltips.

**Spacing-Fixes:** Editor-Button-Reihen von 4-5px auf 6-8px erhöht.

---

## 17. Doppelter-Rahmen Bug

**User:** [Screenshot mit rotem Kreis] In dem Bild ist ein Fehler in der Grafik zu sehen.

**Diagnose:** LIVE WAVEFORM hatte **zwei verschachtelte Rahmen**:
- Äußere `wfBox` mit Border (border-radius: 8)
- Innere `wfPane` mit Border (border-radius: 6) + 4px Padding

→ Sichtbare Streifen/Schatten an den Kanten.

**Fix:** Vereinfachte Struktur:
```java
liveCanvas = new Canvas(900, 80);
Pane wfPane = new Pane(liveCanvas);
wfPane.setStyle("-fx-background-color:transparent;");  // KEIN extra Border
liveCanvas.widthProperty().bind(wfPane.widthProperty());
liveCanvas.setHeight(78);

VBox wfBox = new VBox(6, styledLbl("LIVE · WAVEFORM", ...), wfPane);
wfBox.setPadding(new Insets(10, 12, 10, 12));
wfBox.setStyle(bg(CARD) + "-fx-border-color:" + BORDER +
               ";-fx-border-width:1;-fx-border-radius:8;-fx-background-radius:8;");
```

Ergebnis: Ein sauberer Rahmen, Canvas füllt volle Breite ohne Lücken.

---

## 📦 Finale Datei-Struktur

```
PodcastStudio_v4_AllInOne/
├── SETUP.bat                        ← Setup (JDK, JavaFX, FFmpeg)
├── WHISPER_SETUP.bat                ← Whisper-Setup (optional)
├── PodcastStudio_Starten.bat        ← Normal-Start
├── PodcastStudio_DEBUG.bat          ← Debug-Start mit STDERR
├── ANLEITUNG.txt
├── app/
│   └── PodcastStudio.jar            ← ~53.7 KB
├── jdk/                             ← Portable JDK 21
├── javafx-sdk/                      ← JavaFX SDK 21
├── ffmpeg.exe                       ← FFmpeg
├── whisper/                         ← (optional, nach WHISPER_SETUP)
│   ├── whisper-cli.exe
│   └── ggml-small.bin
├── src/main/java/com/podcast/
│   └── PodcastStudio.java           ← ~3300+ Zeilen, alles in einer Datei
└── chat_verlauf/                    ← Dieser Ordner
    └── CHAT_VERLAUF.md
```

---

## 🎯 Features im finalen Build

### Aufnahme-Tab
- 🎙 Mikrofon-Auswahl (Hot-Plug automatisch)
- 🔊 Wiedergabe-Geräte-Auswahl
- 🎧 Kopfhörer-Modus (Bluetooth priorisieren)
- 🔇 Mikrofon-Stummschalter (Toggle)
- 📊 Live-Waveform (Echtzeit)
- 🎚 Eingangs-Pegel-Meter
- 🎵 Backing-Track (Musik während Aufnahme)
- 🎧 Eigene Stimme abhören (Live-Monitor) mit Verzögerung 0-500ms
- 📍 Kapitel-Marker (Taste M)
- 📁 Aufnahmen-Liste mit Play/Editor/Löschen

### Editor-Tab
- 📂 Audio laden (WAV, MP3, M4A, FLAC, OGG)
- 🎯 Cursor-Animation während Wiedergabe (25 fps)
- ✂ Cut / Copy / Paste / Delete
- 📈 Fade-In / Fade-Out (0.5s-5s)
- 🔊 Normalisierung
- 🚀 Geschwindigkeit 0.5×-2.0× (FFmpeg atempo)
- 💾 Export als WAV / MP3
- 📝 **Whisper Speech-to-Text** (offline)
- 🔇 Auto-Mute beim Tab-Wechsel

### System
- 🎨 YouTube-Dark-Theme (#0f0f0f / #ff0000)
- 🔤 Calibri durchgehend
- 📐 Auto-Skalierung an Bildschirmgröße (0.8x-2.0x)
- ⚙ Einstellungs-Dialog (Schriftgröße, -farbe)
- 💾 Preferences via `java.util.prefs.Preferences`

---

## 🐛 Behobene Bugs (Übersicht)

| # | Bug | Lösung |
|---|---|---|
| 1 | `List.of()` mit null crasht onClose | `Arrays.asList()` |
| 2 | Timer ignoriert Pausen | `totalPausedMs` tracking |
| 3 | UI schwimmt nach rechts | HBox+HGrow statt GridPane-Percent |
| 4 | Mic wird beim 1. Start nicht erkannt | 2-stufiger Mic-Retry |
| 5 | Fenster-Buttons fehlen auf kleinen Monitoren | Screen-aware initial size |
| 6 | Hot-Plug Mic wird nicht erkannt | Device-Watcher Thread |
| 7 | Monitor nur während Aufnahme hörbar | Eigener Mic-Reader-Thread |
| 8 | 100% CPU bei geschlossener Mic-Linie | `if (read < 0) break;` |
| 9 | monitorMicLine bei onClose nicht geschlossen | In TargetDataLine-Liste |
| 10 | 6 Threads nicht daemon | `setDaemon(true)` |
| 11 | Verzögerung erzeugt Stottern | Stille-Pre-Buffer statt Sleep |
| 12 | Quick-Buttons reagieren nicht | Direkt monitorDelayMs setzen |
| 13 | Editor-Cursor friert bei Play | 25fps Updater + getLongFramePosition() |
| 14 | Mic-Mute fehlt | ToggleButton + Buffer-Zero |
| 15 | CSS-Warning bei "italic" font-weight | font-style-Detection in styledLbl |
| 16 | Marker-Lösch-Button winzig | 15px + Hover-Effect |
| 17 | Refresh-Button vertikal verschoben | BOTTOM_CENTER entfernt |
| 18 | TabPane-Labels schwarz | CSS-Pfad-Selektoren |
| 19 | Dropdown-Text blass | `.combo-box .list-cell` styled |
| 20 | Empty-State zeigt nur 1 Zeile | `setWrapText(true)` |
| 21 | Tooltips weiß auf hell | `styledTooltip()` Helper |
| 22 | Scrollbars hell | Globales Scene-Stylesheet |
| 23 | Doppelte Rahmen in Waveform | wfPane Border entfernt |

---

## 💡 Technische Details

### Audio-Format
- 48 kHz, 16-bit, Mono PCM
- 96 KB/s Datenrate
- 4096-Byte Chunks im Record-Thread
- 2048-Byte Chunks im Monitor

### Threading-Modell
- Daemon-Threads für alle Hintergrundarbeiten
- AtomicBoolean für Lifecycle-Flags (`recording`, `monitorActive`, `editorPlaying`)
- LinkedBlockingQueue für Audio-Daten zwischen Reader und Output
- Platform.runLater() für JavaFX-Thread-Updates

### Persistenz
- `java.util.prefs.Preferences.userRoot().node("PodcastStudio")`
- Speichert: `uiScale`, `textColor`
- Lädt beim Start im `loadPrefs()`

### Externe Tools (optional)
- **FFmpeg**: MP3-Export, Speed-Change, Format-Konvertierung
- **Whisper.cpp**: Speech-to-Text (offline)

---

## 📅 Diskutierte aber nicht umgesetzte Ideen

- **React/Vite-Version** — verworfen, da Browser zu eingeschränkt für Audio-Geräte-Auswahl + FFmpeg
- **Vosk** für Live-Transkription während Aufnahme — als Alternative zu Whisper, falls echtzeitfähigkeit gewünscht
- **Cloud-STT** (Google, Azure) — verworfen wegen Privatsphäre + Kosten

---

*Generiert am 14.05.2026 — Komplettes Entwicklungs-Log des PodcastStudio v4 Projekts*
