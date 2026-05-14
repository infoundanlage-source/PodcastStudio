package com.podcast;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.DoubleProperty;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.stage.*;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PodcastStudio extends Application {

    // ── Colors ── YouTube-Dark-Theme (FG ist mutable: per Einstellungen änderbar)
    static final String BG         = "#0f0f0f";   // YouTube Haupthintergrund
    static final String SURFACE    = "#212121";   // YouTube Sekundär-BG
    static final String CARD       = "#282828";   // YouTube Karten/Boxen
    static final String BORDER     = "#3f3f3f";   // Trennlinien
    static final String ACCENT     = "#ff0000";   // YouTube Rot
    static final String ACCENT2    = "#ff6500";   // Orange
    static       String FG         = "#f1f1f1";   // Heller Text (YouTube-weiß)
    static final String MUTED      = "#aaaaaa";   // Gedämpfter Text
    static final String GREEN      = "#00b894";   // Erfolg / Grün
    static final String BLUE       = "#4a9eff";   // Info-Blau
    static final String PURPLE     = "#a855f7";   // Lila (Monitor)
    static final String SELECT_COL = "#1a3a5c";   // Selektion

    // ── Gecachte Color-Objekte (vermeidet Color.web()-Allocations im 60fps Hot-Path) ──
    static final Color METER_COLOR_HIGH = Color.web(ACCENT);
    static final Color METER_COLOR_MID  = Color.web(ACCENT2);
    static final Color METER_COLOR_LOW  = Color.web(GREEN);

    // ── Timing-Konstanten (vermeidet Magic Numbers) ──
    static final int MIC_RETRY_DELAY_1_MS     = 900;
    static final int MIC_RETRY_DELAY_2_MS     = 1500;
    static final int DEVICE_WATCH_INTERVAL_MS = 2000;
    static final int HOTPLUG_HINT_DURATION_MS = 3000;
    static final int MONITOR_POLL_TIMEOUT_MS  = 100;
    static final int EDITOR_FRAME_INTERVAL_MS = 40;  // ~25 fps Cursor-Update

    // ── App-Strings (vermeidet hardcoded Duplikate) ──
    static final String APP_NAME   = "PodcastStudio";
    static final String PREFS_NODE = "PodcastStudio";

    // ── UI-Strings (vermeidet copy-paste) ──
    static final String EMPTY_RECORDINGS_MSG =
        "🎙  Noch keine Aufnahmen vorhanden\n\nKlicke oben auf  ●  AUFNAHME STARTEN  um zu beginnen";
    static final String MARKER_HINT_MSG =
        "Während Aufnahme:  Klick auf 📍 Marker setzen  oder Taste M";

    // ── UI-Skalierung & Settings ──
    DoubleProperty uiScaleProp = new SimpleDoubleProperty(1.2);
    BorderPane     uiRoot;
    Stage          uiStage;

    // ── Audio ──
    static final float  SR      = 48000f;
    static final int    SS      = 16;
    static final int    CH      = 1;
    static final AudioFormat FORMAT =
        new AudioFormat(SR, SS, CH, true, false);

    // ── Recording ──
    volatile boolean recording = false;
    volatile boolean paused    = false;
    volatile boolean micMuted  = false;  // Mikrofon manuell stummgeschaltet
    TargetDataLine   inputLine;
    Thread           recordThread;
    ByteArrayOutputStream recordBuf = new ByteArrayOutputStream();
    int              elapsed   = 0;
    Instant          startTime;
    Instant          pauseStart;
    long             totalPausedMs = 0;
    ToggleButton     micMuteBtn;

    // ── Backing track ──
    float[]          backingData;
    float            backingVol = 0.6f;
    AtomicBoolean    backingPlaying = new AtomicBoolean(false);
    Thread           backingThread;
    SourceDataLine   backingLine;

    // ── Live-Monitoring ──
    AtomicBoolean    monitorActive = new AtomicBoolean(false);
    int              monitorDelayMs = 0;
    SourceDataLine   monitorLine;
    Thread           monitorThread;
    TargetDataLine   monitorMicLine;   // eigene Mic-Line für Monitor (ohne Aufnahme)
    Thread           monitorMicThread;
    LinkedBlockingQueue<byte[]> monitorQueue = new LinkedBlockingQueue<>(200);
    float            monitorVol = 0.7f;
    volatile boolean monitorSuppressedByEditor = false; // True wenn Editor-Tab aktiv ist
    Label            editorMuteHintLbl;

    // ── Markers (NEW) ──
    List<Marker>     markers = new ArrayList<>();

    // ── Recorder list playback ──
    SourceDataLine   playLine;
    Thread           playThread;
    volatile boolean playStop = false;
    Button           currentPlayBtn;
    int              currentPlayIdx = -1;

    // ── Editor ──
    float[]  editAudio;
    int      editSr = (int) SR;
    Path     editPath;
    float[]  clipboard;
    int      selStart = -1, selEnd = -1;
    int      cursorPos = 0;
    Deque<float[]> undoStack = new ArrayDeque<>();
    AtomicBoolean editorPlaying = new AtomicBoolean(false);
    SourceDataLine editorLine;
    Thread   editorPlayThread;
    int      dragStartSample = -1;

    // ── Editor-Waveform Peak-Cache (vermeidet O(n) Neuberechnung pro Frame) ──
    float[]  cachedPeaks;        // Peak-Werte pro Pixel-Spalte
    int      cachedPeaksWidth;   // Canvas-Breite zur Cache-Erstellung
    float[]  cachedPeaksAudio;   // Referenz: wenn editAudio sich aendert, Cache invalidieren

    // ── Waveform live buffer ──
    float[]          waveBuf = new float[200];
    volatile float   level   = 0f;

    // ── Recordings ──
    List<Rec> recordings = new ArrayList<>();
    int recCount = 0;

    // ── Devices ──
    List<Mixer.Info> inputMixers  = new ArrayList<>();
    List<Mixer.Info> outputMixers = new ArrayList<>();

    // ── Paths ──
    Path   outputDir;
    String ffmpeg;
    String whisperExe;     // Pfad zu whisper-cli.exe / main.exe (optional)
    String whisperModel;   // Pfad zu ggml-*.bin Modell (optional)

    // ── UI refs ──
    Label   statusLbl, timerLbl, btLbl, editFileLbl, editInfoLbl,
            selInfoLbl, timeCursorLbl, timeEndLbl, countLbl,
            monitorDelayLbl, markerCountLbl;
    Canvas  liveCanvas, editCanvas;
    Pane    meterPane;
    javafx.scene.shape.Rectangle meterFill;
    ComboBox<String> micCombo, outCombo;
    Button  btnRec, btnPause, btnStop, btnPlayEdit, btnMarker;
    TextField epEntry;
    VBox    recListBox, markerListBox;
    Slider  volSlider, monitorDelaySlider, monitorVolSlider;
    CheckBox monitorCheck, headphoneCheck;
    ComboBox<String> fadeDurCombo;

    // ═══════════════════════════════════════════
    @Override
    public void start(Stage stage) {
        loadPrefs();

        outputDir = Path.of(System.getProperty("user.home"),
                            "Documents", APP_NAME);
        try { Files.createDirectories(outputDir); } catch (Exception ignored) {}
        ffmpeg       = findFFmpeg();
        whisperExe   = findWhisper();
        whisperModel = findWhisperModel();

        BorderPane root = new BorderPane();
        uiRoot = root;
        uiStage = stage;
        root.setStyle(bg(BG));
        root.setTop(buildHeader());
        root.setCenter(buildTabs());
        root.setBottom(buildFooter());

        // Group als Scene-Root, damit Skalierung den Inhalt nicht clippt
        Group sceneRoot = new Group(root);

        // Initial-Größe: an Bildschirm anpassen damit Titelleiste nicht abgeschnitten wird
        javafx.geometry.Rectangle2D vis = Screen.getPrimary().getVisualBounds();
        double initW = Math.min(1280 * uiScaleProp.get(), vis.getWidth()  - 80);
        double initH = Math.min(950  * uiScaleProp.get(), vis.getHeight() - 80);
        Scene scene = new Scene(sceneRoot, initW, initH);
        scene.setFill(Color.web(BG));

        // ── Globales Dark-Theme-Stylesheet ──
        // Behebt: Tabs schwarz, Scrollbars hell, Checkbox-Box hell
        applyDarkThemeStylesheet(scene);

        // Größenbindung: BorderPane lebt in logischen Pixeln (Scene/Scale),
        // Skalierung als Scale-Transform mit Pivot (0,0)
        root.prefWidthProperty().bind(scene.widthProperty().divide(uiScaleProp));
        root.prefHeightProperty().bind(scene.heightProperty().divide(uiScaleProp));
        root.minWidthProperty().bind(scene.widthProperty().divide(uiScaleProp));
        root.minHeightProperty().bind(scene.heightProperty().divide(uiScaleProp));
        root.maxWidthProperty().bind(scene.widthProperty().divide(uiScaleProp));
        root.maxHeightProperty().bind(scene.heightProperty().divide(uiScaleProp));

        Scale scaleT = new Scale();
        scaleT.xProperty().bind(uiScaleProp);
        scaleT.yProperty().bind(uiScaleProp);
        scaleT.setPivotX(0);
        scaleT.setPivotY(0);
        root.getTransforms().add(scaleT);

        scene.setOnKeyPressed(e -> {
            if (e.isControlDown()) {
                switch (e.getCode()) {
                    case Z -> editorUndo();
                    case C -> editCopy();
                    case X -> editCut();
                    case V -> editPaste();
                    default -> {}
                }
            }
            if (e.getCode() == KeyCode.DELETE) editDelete();
            if (e.getCode() == KeyCode.SPACE)  editorTogglePlay();
            if (e.getCode() == KeyCode.M && recording) addMarker();
        });

        stage.setTitle("PodcastStudio Pro  ·  v4.0  ·  JavaFX");
        stage.setScene(scene);
        // Minimum-Größe an Bildschirm clampen, sonst kann App auf kleinen Monitoren
        // nicht angezeigt werden (z.B. Netbook 1024×600).
        stage.setMinWidth(Math.min(900 * uiScaleProp.get(),  vis.getWidth()  - 100));
        stage.setMinHeight(Math.min(700 * uiScaleProp.get(), vis.getHeight() - 100));
        stage.setOnCloseRequest(e -> onClose());
        stage.show();

        populateMics();
        populateOutputs();
        startLoop();

        // Mic-Retry: Audio-Subsystem braucht beim 1. Start manchmal länger
        Thread micRetry = new Thread(() -> {
            try { Thread.sleep(MIC_RETRY_DELAY_1_MS); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> { populateMics(); populateOutputs(); });
            try { Thread.sleep(MIC_RETRY_DELAY_2_MS); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> { populateMics(); populateOutputs(); });
        }, "mic-retry");
        micRetry.setDaemon(true);
        micRetry.start();

        // Hot-Plug Watcher: erkennt neu eingesteckte / entfernte Geräte automatisch
        startDeviceWatcher();

        if (ffmpeg == null) {
            Platform.runLater(() ->
                alert(Alert.AlertType.WARNING, "FFmpeg fehlt",
                    "Ohne FFmpeg sind Geschwindigkeitsänderung und MP3-Export " +
                    "nicht verfügbar.\n\nLösung: ffmpeg.exe in den App-Ordner legen."));
        }
    }

    // ═══════════════════════════════════════════
    //  PREFERENCES (Schriftgröße + Schriftfarbe)
    // ═══════════════════════════════════════════
    java.util.prefs.Preferences prefs() {
        return java.util.prefs.Preferences.userRoot().node(PREFS_NODE);
    }

    void loadPrefs() {
        try {
            // Auto-Skalierung: Bildschirmgröße des Anwenders wird erkannt
            javafx.geometry.Rectangle2D vis = Screen.getPrimary().getVisualBounds();
            double autoScale = Math.max(0.8, Math.min(2.0,
                Math.min(vis.getWidth() / 1600.0, vis.getHeight() / 900.0)));
            uiScaleProp.set(prefs().getDouble("uiScale", autoScale));
            FG = prefs().get("textColor", "#f1f1f1");
        } catch (Exception ignored) {}
    }

    void savePrefs() {
        try {
            prefs().putDouble("uiScale", uiScaleProp.get());
            prefs().put("textColor", FG);
            prefs().flush();
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════
    //  HOT-PLUG WATCHER  (Geräte live erkennen)
    // ═══════════════════════════════════════════
    /** Gibt alle aktuellen Mixer-Namen als Set zurück (für Vergleich). */
    java.util.Set<String> currentMixerNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo())
            names.add(info.getName());
        return names;
    }

    /**
     * Hintergrund-Thread, der alle 2 Sekunden prüft ob sich die
     * Geräteliste geändert hat (USB-Mikrofon / Bluetooth eingesteckt).
     * Bei Änderung werden die Dropdowns automatisch aktualisiert.
     */
    void startDeviceWatcher() {
        Thread watcher = new Thread(() -> {
            java.util.Set<String> known = currentMixerNames();
            while (true) {
                try {
                    Thread.sleep(DEVICE_WATCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                java.util.Set<String> current = currentMixerNames();
                if (!current.equals(known)) {
                    // Neue oder entfernte Geräte gefunden
                    java.util.Set<String> added   = new java.util.HashSet<>(current);
                    added.removeAll(known);
                    java.util.Set<String> removed  = new java.util.HashSet<>(known);
                    removed.removeAll(current);
                    known = current;

                    Platform.runLater(() -> {
                        populateMics();
                        populateOutputs();
                        // Kurze Meldung in der Status-Zeile anzeigen
                        if (!added.isEmpty()) {
                            String name = added.iterator().next();
                            showHotplugHint("🔌  Neues Gerät erkannt: " + name);
                        } else if (!removed.isEmpty()) {
                            showHotplugHint("⚠  Gerät entfernt – Liste aktualisiert");
                        }
                    });
                }
            }
        }, "device-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** Zeigt kurz einen Hinweis im Status-Label an (3 Sekunden, dann zurück zu BEREIT). */
    void showHotplugHint(String msg) {
        if (statusLbl == null) return;
        String prevStyle = statusLbl.getStyle();
        statusLbl.setText(msg);
        statusLbl.setStyle(prevStyle.replaceAll("-fx-text-fill:[^;]+",
            "-fx-text-fill:" + GREEN));
        Thread hint = new Thread(() -> {
            try { Thread.sleep(HOTPLUG_HINT_DURATION_MS); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> {
                // Nur zurücksetzen wenn gerade noch der Hinweis steht
                if (statusLbl.getText().equals(msg)) {
                    statusLbl.setText(recording ? (paused ? "⏸  PAUSE" : "● REC") : "BEREIT");
                    statusLbl.setStyle(prevStyle);
                }
            });
        }, "hotplug-hint");
        hint.setDaemon(true);
        hint.start();
    }

    void openSettings() {
        Stage dlg = new Stage();
        dlg.initOwner(uiStage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Einstellungen");

        VBox box = new VBox(14);
        box.setPadding(new Insets(20, 24, 20, 24));
        box.setStyle(bg(SURFACE));

        Label title = styledLbl("EINSTELLUNGEN", FG, "Calibri,Segoe UI,Arial", "18", "bold");

        // Schriftgröße
        Label scaleHead = styledLbl("Schriftgröße / Skalierung",
            ACCENT2, "Calibri,Segoe UI,Arial", "13", "bold");
        Slider scaleSlider = new Slider(0.8, 2.0, uiScaleProp.get());
        scaleSlider.setMajorTickUnit(0.2);
        scaleSlider.setMinorTickCount(1);
        scaleSlider.setShowTickMarks(true);
        scaleSlider.setShowTickLabels(true);
        scaleSlider.setSnapToTicks(false);
        scaleSlider.setStyle("-fx-control-inner-background:" + BORDER + ";");
        Label scaleLbl = styledLbl(String.format("%.0f %%", uiScaleProp.get() * 100),
            FG, "Calibri,Segoe UI,Arial", "14", "bold");
        scaleSlider.valueProperty().addListener((o, ov, nv) -> {
            uiScaleProp.set(nv.doubleValue());
            scaleLbl.setText(String.format("%.0f %%", nv.doubleValue() * 100));
        });

        // Schriftfarbe
        Label colorHead = styledLbl("Schriftfarbe (gilt nach Neustart)",
            ACCENT2, "Calibri,Segoe UI,Arial", "13", "bold");
        ColorPicker picker = new ColorPicker(Color.web(FG));
        picker.setOnAction(e -> {
            Color c = picker.getValue();
            FG = String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
        });

        // Buttons
        HBox btnBar = new HBox(8);
        btnBar.setAlignment(Pos.CENTER_RIGHT);
        Button reset = secBtn("Standard");
        reset.setOnAction(e -> {
            scaleSlider.setValue(1.2);
            picker.setValue(Color.web("#e8e6e0"));
            FG = "#e8e6e0";
        });
        Button save = accentBtn("Speichern & schließen");
        save.setOnAction(e -> {
            savePrefs();
            dlg.close();
            alert(Alert.AlertType.INFORMATION, "Gespeichert",
                "Einstellungen gespeichert.\n\n" +
                "Schriftgröße ist sofort aktiv.\n" +
                "Schriftfarbe wird beim nächsten Start übernommen.");
        });
        Button cancel = ghostBtn("Abbrechen");
        cancel.setOnAction(e -> dlg.close());
        btnBar.getChildren().addAll(reset, cancel, save);

        box.getChildren().addAll(title, divider(),
            scaleHead, scaleSlider, scaleLbl,
            divider(),
            colorHead, picker,
            divider(),
            btnBar);

        Scene s = new Scene(box, 480, 420);
        s.setFill(Color.web(BG));
        dlg.setScene(s);
        dlg.show();
    }

    Node buildHeader() {
        // ─── Haupt-Header-Bar (YouTube-Style) ───
        HBox h = new HBox(16);
        h.setPadding(new Insets(14, 28, 14, 28));
        h.setAlignment(Pos.CENTER_LEFT);
        h.setStyle(bg(SURFACE) + "-fx-border-color:" + BORDER +
                   ";-fx-border-width:0 0 2 0;");

        // YouTube-Logo-Badge: rotes Viereck + Mikrofon-Icon
        VBox logoBadge = new VBox();
        logoBadge.setAlignment(Pos.CENTER);
        logoBadge.setPrefSize(52, 36);
        logoBadge.setStyle("-fx-background-color:" + ACCENT +
                           ";-fx-background-radius:8;");
        Label icoLbl = new Label("🎙");
        icoLbl.setStyle("-fx-font-size:20px;-fx-text-fill:white;");
        logoBadge.getChildren().add(icoLbl);

        // App-Titel (YouTube-Stil: weiß, groß)
        VBox titles = new VBox(2);
        Label appName = styledLbl(APP_NAME, FG, "Calibri,Segoe UI,Arial", "22", "bold");
        Label appSub  = styledLbl("PRO  ·  v4.0  ·  Recorder  ·  Editor  ·  Mixer",
                                   MUTED, "Calibri,Segoe UI,Arial", "11", "normal");
        titles.getChildren().addAll(appName, appSub);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        // FFmpeg-Badge
        Label ff = styledLbl(
            ffmpeg != null ? " ✓ FFmpeg " : " ✗ FFmpeg ",
            ffmpeg != null ? GREEN : ACCENT,
            "Calibri,Segoe UI,Arial", "11", "bold");
        ff.setStyle(ff.getStyle() +
            "-fx-background-color:" + CARD +
            ";-fx-border-color:" + (ffmpeg != null ? GREEN : ACCENT) +
            ";-fx-border-width:1;-fx-border-radius:4;-fx-background-radius:4;" +
            "-fx-padding:4 10;");

        // Einstellungen-Button (YouTube-Stil)
        Button settingsBtn = new Button("⚙  Einstellungen");
        settingsBtn.setStyle(
            "-fx-background-color:" + CARD + ";-fx-text-fill:" + FG + ";" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:13px;" +
            "-fx-padding:9 16;-fx-cursor:hand;" +
            "-fx-border-color:" + BORDER + ";-fx-border-radius:6;" +
            "-fx-background-radius:6;");
        settingsBtn.setTooltip(styledTooltip("Schriftgröße & Farbe anpassen"));
        settingsBtn.setOnAction(e -> openSettings());

        h.getChildren().addAll(logoBadge, titles, sp, settingsBtn, ff);
        return h;
    }

    Node buildFooter() {
        Label l = styledLbl("📁  " + outputDir, MUTED,
                            "Calibri,Segoe UI,Arial", "11", "normal");
        l.setMaxWidth(Double.MAX_VALUE);
        l.setPadding(new Insets(6, 28, 10, 28));
        l.setStyle(l.getStyle() +
            bg(SURFACE) + "-fx-border-color:" + BORDER + ";-fx-border-width:1 0 0 0;");
        return l;
    }

    Node buildTabs() {
        TabPane tp = new TabPane();
        tp.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tp.setStyle(
            "-fx-background-color:" + BG + ";" +
            "-fx-tab-min-height:42px;" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:14px;");

        Tab t1 = new Tab("  🎙   AUFNAHME  ", buildRecorderTab());
        Tab t2 = new Tab("  ✂   EDITOR  ",   buildEditorTab());
        tp.getTabs().addAll(t1, t2);

        tp.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n == t2) {
                // ── Editor-Tab aktiv ──
                // Mikrofon-Monitor automatisch stummschalten, damit beim
                // Bearbeiten kein Mic-Signal auf den Lautsprecher kommt
                // (verhindert Feedback und Selbsthören).
                if (monitorActive.get()) {
                    monitorSuppressedByEditor = true;
                    stopMonitor();
                }
                if (editorMuteHintLbl != null) {
                    boolean visible = monitorSuppressedByEditor;
                    editorMuteHintLbl.setVisible(visible);
                    editorMuteHintLbl.setManaged(visible);
                }
                Platform.runLater(this::drawEditor);
            } else if (n == t1) {
                // ── Recorder-Tab aktiv ──
                // Monitor wieder anschalten, falls er vorher aktiv war
                if (monitorSuppressedByEditor) {
                    monitorSuppressedByEditor = false;
                    if (monitorCheck != null && monitorCheck.isSelected()) {
                        startMonitor();
                    }
                }
                if (editorMuteHintLbl != null) {
                    editorMuteHintLbl.setVisible(false);
                    editorMuteHintLbl.setManaged(false);
                }
                Platform.runLater(this::drawLiveWaveform);
            }
        });
        return tp;
    }

    // ═══════════════════════════════════════════
    //  RECORDER TAB (mit Live-Monitor + Marker)
    // ═══════════════════════════════════════════
    Node buildRecorderTab() {
        VBox root = new VBox(0);
        root.setStyle(bg(SURFACE));
        root.setPadding(new Insets(16, 24, 16, 24));

        // ── Status bar ──
        HBox statusBar = new HBox(12);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(0, 0, 10, 0));

        Label dot = new Label("●");
        dot.setStyle("-fx-text-fill:" + MUTED + ";-fx-font-size:18px;");
        statusLbl = styledLbl("BEREIT", MUTED, "Calibri,Segoe UI,Arial", "16", "bold");

        Region sp1 = new Region();
        HBox.setHgrow(sp1, Priority.ALWAYS);

        timerLbl = styledLbl("00:00:00", FG, "Calibri,Segoe UI,Arial", "40", "bold");

        statusBar.getChildren().addAll(dot, statusLbl, sp1, timerLbl);
        root.getChildren().add(statusBar);

        // ── Live Waveform ──
        // Vereinfachte Struktur: nur EINE Box mit Border, Canvas direkt drin
        // (vorher: wfBox-Border + wfPane-Border = doppelter Rahmen, visuelles Durcheinander)
        liveCanvas = new Canvas(900, 80);
        Pane wfPane = new Pane(liveCanvas);
        wfPane.setStyle("-fx-background-color:transparent;");  // KEIN extra Border mehr
        liveCanvas.widthProperty().bind(wfPane.widthProperty());
        liveCanvas.setHeight(78);
        // Canvas-Höhe an Pane binden damit keine Lücke entsteht
        wfPane.setMinHeight(78);
        wfPane.setPrefHeight(78);

        VBox wfBox = new VBox(6, styledLbl("LIVE · WAVEFORM", FG,
                "Calibri,Segoe UI,Arial", "14", "bold"), wfPane);
        wfBox.setPadding(new Insets(10, 12, 10, 12));
        wfBox.setStyle(bg(CARD) + "-fx-border-color:" + BORDER +
                       ";-fx-border-width:1;-fx-border-radius:8;-fx-background-radius:8;");
        root.getChildren().add(pad(wfBox, 0, 0, 8, 0));

        // ── Mic + Out ── HBox mit gleichmäßigem Hgrow (stabiler als GridPane)
        HBox deviceRow = new HBox(12);
        deviceRow.setFillHeight(true);

        micCombo = combo();
        micCombo.setMaxWidth(Double.MAX_VALUE);
        VBox micBox = new VBox(5,
            styledLbl("🎙  MIKROFON · Eingang", FG,
                      "Calibri,Segoe UI,Arial", "14", "bold"),
            micCombo);
        micBox.setFillWidth(true);
        HBox.setHgrow(micBox, Priority.ALWAYS);

        outCombo = combo();
        outCombo.setMaxWidth(Double.MAX_VALUE);
        VBox outBox = new VBox(5,
            buildOutputLabel(),
            outCombo);
        outBox.setFillWidth(true);
        HBox.setHgrow(outBox, Priority.ALWAYS);

        Button refreshBtn = new Button("🔄");
        refreshBtn.setStyle(
            "-fx-background-color:" + CARD + ";-fx-text-fill:" + FG + ";" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:16px;" +
            "-fx-padding:8 14;-fx-cursor:hand;" +
            "-fx-border-color:" + BORDER + ";-fx-border-width:1.5;" +
            "-fx-border-radius:6;-fx-background-radius:6;");
        refreshBtn.setTooltip(styledTooltip("Geräte neu suchen (Bluetooth, USB)"));
        refreshBtn.setOnAction(e -> { populateMics(); populateOutputs(); });
        VBox refBox = new VBox(5,
            // unsichtbares Label mit gleicher Höhe wie die anderen Labels (Bold 14px)
            styledLbl(" ", BG, "Calibri,Segoe UI,Arial", "14", "bold"),
            refreshBtn);
        refBox.setMinWidth(50);
        // KEIN setAlignment - default TOP_LEFT damit Refresh-Button auf gleicher Höhe wie Dropdowns sitzt

        deviceRow.getChildren().addAll(micBox, outBox, refBox);
        root.getChildren().add(pad(deviceRow, 0, 0, 8, 0));

        // ── Headphone-Checkbox + Mic-Mute + Title ──
        HBox optsRow = new HBox(12);
        optsRow.setAlignment(Pos.CENTER_LEFT);

        headphoneCheck = checkBox("🎧  Kopfhörer-Modus  (bevorzugt Kopfhörer/Bluetooth)");
        headphoneCheck.setSelected(false);
        headphoneCheck.selectedProperty().addListener((o, ov, nv) -> {
            populateOutputs();
        });

        // ── Mikrofon-Stummschalter (Toggle-Button) ──
        micMuteBtn = new ToggleButton("🎙  Mikrofon AN");
        micMuteBtn.setStyle(
            "-fx-background-color:" + CARD + ";-fx-text-fill:" + FG + ";" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:13px;-fx-font-weight:bold;" +
            "-fx-padding:9 18;-fx-cursor:hand;" +
            "-fx-border-color:" + BORDER + ";-fx-border-radius:6;-fx-background-radius:6;");
        micMuteBtn.setTooltip(styledTooltip(
            "Klick = Mikrofon stumm schalten\n" +
            "Während STUMM: keine Audio-Aufzeichnung und kein Selbsthören"));
        micMuteBtn.setOnAction(e -> toggleMicMute());

        Region optsSp = new Region();
        HBox.setHgrow(optsSp, Priority.ALWAYS);

        optsRow.getChildren().addAll(headphoneCheck, optsSp, micMuteBtn);
        root.getChildren().add(pad(optsRow, 0, 0, 8, 0));

        // ── Title + Meter ──
        HBox titleRow = new HBox(12);
        VBox titleBox = new VBox(4,
            styledLbl("EPISODE / TITEL", FG,
                      "Calibri,Segoe UI,Arial", "14", "bold"),
            epEntry = textField("z.B. Folge 01 – Intro"));
        epEntry.setMaxWidth(Double.MAX_VALUE);
        titleBox.setFillWidth(true);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        VBox meterBox = new VBox(5,
            styledLbl("EINGANGSPEGEL", FG,
                      "Calibri,Segoe UI,Arial", "14", "bold"));
        meterPane = new Pane();
        meterPane.setPrefHeight(22);
        meterPane.setMaxWidth(Double.MAX_VALUE);
        meterPane.setStyle(bg(BORDER) + "-fx-border-radius:4;-fx-background-radius:4;");
        javafx.scene.shape.Rectangle meterBg =
            new javafx.scene.shape.Rectangle(0, 22);
        meterBg.widthProperty().bind(meterPane.widthProperty());
        meterBg.setFill(Color.web(BORDER));
        meterBg.setArcWidth(4); meterBg.setArcHeight(4);
        meterFill = new javafx.scene.shape.Rectangle(0, 0, 0, 22);
        meterFill.setFill(Color.web(GREEN));
        meterFill.setArcWidth(4); meterFill.setArcHeight(4);
        meterPane.getChildren().addAll(meterBg, meterFill);
        meterBox.getChildren().add(meterPane);
        meterBox.setPrefWidth(260);

        titleRow.getChildren().addAll(titleBox, meterBox);
        root.getChildren().add(pad(titleRow, 0, 0, 8, 0));

        // ── Live-Monitor Box (NEU) ──
        root.getChildren().add(buildMonitorBox());

        // ── Backing Track ──
        root.getChildren().add(buildBackingTrackBox());

        // ── Controls ──
        HBox ctrl = new HBox(8);
        ctrl.setAlignment(Pos.CENTER_LEFT);
        ctrl.setPadding(new Insets(4, 0, 8, 0));

        btnRec    = accentBtn("●  AUFNAHME STARTEN");
        btnPause  = controlBtn("⏸  Pause");
        btnStop   = controlBtn("⏹  Stopp");
        btnMarker = controlBtn("📍  Marker setzen  (M)");
        btnPause.setDisable(true);
        btnStop.setDisable(true);
        btnMarker.setDisable(true);
        // Disabled-Style explizit setzen damit die Buttons gut lesbar bleiben
        btnPause.setOpacity(1.0);
        btnStop.setOpacity(1.0);
        btnMarker.setOpacity(1.0);

        btnRec.setOnAction(e -> toggleRecord());
        btnPause.setOnAction(e -> togglePause());
        btnStop.setOnAction(e -> stopRecord());
        btnMarker.setOnAction(e -> addMarker());

        Region ctrlSp = new Region();
        HBox.setHgrow(ctrlSp, Priority.ALWAYS);

        Button folderBtn = ghostBtn("📁  Ordner öffnen");
        folderBtn.setOnAction(e -> openFolder());

        ctrl.getChildren().addAll(btnRec, btnPause, btnStop, btnMarker, ctrlSp, folderBtn);
        root.getChildren().add(ctrl);

        // ── Marker-Liste (NEU) ──
        root.getChildren().add(buildMarkerListBox());

        root.getChildren().add(divider());

        // ── Recordings list ──
        HBox listHead = new HBox(12);
        listHead.setAlignment(Pos.CENTER_LEFT);
        listHead.setPadding(new Insets(0, 0, 6, 0));
        countLbl = styledLbl("0 Dateien", MUTED,
                             "Calibri,Segoe UI,Arial", "12", "normal");
        Region lhSp = new Region();
        HBox.setHgrow(lhSp, Priority.ALWAYS);
        listHead.getChildren().addAll(
            styledLbl("📁  AUFNAHMEN", FG, "Calibri,Segoe UI,Arial", "16", "bold"),
            lhSp, countLbl);
        root.getChildren().add(listHead);

        recListBox = new VBox(6);
        recListBox.setFillWidth(true);
        Label emptyLbl = styledLbl(
            EMPTY_RECORDINGS_MSG,
            MUTED, "Calibri,Segoe UI,Arial", "14", "normal");
        emptyLbl.setPadding(new Insets(40, 20, 40, 20));
        emptyLbl.setStyle(emptyLbl.getStyle() +
            "-fx-text-alignment:center;-fx-alignment:center;" +
            bg(CARD) + "-fx-border-color:" + BORDER +
            ";-fx-border-width:1;-fx-border-radius:8;-fx-background-radius:8;");
        emptyLbl.setMaxWidth(Double.MAX_VALUE);
        emptyLbl.setWrapText(true);                  // ← Newlines korrekt umbrechen
        emptyLbl.setAlignment(Pos.CENTER);
        recListBox.getChildren().add(emptyLbl);

        ScrollPane sp = new ScrollPane(recListBox);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color:" + SURFACE + ";" +
                    "-fx-background:" + SURFACE + ";-fx-border-color:transparent;");
        VBox.setVgrow(sp, Priority.ALWAYS);
        root.getChildren().add(sp);

        return root;
    }

    Label buildOutputLabel() {
        Label l = styledLbl("🔊  WIEDERGABE · Ausgang", FG,
                  "Calibri,Segoe UI,Arial", "14", "bold");
        return l;
    }

    // ── NEU: Live-Monitor Box ──
    Node buildMonitorBox() {
        VBox outer = new VBox(6);
        outer.setStyle(bg(CARD) + "-fx-border-color:" + PURPLE + "55;" +
                       "-fx-border-width:1;-fx-border-radius:8;");
        outer.setPadding(new Insets(10, 12, 10, 12));

        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        monitorCheck = checkBox("🎧  EIGENE STIMME ABHÖREN");
        monitorCheck.setStyle(monitorCheck.getStyle()
            .replace("-fx-text-fill:" + FG, "-fx-text-fill:" + PURPLE));
        monitorCheck.setSelected(false);
        monitorCheck.selectedProperty().addListener((o, ov, nv) -> {
            if (nv) startMonitor();   // Immer starten – auch ohne Aufnahme
            else    stopMonitor();
        });

        Label hint = styledLbl("(Mikrofon live über Lautsprecher/Kopfhörer hören)",
            MUTED, "Calibri,Segoe UI,Arial", "11", "italic");

        titleRow.getChildren().addAll(monitorCheck, hint);
        outer.getChildren().add(titleRow);

        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);

        // Verzögerung
        VBox delayBox = new VBox(2);
        Label delayTitleLbl = styledLbl("VERZÖGERUNG (optional)", FG,
                                        "Calibri,Segoe UI,Arial", "14", "bold");
        HBox delayRow = new HBox(8);
        delayRow.setAlignment(Pos.CENTER_LEFT);
        monitorDelaySlider = new Slider(0, 500, 0);
        monitorDelaySlider.setMajorTickUnit(100);
        monitorDelaySlider.setMinorTickCount(0);
        monitorDelaySlider.setSnapToTicks(false);
        monitorDelaySlider.setShowTickMarks(true);
        monitorDelaySlider.setShowTickLabels(false);
        monitorDelaySlider.setPrefWidth(200);
        monitorDelaySlider.setStyle("-fx-control-inner-background:" + BORDER + ";");
        monitorDelayLbl = styledLbl("0 ms", PURPLE,
                                     "Calibri,Segoe UI,Arial", "13", "bold");
        monitorDelayLbl.setMinWidth(60);
        monitorDelaySlider.valueProperty().addListener((o, ov, nv) -> {
            monitorDelayMs = nv.intValue();
            monitorDelayLbl.setText(monitorDelayMs + " ms");
        });
        delayRow.getChildren().addAll(monitorDelaySlider, monitorDelayLbl);
        delayBox.getChildren().addAll(delayTitleLbl, delayRow);

        // Lautstärke
        VBox volBox = new VBox(2);
        Label volTitleLbl = styledLbl("LAUTSTÄRKE", FG,
                                      "Calibri,Segoe UI,Arial", "14", "bold");
        monitorVolSlider = new Slider(0, 100, 70);
        monitorVolSlider.setPrefWidth(120);
        monitorVolSlider.setStyle("-fx-control-inner-background:" + BORDER + ";");
        monitorVolSlider.valueProperty().addListener((o, ov, nv) ->
            monitorVol = nv.floatValue() / 100f);
        volBox.getChildren().addAll(volTitleLbl, monitorVolSlider);

        // Quick-Buttons
        VBox quickBox = new VBox(2);
        Label qLbl = styledLbl("SCHNELLAUSWAHL", FG,
                                "Calibri,Segoe UI,Arial", "14", "bold");
        HBox quickRow = new HBox(8);
        for (int ms : new int[]{0, 50, 100, 200, 500}) {
            int v = ms;
            Button b = miniBtn(v + " ms");
            b.setOnAction(e -> {
                // Direkt-Update: Slider, Label und Variable gleichzeitig
                // (zuverlässiger als nur Slider.setValue – Listener feuert
                //  nicht, wenn Slider schon exakt diesen Wert hatte)
                monitorDelayMs = v;
                monitorDelayLbl.setText(v + " ms");
                monitorDelaySlider.setValue(v);
                // Visuelles Feedback: kurz hervorheben
                b.setStyle(b.getStyle() + "-fx-background-color:" + PURPLE + ";-fx-text-fill:white;");
                Thread t = new Thread(() -> {
                    try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                    Platform.runLater(() ->
                        b.setStyle("-fx-background-color:" + CARD + ";-fx-text-fill:" + FG + ";" +
                                   "-fx-font-family:'Calibri,Segoe UI,Arial';" +
                                   "-fx-font-size:12px;-fx-padding:6 12;-fx-cursor:hand;" +
                                   "-fx-border-color:" + BORDER + ";-fx-border-radius:4;" +
                                   "-fx-background-radius:4;"));
                });
                t.setDaemon(true);
                t.start();
            });
            quickRow.getChildren().add(b);
        }
        quickBox.getChildren().addAll(qLbl, quickRow);

        row.getChildren().addAll(delayBox, volBox, quickBox);
        outer.getChildren().add(row);

        return pad(outer, 0, 0, 8, 0);
    }

    Node buildBackingTrackBox() {
        VBox outer = new VBox(4);
        outer.setStyle(bg(CARD) + "-fx-border-color:" + BLUE + "44;" +
                       "-fx-border-width:1;-fx-border-radius:8;");
        outer.setPadding(new Insets(8, 12, 8, 12));

        Label title = styledLbl("🎵  BACKING-TRACK  ·  Musik während Aufnahme",
            BLUE, "Calibri,Segoe UI,Arial", "14", "bold");
        outer.getChildren().add(title);

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);

        Button loadBtn = secBtn("📂  Track laden");
        loadBtn.setOnAction(e -> loadBackingTrack());

        btLbl = styledLbl("(kein Track geladen)", FG,
                           "Calibri,Segoe UI,Arial", "13", "normal");

        Button clearBtn = ghostBtn("✕");
        clearBtn.setOnAction(e -> clearBackingTrack());

        // Lautstärke kompakt direkt rechts von btLbl, nicht ganz rechts
        Label volLbl = styledLbl("Lautstärke:", FG,
                                  "Calibri,Segoe UI,Arial", "13", "bold");
        volSlider = new Slider(0, 100, 60);
        volSlider.setPrefWidth(150);
        volSlider.setStyle("-fx-control-inner-background:" + BORDER + ";");
        volSlider.valueProperty().addListener(
            (obs, o, n) -> backingVol = n.floatValue() / 100f);

        Region btSp = new Region();
        HBox.setHgrow(btSp, Priority.ALWAYS);

        row.getChildren().addAll(loadBtn, btLbl, clearBtn,
                                  btSp, volLbl, volSlider);
        outer.getChildren().add(row);
        return pad(outer, 0, 0, 8, 0);
    }

    // ── NEU: Marker-Liste ──
    Node buildMarkerListBox() {
        VBox outer = new VBox(4);
        outer.setStyle(bg(CARD) + "-fx-border-color:" + ACCENT2 + "55;" +
                       "-fx-border-width:1;-fx-border-radius:8;");
        outer.setPadding(new Insets(8, 12, 8, 12));

        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        Label title = styledLbl("📍  KAPITEL / MARKER",
            ACCENT2, "Calibri,Segoe UI,Arial", "14", "bold");
        markerCountLbl = styledLbl("(0)", MUTED,
            "Calibri,Segoe UI,Arial", "11", "normal");
        Region hsp = new Region();
        HBox.setHgrow(hsp, Priority.ALWAYS);

        Button exportMarkersBtn = miniBtn("📋  Exportieren");
        exportMarkersBtn.setOnAction(e -> exportMarkers());

        Button clearMarkersBtn = miniBtn("🗑  Alle löschen");
        clearMarkersBtn.setOnAction(e -> clearMarkers());

        head.getChildren().addAll(title, markerCountLbl, hsp,
                                  exportMarkersBtn, clearMarkersBtn);
        outer.getChildren().add(head);

        markerListBox = new VBox(3);
        markerListBox.setFillWidth(true);
        Label emptyM = styledLbl(
            MARKER_HINT_MSG,
            MUTED, "Calibri,Segoe UI,Arial", "11", "italic");
        emptyM.setPadding(new Insets(6, 0, 6, 0));
        markerListBox.getChildren().add(emptyM);

        ScrollPane sp = new ScrollPane(markerListBox);
        sp.setFitToWidth(true);
        sp.setMaxHeight(80);
        sp.setStyle("-fx-background-color:transparent;-fx-background:transparent;");
        outer.getChildren().add(sp);

        return pad(outer, 0, 0, 8, 0);
    }

    // ═══════════════════════════════════════════
    //  EDITOR TAB (mit Fade + Normalize)
    // ═══════════════════════════════════════════
    Node buildEditorTab() {
        VBox root = new VBox(8);
        root.setStyle(bg(SURFACE));
        root.setPadding(new Insets(16, 24, 16, 24));

        // ── Mute-Hinweis (oben, nur sichtbar wenn Mic stumm geschaltet wurde) ──
        editorMuteHintLbl = styledLbl(
            "🔇  MIKROFON STUMM während der Bearbeitung – kein Feedback / Selbsthören",
            PURPLE, "Calibri,Segoe UI,Arial", "12", "bold");
        editorMuteHintLbl.setStyle(editorMuteHintLbl.getStyle() +
            "-fx-background-color:" + CARD + ";-fx-border-color:" + PURPLE + "88;" +
            "-fx-border-width:1;-fx-border-radius:6;-fx-background-radius:6;" +
            "-fx-padding:8 14;");
        editorMuteHintLbl.setMaxWidth(Double.MAX_VALUE);
        editorMuteHintLbl.setVisible(false);
        editorMuteHintLbl.setManaged(false);
        root.getChildren().add(editorMuteHintLbl);

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);

        Button loadBtn = accentBtn("📂  Aufnahme laden");
        loadBtn.setOnAction(e -> editorLoadDialog());

        editFileLbl = styledLbl("(keine Datei geladen)", MUTED,
                                 "Calibri,Segoe UI,Arial", "13", "normal");
        Region topSp = new Region();
        HBox.setHgrow(topSp, Priority.ALWAYS);
        editInfoLbl = styledLbl("", FG, "Calibri,Segoe UI,Arial", "12", "normal");

        top.getChildren().addAll(loadBtn, editFileLbl, topSp, editInfoLbl);
        root.getChildren().add(top);

        // Editor canvas
        editCanvas = new Canvas(900, 180);
        editCanvas.setCursor(javafx.scene.Cursor.TEXT);
        editCanvas.setOnMousePressed(this::editMousePressed);
        editCanvas.setOnMouseDragged(this::editMouseDragged);
        editCanvas.setOnMouseReleased(this::editMouseReleased);

        Pane ecPane = new Pane(editCanvas);
        ecPane.setStyle(bg(CARD) + "-fx-border-color:" + BORDER +
                        ";-fx-border-width:1;-fx-border-radius:8;");
        editCanvas.widthProperty().bind(ecPane.widthProperty());
        editCanvas.setHeight(180);

        HBox wfInfo = new HBox();
        wfInfo.setAlignment(Pos.CENTER_LEFT);
        wfInfo.setPadding(new Insets(4, 8, 4, 8));
        selInfoLbl = styledLbl("", BLUE, "Calibri,Segoe UI,Arial", "12", "bold");
        Region wfSp = new Region(); HBox.setHgrow(wfSp, Priority.ALWAYS);
        wfInfo.getChildren().addAll(
            styledLbl("WAVEFORM  ·  Klicken & ziehen zum Markieren",
                      FG, "Calibri,Segoe UI,Arial", "12", "bold"),
            wfSp, selInfoLbl);

        HBox timeRow = new HBox();
        timeRow.setAlignment(Pos.CENTER_LEFT);
        timeRow.setPadding(new Insets(2, 8, 6, 8));
        timeCursorLbl = styledLbl("", BLUE, "Calibri,Segoe UI,Arial", "12", "bold");
        Region tSp = new Region(); HBox.setHgrow(tSp, Priority.ALWAYS);
        timeEndLbl = styledLbl("00:00.000", FG,
                                "Calibri,Segoe UI,Arial", "12", "bold");
        timeRow.getChildren().addAll(timeCursorLbl, tSp, timeEndLbl);

        VBox wfBox = new VBox(4, wfInfo, ecPane, timeRow);
        wfBox.setStyle(bg(CARD) + "-fx-border-color:" + BORDER +
                       ";-fx-border-width:1;-fx-border-radius:8;");
        root.getChildren().add(wfBox);

        // Edit buttons
        HBox ebar = new HBox(8);
        ebar.setAlignment(Pos.CENTER_LEFT);

        btnPlayEdit = secBtn("▶  Play");
        btnPlayEdit.setOnAction(e -> editorTogglePlay());

        Button toStart = secBtn("⏮");
        toStart.setOnAction(e -> { cursorPos = 0; drawEditor(); });
        Button toEnd = secBtn("⏭");
        toEnd.setOnAction(e -> {
            if (editAudio != null) { cursorPos = editAudio.length - 1; drawEditor(); }
        });

        Button cutBtn   = secBtn("✂  Cut");
        Button copyBtn  = secBtn("📋  Copy");
        Button pasteBtn = secBtn("📌  Paste");
        Button delBtn   = secBtn("🗑  Del");
        Button selAllBtn = secBtn("☰  All");
        Button clrSelBtn = secBtn("✕");

        cutBtn.setOnAction(e -> editCut());
        copyBtn.setOnAction(e -> editCopy());
        pasteBtn.setOnAction(e -> editPaste());
        delBtn.setOnAction(e -> editDelete());
        selAllBtn.setOnAction(e -> { selStart = 0;
            if (editAudio != null) selEnd = editAudio.length; drawEditor(); });
        clrSelBtn.setOnAction(e -> { selStart = -1; selEnd = -1; drawEditor(); });

        Label sep1 = styledLbl(" │ ", BORDER, "Arial", "16", "normal");
        Label sep2 = styledLbl(" │ ", BORDER, "Arial", "16", "normal");
        ebar.getChildren().addAll(
            btnPlayEdit, toStart, toEnd, sep1,
            cutBtn, copyBtn, pasteBtn, delBtn, sep2,
            selAllBtn, clrSelBtn);
        root.getChildren().add(ebar);

        // ── NEU: Fade + Normalisierung ──
        HBox fadeBar = new HBox(8);
        fadeBar.setAlignment(Pos.CENTER_LEFT);
        fadeBar.setPadding(new Insets(4, 0, 4, 0));

        VBox fadeBox = new VBox(4);
        Label fLbl = styledLbl("FADE / LAUTSTÄRKE", FG,
                                "Calibri,Segoe UI,Arial", "13", "bold");
        HBox fadeBtns = new HBox(8);

        fadeDurCombo = combo();
        fadeDurCombo.getItems().addAll("0.5s", "1.0s", "2.0s", "3.0s", "5.0s");
        fadeDurCombo.getSelectionModel().select(1);
        fadeDurCombo.setMaxWidth(70);

        Button fadeInBtn = greenBtn("📈  Fade-In");
        Button fadeOutBtn = greenBtn("📉  Fade-Out");
        Button normalizeBtn = blueBtn("🔊  Normalisieren");

        fadeInBtn.setOnAction(e -> applyFade(true));
        fadeOutBtn.setOnAction(e -> applyFade(false));
        normalizeBtn.setOnAction(e -> applyNormalize());

        fadeBtns.getChildren().addAll(
            styledLbl("Dauer:", FG, "Calibri,Segoe UI,Arial", "12", "normal"),
            fadeDurCombo, fadeInBtn, fadeOutBtn,
            styledLbl(" │ ", BORDER, "Arial", "16", "normal"),
            normalizeBtn);
        fadeBox.getChildren().addAll(fLbl, fadeBtns);

        fadeBar.getChildren().add(fadeBox);
        root.getChildren().add(fadeBar);

        // Speed + Export
        HBox sbar = new HBox(8);
        sbar.setAlignment(Pos.CENTER_LEFT);

        VBox speedBox = new VBox(4);
        speedBox.getChildren().add(styledLbl("GESCHWINDIGKEIT  (FFmpeg)",
                FG, "Calibri,Segoe UI,Arial", "13", "bold"));
        HBox speedBtns = new HBox(6);
        for (double[] spv : new double[][]{{0.5},{0.75},{1.0},{1.25},{1.5},{2.0}}) {
            double factor = spv[0];
            Button b = secBtn(factor + "×");
            b.setOnAction(e -> applySpeed(factor));
            speedBtns.getChildren().add(b);
        }
        speedBox.getChildren().add(speedBtns);
        HBox.setHgrow(speedBox, Priority.ALWAYS);

        VBox exportBox = new VBox(4);
        exportBox.setAlignment(Pos.CENTER_RIGHT);
        exportBox.getChildren().add(styledLbl("EXPORT  &  TRANSKRIPT", FG,
                "Calibri,Segoe UI,Arial", "13", "bold"));
        HBox exportBtns = new HBox(6);
        Button undoBtn   = ghostBtn("↶  Undo");
        Button savBtn    = greenBtn("💾  WAV");
        Button mp3Btn    = blueBtn("🎵  MP3");
        Button transBtn  = new Button("📝  Transkribieren");
        transBtn.setStyle(
            "-fx-background-color:" + PURPLE + ";-fx-text-fill:white;" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:12px;" +
            "-fx-font-weight:bold;-fx-padding:10 16;-fx-cursor:hand;" +
            "-fx-background-radius:6;");
        transBtn.setTooltip(styledTooltip(
            "Sprache zu Text mit Whisper (offline, kostenlos)\n" +
            "Erstellt ein Transkript mit Zeitstempeln."));
        undoBtn.setOnAction(e -> editorUndo());
        savBtn.setOnAction(e -> editorSaveWav());
        mp3Btn.setOnAction(e -> editorExportMp3());
        transBtn.setOnAction(e -> editorTranscribe());
        exportBtns.getChildren().addAll(undoBtn, savBtn, mp3Btn, transBtn);
        exportBox.getChildren().add(exportBtns);

        sbar.getChildren().addAll(speedBox, exportBox);
        root.getChildren().add(sbar);

        Label hint = styledLbl(
            "💡  Klick + Ziehen = Bereich markieren  ·  " +
            "Strg+Z = Rückgängig  ·  Leertaste = Play  ·  " +
            "Fade ohne Auswahl = ganzer Anfang/Ende",
            MUTED, "Calibri,Segoe UI,Arial", "12", "normal");
        hint.setWrapText(true);
        root.getChildren().add(hint);

        return root;
    }

    // ═══════════════════════════════════════════
    //  DEVICES (mit Bluetooth)
    // ═══════════════════════════════════════════
    void populateMics() {
        inputMixers.clear();
        List<String> names = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer m = AudioSystem.getMixer(info);
            try {
                Line.Info li = new DataLine.Info(TargetDataLine.class, FORMAT);
                if (m.isLineSupported(li)) {
                    String n = info.getName();
                    String low = n.toLowerCase();
                    if (low.contains("primary") || low.contains("mapper")) continue;
                    inputMixers.add(info);
                    names.add(decorateDeviceName(n));
                }
            } catch (Exception ignored) {}
        }
        if (names.isEmpty()) names.add("Kein Mikrofon gefunden");
        String prev = micCombo.getValue();
        micCombo.getItems().setAll(names);
        if (prev != null && names.contains(prev)) micCombo.setValue(prev);
        else micCombo.getSelectionModel().selectFirst();
    }

    void populateOutputs() {
        outputMixers.clear();
        List<String> names = new ArrayList<>();
        List<Mixer.Info> headphones = new ArrayList<>();
        List<String> hpNames = new ArrayList<>();
        List<Mixer.Info> speakers = new ArrayList<>();
        List<String> spNames = new ArrayList<>();

        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer m = AudioSystem.getMixer(info);
            try {
                Line.Info li = new DataLine.Info(SourceDataLine.class, FORMAT);
                if (m.isLineSupported(li)) {
                    String n = info.getName();
                    String low = n.toLowerCase();
                    if (low.contains("primary") || low.contains("mapper")) continue;

                    String decorated = decorateDeviceName(n);
                    if (isHeadphoneOrBluetooth(low)) {
                        headphones.add(info);
                        hpNames.add(decorated);
                    } else {
                        speakers.add(info);
                        spNames.add(decorated);
                    }
                }
            } catch (Exception ignored) {}
        }

        // Reihenfolge: bei Kopfhörer-Modus zuerst Headphones, sonst Speakers
        if (headphoneCheck != null && headphoneCheck.isSelected()) {
            outputMixers.addAll(headphones);
            names.addAll(hpNames);
            outputMixers.addAll(speakers);
            names.addAll(spNames);
        } else {
            outputMixers.addAll(speakers);
            names.addAll(spNames);
            outputMixers.addAll(headphones);
            names.addAll(hpNames);
        }

        if (names.isEmpty()) names.add("Kein Ausgabegerät gefunden");
        String prev = outCombo.getValue();
        outCombo.getItems().setAll(names);
        if (prev != null && names.contains(prev)) outCombo.setValue(prev);
        else outCombo.getSelectionModel().selectFirst();
    }

    boolean isHeadphoneOrBluetooth(String low) {
        return low.contains("headphone") || low.contains("kopfhörer") ||
               low.contains("bluetooth") || low.contains("bt ") ||
               low.contains("airpod") || low.contains("buds") ||
               low.contains("headset") || low.contains("a2dp") ||
               low.contains("hands-free");
    }

    String decorateDeviceName(String name) {
        String low = name.toLowerCase();
        if (low.contains("bluetooth") || low.contains("a2dp") ||
            low.contains("airpod") || low.contains("buds")) return "🔵  " + name;
        if (low.contains("headphone") || low.contains("kopfhörer") ||
            low.contains("headset")) return "🎧  " + name;
        if (low.contains("usb")) return "🔌  " + name;
        return "🔊  " + name;
    }

    Mixer.Info selectedInput() {
        int i = micCombo.getSelectionModel().getSelectedIndex();
        return (i >= 0 && i < inputMixers.size()) ? inputMixers.get(i) : null;
    }

    Mixer.Info selectedOutput() {
        int i = outCombo.getSelectionModel().getSelectedIndex();
        return (i >= 0 && i < outputMixers.size()) ? outputMixers.get(i) : null;
    }

    // ═══════════════════════════════════════════
    //  BACKING TRACK
    // ═══════════════════════════════════════════
    void loadBackingTrack() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Backing-Track auswählen");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            "Audio", "*.wav", "*.mp3", "*.m4a", "*.flac", "*.ogg"));
        File f = fc.showOpenDialog(null);
        if (f == null) return;

        Thread bt = new Thread(() -> {
            try {
                float[] data;
                String ext = f.getName().toLowerCase();
                if ((ext.endsWith(".mp3") || ext.endsWith(".m4a") ||
                     ext.endsWith(".ogg")) && ffmpeg != null) {
                    File tmp = File.createTempFile("ps_bt", ".wav");
                    runFFmpeg("-y", "-i", f.getAbsolutePath(),
                              "-ar", "48000", "-ac", "1", tmp.getAbsolutePath());
                    data = readWavToFloat(tmp);
                    tmp.delete();
                } else {
                    data = readWavToFloat(f);
                }
                backingData = data;
                double dur = data.length / SR;
                int m = (int)(dur / 60), s = (int)(dur % 60);
                String name = f.getName().length() > 35
                    ? f.getName().substring(0, 32) + "..." : f.getName();
                Platform.runLater(() ->
                    btLbl.setText("🎵  " + name + "  (" + m + ":" +
                                  String.format("%02d", s) + ")"));
            } catch (Exception ex) {
                Platform.runLater(() ->
                    alert(Alert.AlertType.ERROR, "Fehler",
                          "Track konnte nicht geladen werden:\n" + ex.getMessage()));
            }
        }, "backing-load");
        bt.setDaemon(true);
        bt.start();
    }

    void clearBackingTrack() {
        backingData = null;
        btLbl.setText("(kein Track geladen)");
    }

    void startBackingPlayback() {
        if (backingData == null) return;
        Mixer.Info outInfo = selectedOutput();
        backingPlaying.set(true);
        backingThread = new Thread(() -> {
            try {
                SourceDataLine line;
                if (outInfo != null) {
                    Mixer mixer = AudioSystem.getMixer(outInfo);
                    DataLine.Info dli = new DataLine.Info(SourceDataLine.class, FORMAT);
                    line = (SourceDataLine) mixer.getLine(dli);
                } else {
                    line = AudioSystem.getSourceDataLine(FORMAT);
                }
                line.open(FORMAT, 4096);
                line.start();
                backingLine = line;

                int pos = 0;
                byte[] buf = new byte[4096];
                while (backingPlaying.get() && pos < backingData.length) {
                    if (!paused) {
                        int frames = buf.length / 2;
                        int avail = Math.min(frames, backingData.length - pos);
                        for (int i = 0; i < avail; i++) {
                            short s = (short)(backingData[pos + i] * backingVol * 32767f);
                            buf[i * 2]     = (byte)(s & 0xFF);
                            buf[i * 2 + 1] = (byte)((s >> 8) & 0xFF);
                        }
                        line.write(buf, 0, avail * 2);
                        pos += avail;
                    } else {
                        Thread.sleep(20);
                    }
                }
                line.drain();
                line.close();
            } catch (Exception ex) {
                Platform.runLater(() ->
                    alert(Alert.AlertType.WARNING, "Backing-Track",
                          "Wiedergabe-Fehler:\n" + ex.getMessage()));
            }
            backingPlaying.set(false);
        }, "backing-play");
        backingThread.setDaemon(true);
        backingThread.start();
    }

    void stopBackingPlayback() {
        backingPlaying.set(false);
        if (backingLine != null) {
            try { backingLine.stop(); backingLine.close(); } catch (Exception ignored) {}
            backingLine = null;
        }
        if (backingThread != null) {
            backingThread.interrupt();
            backingThread = null;
        }
    }

    // ═══════════════════════════════════════════
    //  MIKROFON-STUMMSCHALTUNG
    // ═══════════════════════════════════════════

    /**
     * Mikrofon manuell stumm-/lautschalten.
     * Wirkt auf BEIDES: Aufnahme (zeichnet Stille auf) UND Monitor (kein Selbsthören).
     */
    void toggleMicMute() {
        micMuted = !micMuted;
        updateMicMuteUI();
    }

    /** Aktualisiert Button-Text, Farbe und Pegel-Anzeige je nach Mute-Zustand. */
    void updateMicMuteUI() {
        if (micMuteBtn == null) return;
        if (micMuted) {
            micMuteBtn.setText("🔇  MIKROFON STUMM");
            micMuteBtn.setStyle(
                "-fx-background-color:" + ACCENT + ";-fx-text-fill:white;" +
                "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:13px;-fx-font-weight:bold;" +
                "-fx-padding:9 18;-fx-cursor:hand;" +
                "-fx-border-color:" + ACCENT + ";-fx-border-radius:6;-fx-background-radius:6;");
            // Pegel-Meter sofort auf 0 setzen
            level = 0f;
            if (meterFill != null) meterFill.setWidth(0);
        } else {
            micMuteBtn.setText("🎙  Mikrofon AN");
            micMuteBtn.setStyle(
                "-fx-background-color:" + CARD + ";-fx-text-fill:" + FG + ";" +
                "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:13px;-fx-font-weight:bold;" +
                "-fx-padding:9 18;-fx-cursor:hand;" +
                "-fx-border-color:" + BORDER + ";-fx-border-radius:6;-fx-background-radius:6;");
        }
    }

    // ═══════════════════════════════════════════
    //  LIVE-MONITORING  (unabhängig von Aufnahme)
    // ═══════════════════════════════════════════

    /** Wandelt Millisekunden in Bytes um (mono 16-bit PCM, 48 kHz). */
    static int msToBytes(int ms) {
        // 48000 Samples/s × 2 Bytes/Sample = 96 Bytes/ms
        int bytes = (int)((long) ms * (long) SR * 2L / 1000L);
        return bytes & ~1; // gerade Zahl (16-bit Samples)
    }

    /**
     * Schreibt {@code ms} Millisekunden Stille in den Lautsprecher-Puffer.
     * Dadurch wird die danach folgende Audio um genau diese Zeit verschoben –
     * blockierend, da {@code line.write()} pro Sample auf Echtzeit-Wiedergabe wartet.
     */
    void writeSilenceMs(SourceDataLine line, int ms) {
        if (ms <= 0) return;
        int remaining = msToBytes(ms);
        byte[] silence = new byte[Math.min(remaining, 4096)];
        while (remaining > 0 && monitorActive.get()) {
            int chunk = Math.min(remaining, silence.length);
            line.write(silence, 0, chunk);
            remaining -= chunk;
        }
    }


    /**
     * Startet den Monitor-Ausgang (Lautsprecher-Thread).
     * Liest Daten aus monitorQueue – befüllt entweder vom
     * Aufnahme-Thread ODER vom eigenen Mic-Reader-Thread.
     */
    void startMonitor() {
        if (monitorActive.get()) return;
        if (monitorSuppressedByEditor) return; // Editor-Tab aktiv → Mic stumm
        monitorActive.set(true);
        monitorQueue.clear();

        Mixer.Info outInfo = selectedOutput();

        // ── Ausgangs-Thread (Lautsprecher) ──
        monitorThread = new Thread(() -> {
            try {
                SourceDataLine spk;
                if (outInfo != null) {
                    Mixer mx = AudioSystem.getMixer(outInfo);
                    DataLine.Info dli = new DataLine.Info(SourceDataLine.class, FORMAT);
                    spk = (SourceDataLine) mx.getLine(dli);
                } else {
                    spk = AudioSystem.getSourceDataLine(FORMAT);
                }
                spk.open(FORMAT, 4096);
                spk.start();
                monitorLine = spk;

                // ── Initiale Verzögerung: Stille in den Lautsprecher-Puffer schreiben ──
                // Dadurch wird die gesamte nachfolgende Audio um genau diese Zeit
                // versetzt – ohne Sleeps zwischen den Chunks (=ohne Stottern).
                int appliedDelayMs = 0;
                if (monitorDelayMs > 0) {
                    writeSilenceMs(spk, monitorDelayMs);
                    appliedDelayMs = monitorDelayMs;
                }

                while (monitorActive.get()) {
                    byte[] data = monitorQueue.poll(
                        100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (data == null) continue;

                    // Delay-Slider wurde verändert?
                    int targetDelay = monitorDelayMs;
                    if (targetDelay > appliedDelayMs) {
                        // Mehr Delay → zusätzliche Stille einschieben
                        writeSilenceMs(spk, targetDelay - appliedDelayMs);
                        appliedDelayMs = targetDelay;
                    } else if (targetDelay < appliedDelayMs) {
                        // Weniger Delay → Audio aus Queue verwerfen
                        int dropBytes = msToBytes(appliedDelayMs - targetDelay);
                        while (dropBytes > 0 && !monitorQueue.isEmpty()) {
                            byte[] dropChunk = monitorQueue.poll();
                            if (dropChunk != null) dropBytes -= dropChunk.length;
                        }
                        // Falls noch Rest übrig: aus aktuellem Chunk wegschneiden
                        if (dropBytes > 0 && data.length > dropBytes) {
                            data = Arrays.copyOfRange(data, dropBytes & ~1, data.length);
                        } else if (dropBytes > 0) {
                            // Aktueller Chunk wird komplett verworfen
                            appliedDelayMs = targetDelay;
                            continue;
                        }
                        appliedDelayMs = targetDelay;
                    }

                    // Lautstärke anwenden + Clipping verhindern
                    byte[] adj = new byte[data.length];
                    for (int i = 0; i < data.length - 1; i += 2) {
                        short s = (short)((data[i + 1] << 8) | (data[i] & 0xFF));
                        int v = (int)(s * monitorVol);
                        s = (short) Math.max(-32768, Math.min(32767, v));
                        adj[i]     = (byte)(s & 0xFF);
                        adj[i + 1] = (byte)((s >> 8) & 0xFF);
                    }
                    spk.write(adj, 0, adj.length);
                }
                spk.drain();
                spk.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            monitorActive.set(false);
        }, "monitor-out");
        monitorThread.setDaemon(true);
        monitorThread.start();

        // ── Mic-Reader nur starten wenn NICHT aufgenommen wird ──
        // Während der Aufnahme befüllt der Record-Thread die Queue.
        if (!recording) {
            startMonitorMicReader();
        }
    }

    /**
     * Öffnet eine eigene Mic-Linie und schickt die Daten in die
     * monitorQueue – aktiv solange monitorActive && !recording.
     * Beim Start einer Aufnahme wird dieser Thread automatisch beendet
     * (der Record-Thread übernimmt das Queue-Befüllen).
     */
    void startMonitorMicReader() {
        Mixer.Info inInfo = selectedInput();

        monitorMicThread = new Thread(() -> {
            try {
                TargetDataLine mic;
                if (inInfo != null) {
                    Mixer mx = AudioSystem.getMixer(inInfo);
                    DataLine.Info dli = new DataLine.Info(TargetDataLine.class, FORMAT);
                    mic = (TargetDataLine) mx.getLine(dli);
                } else {
                    mic = AudioSystem.getTargetDataLine(FORMAT);
                }
                mic.open(FORMAT, 4096);
                mic.start();
                monitorMicLine = mic;

                byte[] buf = new byte[2048];
                while (monitorActive.get() && !recording) {
                    int read = mic.read(buf, 0, buf.length);
                    if (read < 0) break;     // Mic-Linie wurde geschlossen → Loop verlassen (verhindert 100%-CPU-Spin)
                    if (read == 0) {         // Sicherheitsnetz
                        try { Thread.sleep(5); } catch (InterruptedException ie) { break; }
                        continue;
                    }
                    // Wenn Mic stumm: Buffer auf 0 setzen → kein Audio durch
                    if (micMuted) Arrays.fill(buf, 0, read, (byte) 0);
                    byte[] copy = Arrays.copyOf(buf, read);
                    monitorQueue.offer(copy);
                    updateLevel(copy, read); // Pegel-Meter auch ohne Aufnahme
                }
                try { mic.stop(); mic.close(); } catch (Exception ignored) {}
                monitorMicLine = null;
            } catch (Exception ex) {
                // Mic konnte nicht geöffnet werden – Queue bleibt leer
                Platform.runLater(() -> showHotplugHint(
                    "⚠  Monitor: Mikrofon konnte nicht geöffnet werden"));
            }
        }, "monitor-mic");
        monitorMicThread.setDaemon(true);
        monitorMicThread.start();
    }

    void stopMonitor() {
        monitorActive.set(false);
        // Mic-Reader beenden
        if (monitorMicLine != null) {
            try { monitorMicLine.stop(); monitorMicLine.close(); } catch (Exception ignored) {}
            monitorMicLine = null;
        }
        if (monitorMicThread != null) { monitorMicThread.interrupt(); monitorMicThread = null; }
        // Ausgangs-Thread beenden
        if (monitorLine != null) {
            try { monitorLine.stop(); monitorLine.close(); } catch (Exception ignored) {}
            monitorLine = null;
        }
        if (monitorThread != null) { monitorThread.interrupt(); monitorThread = null; }
        monitorQueue.clear();
    }

    // ═══════════════════════════════════════════
    //  RECORDING
    // ═══════════════════════════════════════════
    void toggleRecord() {
        if (recording) stopRecord();
        else startRecord();
    }

    void startRecord() {
        Mixer.Info inInfo = selectedInput();
        if (inInfo == null) {
            alert(Alert.AlertType.ERROR, "Fehler", "Kein Mikrofon ausgewählt.");
            return;
        }

        // Monitor-Mic-Reader schließen, damit Aufnahme dieselbe Mic-Linie öffnen kann
        if (monitorMicLine != null) {
            try { monitorMicLine.stop(); monitorMicLine.close(); } catch (Exception ignored) {}
            monitorMicLine = null;
        }
        if (monitorMicThread != null) { monitorMicThread.interrupt(); monitorMicThread = null; }

        try {
            Mixer mixer = AudioSystem.getMixer(inInfo);
            DataLine.Info dli = new DataLine.Info(TargetDataLine.class, FORMAT);
            inputLine = (TargetDataLine) mixer.getLine(dli);
            inputLine.open(FORMAT, 8192);
            inputLine.start();
        } catch (Exception ex) {
            try {
                inputLine = AudioSystem.getTargetDataLine(FORMAT);
                inputLine.open(FORMAT, 8192);
                inputLine.start();
            } catch (Exception ex2) {
                alert(Alert.AlertType.ERROR, "Fehler",
                      "Aufnahme konnte nicht gestartet werden:\n" + ex2.getMessage());
                return;
            }
        }

        recording     = true;
        paused        = false;
        recordBuf.reset();
        elapsed       = 0;
        startTime     = Instant.now();
        pauseStart    = null;
        totalPausedMs = 0;
        markers.clear();
        Platform.runLater(this::renderMarkers);

        recordThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            while (recording) {
                int read = inputLine.read(buf, 0, buf.length);
                if (read > 0 && !paused) {
                    // Wenn Mic stumm: Buffer komplett auf 0 setzen (Stille)
                    if (micMuted) Arrays.fill(buf, 0, read, (byte) 0);

                    synchronized (recordBuf) { recordBuf.write(buf, 0, read); }
                    updateLevel(buf, read);

                    // Live-Monitor: Daten in Queue
                    if (monitorActive.get()) {
                        byte[] copy = new byte[read];
                        System.arraycopy(buf, 0, copy, 0, read);
                        monitorQueue.offer(copy);
                    }
                }
                if (paused) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
            }
        }, "rec-thread");
        recordThread.setDaemon(true);
        recordThread.start();

        startBackingPlayback();
        setUIState("recording");
    }

    long lastLevelUpdate = 0;

    void updateLevel(byte[] buf, int len) {
        long sum = 0;
        for (int i = 0; i < len - 1; i += 2) {
            short s = (short)((buf[i + 1] << 8) | (buf[i] & 0xFF));
            sum += (long) s * s;
        }
        float rms = (float) Math.sqrt((double) sum / (len / 2)) / 32768f;
        level = Math.min(1f, rms * 4f);
        float peak = 0f;
        for (int i = 0; i < len - 1; i += 2) {
            short s = (short)((buf[i + 1] << 8) | (buf[i] & 0xFF));
            float v = Math.abs(s / 32768f);
            if (v > peak) peak = v;
        }

        // Waveform-Buffer direkt aktualisieren (kein Platform.runLater nötig - nur Daten!)
        if (waveBuf.length > 0) {
            System.arraycopy(waveBuf, 1, waveBuf, 0, waveBuf.length - 1);
            waveBuf[waveBuf.length - 1] = peak;
        }
    }

    void togglePause() {
        if (!recording) return;
        paused = !paused;
        if (paused) {
            pauseStart = Instant.now();
        } else if (pauseStart != null) {
            totalPausedMs += java.time.Duration.between(pauseStart, Instant.now()).toMillis();
            pauseStart = null;
        }
        setUIState(paused ? "paused" : "recording");
    }

    void stopRecord() {
        recording = false;
        paused    = false;
        if (inputLine != null) {
            inputLine.stop();
            inputLine.close();
            inputLine = null;
        }
        stopBackingPlayback();
        // Monitor NICHT stoppen – nur den Ausgangs-Thread weiterlaufen lassen.
        // Stattdessen: Mic-Reader neu starten, damit man sich nach der
        // Aufnahme wieder über Lautsprecher hören kann.
        if (monitorThread != null) {
            // Mic-Reader neu starten (Aufnahme ist jetzt fertig, Mic frei)
            if (monitorCheck != null && monitorCheck.isSelected()) {
                Thread t = new Thread(() -> {
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                    if (monitorActive.get() && !recording) startMonitorMicReader();
                }, "monitor-mic-restart");
                t.setDaemon(true);
                t.start();
            }
        }
        if (recordThread != null) {
            try { recordThread.join(1000); } catch (InterruptedException ignored) {}
            recordThread = null;
        }
        saveRecording();
        setUIState("idle");
    }

    void saveRecording() {
        byte[] data;
        synchronized (recordBuf) { data = recordBuf.toByteArray(); }
        if (data.length == 0) return;

        recCount++;
        String title = epEntry.getText().isBlank()
            ? "Aufnahme " + recCount : epEntry.getText().trim();
        String safe  = title.replaceAll("[^\\w\\säöüÄÖÜß-]", "");
        String ts    = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path path = outputDir.resolve(ts + "_" + safe + ".wav");

        try {
            AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(data), FORMAT, data.length / 2);
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, path.toFile());
        } catch (Exception ex) {
            Platform.runLater(() ->
                alert(Alert.AlertType.ERROR, "Fehler",
                      "Aufnahme konnte nicht gespeichert werden:\n" + ex.getMessage()));
            return;
        }

        // Marker als .txt-Datei zum Audio
        if (!markers.isEmpty()) {
            try {
                Path mPath = path.resolveSibling(
                    path.getFileName().toString().replace(".wav", "_marker.txt"));
                StringBuilder sb = new StringBuilder();
                sb.append("Kapitel / Marker  ·  ").append(title).append("\n");
                sb.append("=".repeat(50)).append("\n\n");
                for (Marker m : markers) {
                    sb.append(toTime(m.timeSec())).append("  -  ")
                      .append(m.label()).append("\n");
                }
                Files.writeString(mPath, sb.toString());
            } catch (Exception ignored) {}
        }

        double dur  = (data.length / 2.0) / SR;
        long   size = 0;
        try { size = Files.size(path) / 1024; } catch (Exception ignored) {}
        Rec rec = new Rec(path, title, dur, size, recCount);
        recordings.add(0, rec);
        Platform.runLater(this::renderRecordings);
    }

    // ═══════════════════════════════════════════
    //  MARKER (NEU)
    // ═══════════════════════════════════════════
    void addMarker() {
        if (!recording) return;
        double t = elapsed;
        if (startTime != null) {
            t = java.time.Duration.between(startTime, Instant.now()).toMillis() / 1000.0;
        }
        TextInputDialog d = new TextInputDialog("Kapitel " + (markers.size() + 1));
        d.setTitle("Marker setzen");
        d.setHeaderText("Marker bei " + toTime(t));
        d.setContentText("Bezeichnung:");
        Optional<String> r = d.showAndWait();
        if (r.isPresent()) {
            markers.add(new Marker(t, r.get()));
            renderMarkers();
        }
    }

    void renderMarkers() {
        markerListBox.getChildren().clear();
        markerCountLbl.setText("(" + markers.size() + ")");
        if (markers.isEmpty()) {
            Label e = styledLbl(
                MARKER_HINT_MSG,
                MUTED, "Calibri,Segoe UI,Arial", "11", "italic");
            e.setPadding(new Insets(6, 0, 6, 0));
            markerListBox.getChildren().add(e);
            return;
        }
        int i = 0;
        for (Marker m : markers) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 8, 4, 8));
            row.setStyle(bg(SURFACE) + "-fx-background-radius:4;");

            Label idx = styledLbl(String.format("#%02d", i + 1), MUTED,
                                   "Calibri,Segoe UI,Arial", "11", "normal");
            idx.setMinWidth(32);
            Label time = styledLbl(toTime(m.timeSec()), ACCENT2,
                                    "Calibri,Segoe UI,Arial", "12", "bold");
            time.setMinWidth(90);
            Label lbl = styledLbl(m.label(), FG, "Calibri,Segoe UI,Arial", "12", "normal");
            HBox.setHgrow(lbl, Priority.ALWAYS);
            lbl.setMaxWidth(Double.MAX_VALUE);

            Marker mFinal = m;
            Button delBtn = new Button("✕");
            String delNormal = "-fx-background-color:transparent;-fx-text-fill:" +
                MUTED + ";-fx-cursor:hand;-fx-font-size:15px;-fx-font-weight:bold;-fx-padding:2 8;";
            String delHover  = "-fx-background-color:transparent;-fx-text-fill:" +
                ACCENT + ";-fx-cursor:hand;-fx-font-size:15px;-fx-font-weight:bold;-fx-padding:2 8;";
            delBtn.setStyle(delNormal);
            delBtn.setOnMouseEntered(e -> delBtn.setStyle(delHover));
            delBtn.setOnMouseExited(e  -> delBtn.setStyle(delNormal));
            delBtn.setOnAction(e -> {
                markers.remove(mFinal);
                renderMarkers();
            });

            row.getChildren().addAll(idx, time, lbl, delBtn);
            markerListBox.getChildren().add(row);
            i++;
        }
    }

    void clearMarkers() {
        if (markers.isEmpty()) return;
        if (!confirm("Alle Marker löschen?",
                     "Wirklich alle " + markers.size() + " Marker löschen?")) return;
        markers.clear();
        renderMarkers();
    }

    void exportMarkers() {
        if (markers.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "Keine Marker",
                  "Es gibt keine Marker zum Exportieren.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Marker exportieren");
        fc.setInitialDirectory(outputDir.toFile());
        fc.setInitialFileName("kapitel.txt");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text", "*.txt"));
        File f = fc.showSaveDialog(null);
        if (f == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Kapitel / Marker\n");
            sb.append("=".repeat(50)).append("\n\n");
            for (Marker m : markers) {
                sb.append(toTime(m.timeSec())).append("  -  ")
                  .append(m.label()).append("\n");
            }
            Files.writeString(f.toPath(), sb.toString());
            alert(Alert.AlertType.INFORMATION, "Exportiert",
                  "Marker exportiert nach:\n" + f.getAbsolutePath());
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Fehler",
                  "Export fehlgeschlagen:\n" + ex.getMessage());
        }
    }

    // ═══════════════════════════════════════════
    //  RECORDINGS LIST
    // ═══════════════════════════════════════════
    void renderRecordings() {
        recListBox.getChildren().clear();
        int n = recordings.size();
        countLbl.setText(n + " Datei" + (n != 1 ? "en" : ""));
        if (n == 0) {
            Label e = styledLbl(
                EMPTY_RECORDINGS_MSG,
                MUTED, "Calibri,Segoe UI,Arial", "14", "normal");
            e.setPadding(new Insets(40, 20, 40, 20));
            e.setStyle(e.getStyle() +
                "-fx-text-alignment:center;-fx-alignment:center;" +
                bg(CARD) + "-fx-border-color:" + BORDER +
                ";-fx-border-width:1;-fx-border-radius:8;-fx-background-radius:8;");
            e.setMaxWidth(Double.MAX_VALUE);
            e.setWrapText(true);
            e.setAlignment(Pos.CENTER);
            recListBox.getChildren().add(e);
            return;
        }
        for (Rec r : recordings) {
            HBox item = new HBox(8);
            item.setAlignment(Pos.CENTER_LEFT);
            item.setPadding(new Insets(8, 12, 8, 12));
            item.setStyle(bg(CARD) + "-fx-border-color:" + BORDER +
                          ";-fx-border-width:1;-fx-border-radius:8;");

            Label idx = styledLbl(String.format("#%02d", r.index()),
                                   MUTED, "Calibri,Segoe UI,Arial", "11", "normal");
            idx.setMinWidth(36);

            VBox info = new VBox(3);
            info.getChildren().addAll(
                styledLbl(r.title(), FG, "Calibri,Segoe UI,Arial", "14", "bold"),
                styledLbl(String.format("%02d:%02d  ·  %d KB",
                          (int)(r.duration()/60), (int)(r.duration()%60),
                          r.sizeKb()),
                    MUTED, "Calibri,Segoe UI,Arial", "12", "normal"));
            HBox.setHgrow(info, Priority.ALWAYS);

            Button playBtn = secBtn("▶  Play");
            playBtn.setOnAction(e ->
                Platform.runLater(() -> playRecording(r, playBtn)));

            Button editBtn = blueBtn("✂  Editor");
            editBtn.setOnAction(e -> editorLoad(r.path()));

            Button folderBtn2 = ghostBtn("📁");
            folderBtn2.setOnAction(e -> showInFolder(r.path()));

            Button delBtn2 = new Button("✕");
            String del2Normal = "-fx-background-color:transparent;-fx-text-fill:" +
                MUTED + ";-fx-cursor:hand;-fx-font-size:16px;-fx-font-weight:bold;-fx-padding:4 10;";
            String del2Hover  = "-fx-background-color:transparent;-fx-text-fill:" +
                ACCENT + ";-fx-cursor:hand;-fx-font-size:16px;-fx-font-weight:bold;-fx-padding:4 10;";
            delBtn2.setStyle(del2Normal);
            delBtn2.setOnMouseEntered(e -> delBtn2.setStyle(del2Hover));
            delBtn2.setOnMouseExited(e  -> delBtn2.setStyle(del2Normal));
            delBtn2.setOnAction(e -> deleteRec(r));

            item.getChildren().addAll(idx, info, playBtn, editBtn,
                                       folderBtn2, delBtn2);
            recListBox.getChildren().add(item);
        }
    }

    void playRecording(Rec r, Button btn) {
        if (currentPlayIdx == r.index() && playLine != null) {
            stopListPlayback();
            return;
        }
        stopListPlayback();

        currentPlayIdx = r.index();
        currentPlayBtn = btn;
        playStop = false;
        btn.setText("⏹  Stopp");

        Mixer.Info outInfo = selectedOutput();
        playThread = new Thread(() -> {
            try {
                float[] audio = readWavToFloat(r.path().toFile());
                byte[]  bytes = floatsToBytes(audio);

                SourceDataLine line;
                if (outInfo != null) {
                    Mixer mixer = AudioSystem.getMixer(outInfo);
                    DataLine.Info dli = new DataLine.Info(SourceDataLine.class, FORMAT);
                    line = (SourceDataLine) mixer.getLine(dli);
                } else {
                    line = AudioSystem.getSourceDataLine(FORMAT);
                }
                line.open(FORMAT, 8192);
                line.start();
                playLine = line;

                int offset = 0, chunk = 8192;
                while (!playStop && offset < bytes.length) {
                    int len = Math.min(chunk, bytes.length - offset);
                    line.write(bytes, offset, len);
                    offset += len;
                }
                line.drain();
                line.close();
            } catch (Exception ex) {
                Platform.runLater(() ->
                    alert(Alert.AlertType.ERROR, "Fehler",
                          "Wiedergabe fehlgeschlagen:\n" + ex.getMessage()));
            }
            Platform.runLater(() -> {
                if (currentPlayBtn != null)
                    currentPlayBtn.setText("▶  Play");
                currentPlayIdx = -1;
                currentPlayBtn = null;
                playLine = null;
            });
        }, "play-thread");
        playThread.setDaemon(true);
        playThread.start();
    }

    void stopListPlayback() {
        playStop = true;
        if (playLine != null) {
            try { playLine.stop(); playLine.close(); } catch (Exception ignored) {}
            playLine = null;
        }
        if (currentPlayBtn != null) {
            currentPlayBtn.setText("▶  Play");
            currentPlayBtn = null;
        }
        currentPlayIdx = -1;
    }

    void deleteRec(Rec r) {
        if (!confirm("Löschen?",
                     "\"" + r.title() + "\" wirklich löschen?")) return;
        if (currentPlayIdx == r.index()) stopListPlayback();
        try { Files.deleteIfExists(r.path()); } catch (Exception ignored) {}
        recordings.remove(r);
        renderRecordings();
    }

    // ═══════════════════════════════════════════
    //  EDITOR
    // ═══════════════════════════════════════════
    void editorLoadDialog() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Audio laden");
        fc.setInitialDirectory(outputDir.toFile());
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            "Audio", "*.wav", "*.mp3", "*.m4a", "*.flac"));
        File f = fc.showOpenDialog(null);
        if (f != null) editorLoad(f.toPath());
    }

    void editorLoad(Path path) {
        Thread t = new Thread(() -> {
            try {
                float[] data;
                String ext = path.getFileName().toString().toLowerCase();
                if ((ext.endsWith(".mp3") || ext.endsWith(".m4a") ||
                     ext.endsWith(".ogg")) && ffmpeg != null) {
                    File tmp = File.createTempFile("ps_ed", ".wav");
                    runFFmpeg("-y", "-i", path.toString(),
                              "-ar", "48000", "-ac", "1", tmp.getAbsolutePath());
                    data = readWavToFloat(tmp);
                    tmp.delete();
                } else {
                    data = readWavToFloat(path.toFile());
                }
                editAudio   = data;
                editSr      = (int) SR;
                editPath    = path;
                selStart    = -1; selEnd = -1;
                cursorPos   = 0;
                undoStack.clear();

                Platform.runLater(() -> {
                    editFileLbl.setText(path.getFileName().toString());
                    updateEditInfo();
                    drawEditor();
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                    alert(Alert.AlertType.ERROR, "Fehler",
                          "Datei konnte nicht geladen werden:\n" + ex.getMessage()));
            }
        }, "editor-load");
        t.setDaemon(true);
        t.start();
    }

    void updateEditInfo() {
        if (editAudio == null) { editInfoLbl.setText(""); return; }
        double dur = editAudio.length / (double) editSr;
        editInfoLbl.setText(String.format("%02d:%06.3f  ·  %d Hz  ·  %,d Samples",
            (int)(dur/60), dur%60, editSr, editAudio.length));
    }

    void drawEditor() {
        if (editCanvas == null) return;
        GraphicsContext gc = editCanvas.getGraphicsContext2D();
        double w = editCanvas.getWidth(), h = editCanvas.getHeight();
        gc.clearRect(0, 0, w, h);
        gc.setFill(Color.web(CARD));
        gc.fillRect(0, 0, w, h);

        gc.setStroke(Color.web(BORDER));
        gc.setLineWidth(1);
        gc.strokeLine(0, h / 2, w, h / 2);

        if (editAudio == null || editAudio.length == 0) {
            gc.setFill(Color.web(MUTED));
            gc.setFont(javafx.scene.text.Font.font("Segoe UI", 14));
            gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
            gc.fillText("📂  Aufnahme laden", w / 2, h / 2 + 5);
            return;
        }

        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
            int a = (int) Math.min(selStart, selEnd);
            int b = (int) Math.max(selStart, selEnd);
            double x1 = sampleToX(a, w), x2 = sampleToX(b, w);
            gc.setFill(Color.web(SELECT_COL));
            gc.fillRect(x1, 0, x2 - x1, h);
        }

        int n = editAudio.length;
        int iw = (int) w;

        // Peak-Cache: nur neu berechnen wenn Audio oder Canvas-Breite sich aendert
        // (vorher: O(n) pro Frame waehrend Wiedergabe = Milliarden Operationen/Sek)
        if (cachedPeaks == null || cachedPeaksWidth != iw || cachedPeaksAudio != editAudio) {
            int sampPerPx = Math.max(1, n / iw);
            cachedPeaks = new float[iw];
            for (int x = 0; x < iw; x++) {
                int start = x * sampPerPx;
                int end   = Math.min(start + sampPerPx, n);
                if (start >= n) break;
                float peak = 0f;
                for (int i = start; i < end; i++)
                    peak = Math.max(peak, Math.abs(editAudio[i]));
                cachedPeaks[x] = peak;
            }
            cachedPeaksWidth = iw;
            cachedPeaksAudio = editAudio;
        }

        gc.setStroke(Color.web(ACCENT));
        gc.setLineWidth(1);
        double halfH = h / 2;
        for (int x = 0; x < iw; x++) {
            double amp = cachedPeaks[x] * halfH * 0.92;
            gc.strokeLine(x, halfH - amp, x, halfH + amp);
        }

        double cx = sampleToX(cursorPos, w);
        gc.setStroke(Color.web(BLUE));
        gc.setLineWidth(2);
        gc.strokeLine(cx, 0, cx, h);

        double dur = n / (double) editSr;
        timeEndLbl.setText(toTime(dur));
        timeCursorLbl.setText("⌖  " + toTime(cursorPos / (double) editSr));

        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
            int a = Math.min(selStart, selEnd);
            int b = Math.max(selStart, selEnd);
            double selDur = (b - a) / (double) editSr;
            selInfoLbl.setText(String.format("AUSWAHL: %s → %s  ·  %.3fs",
                toTime(a / (double)editSr), toTime(b / (double)editSr), selDur));
        } else {
            selInfoLbl.setText("");
        }
    }

    double sampleToX(int sample, double w) {
        if (editAudio == null || editAudio.length == 0) return 0;
        return (sample / (double) editAudio.length) * w;
    }

    int xToSample(double x) {
        if (editAudio == null || editAudio.length == 0) return 0;
        double w = editCanvas.getWidth();
        double ratio = Math.max(0, Math.min(1, x / w));
        return (int)(ratio * editAudio.length);
    }

    void editMousePressed(javafx.scene.input.MouseEvent e) {
        if (editAudio == null) return;
        dragStartSample = xToSample(e.getX());
        selStart = dragStartSample; selEnd = dragStartSample;
        cursorPos = dragStartSample;
        drawEditor();
    }

    void editMouseDragged(javafx.scene.input.MouseEvent e) {
        if (editAudio == null || dragStartSample < 0) return;
        selEnd = xToSample(e.getX());
        drawEditor();
    }

    void editMouseReleased(javafx.scene.input.MouseEvent e) {
        if (editAudio == null) return;
        if (Math.abs(selEnd - selStart) < editSr * 0.01) {
            cursorPos = selStart;
            selStart  = -1; selEnd = -1;
        }
        dragStartSample = -1;
        drawEditor();
    }

    void pushUndo() {
        if (editAudio != null) {
            undoStack.push(Arrays.copyOf(editAudio, editAudio.length));
            if (undoStack.size() > 30) {
                Deque<float[]> trimmed = new ArrayDeque<>();
                int i = 0;
                for (float[] s : undoStack) { if (i++ < 30) trimmed.push(s); }
                undoStack = trimmed;
            }
        }
    }

    int[] selRange() {
        if (selStart < 0 || selEnd < 0 || selStart == selEnd) return null;
        int a = Math.max(0, Math.min(selStart, selEnd));
        int b = Math.min(editAudio.length, Math.max(selStart, selEnd));
        return a == b ? null : new int[]{a, b};
    }

    void editCopy() {
        if (editAudio == null) return;
        int[] r = selRange();
        if (r == null) { alert(Alert.AlertType.INFORMATION, "Kopieren",
            "Bitte zuerst einen Bereich markieren."); return; }
        clipboard = Arrays.copyOfRange(editAudio, r[0], r[1]);
        selInfoLbl.setText(String.format("📋  Kopiert: %.3fs",
            clipboard.length / (double) editSr));
    }

    void editCut() {
        if (editAudio == null) return;
        int[] r = selRange();
        if (r == null) { alert(Alert.AlertType.INFORMATION, "Ausschneiden",
            "Bitte zuerst einen Bereich markieren."); return; }
        pushUndo();
        clipboard = Arrays.copyOfRange(editAudio, r[0], r[1]);
        editAudio = concat(
            Arrays.copyOfRange(editAudio, 0, r[0]),
            Arrays.copyOfRange(editAudio, r[1], editAudio.length));
        cursorPos = r[0]; selStart = -1; selEnd = -1;
        updateEditInfo(); drawEditor();
    }

    void editDelete() {
        if (editAudio == null) return;
        int[] r = selRange();
        if (r == null) { alert(Alert.AlertType.INFORMATION, "Löschen",
            "Bitte zuerst einen Bereich markieren."); return; }
        pushUndo();
        editAudio = concat(
            Arrays.copyOfRange(editAudio, 0, r[0]),
            Arrays.copyOfRange(editAudio, r[1], editAudio.length));
        cursorPos = r[0]; selStart = -1; selEnd = -1;
        updateEditInfo(); drawEditor();
    }

    void editPaste() {
        if (editAudio == null || clipboard == null || clipboard.length == 0) {
            alert(Alert.AlertType.INFORMATION, "Einfügen",
                  "Zwischenablage ist leer."); return;
        }
        pushUndo();
        int[] r = selRange();
        if (r != null) {
            editAudio = concat(
                Arrays.copyOfRange(editAudio, 0, r[0]),
                clipboard,
                Arrays.copyOfRange(editAudio, r[1], editAudio.length));
            cursorPos = r[0] + clipboard.length;
        } else {
            int p = Math.min(cursorPos, editAudio.length);
            editAudio = concat(
                Arrays.copyOfRange(editAudio, 0, p),
                clipboard,
                Arrays.copyOfRange(editAudio, p, editAudio.length));
            cursorPos = p + clipboard.length;
        }
        selStart = -1; selEnd = -1;
        updateEditInfo(); drawEditor();
    }

    void editorUndo() {
        if (undoStack.isEmpty()) return;
        editAudio = undoStack.pop();
        selStart = -1; selEnd = -1;
        if (cursorPos >= editAudio.length) cursorPos = editAudio.length - 1;
        updateEditInfo(); drawEditor();
    }

    // ═══════════════════════════════════════════
    //  FADE-IN / FADE-OUT (NEU)
    // ═══════════════════════════════════════════
    void applyFade(boolean fadeIn) {
        if (editAudio == null) {
            alert(Alert.AlertType.INFORMATION, "Fade",
                  "Bitte zuerst eine Datei laden."); return;
        }
        String dur = fadeDurCombo.getValue();
        double sec = Double.parseDouble(dur.replace("s", ""));
        int fadeSamples = (int)(sec * editSr);

        pushUndo();

        int[] r = selRange();
        int startSample, endSample;
        if (r != null) {
            // Auswahl: Fade an Start oder Ende der Auswahl
            if (fadeIn) {
                startSample = r[0];
                endSample   = Math.min(r[0] + fadeSamples, r[1]);
            } else {
                startSample = Math.max(r[1] - fadeSamples, r[0]);
                endSample   = r[1];
            }
        } else {
            // Kein Auswahl: Fade am Anfang oder Ende der Datei
            if (fadeIn) {
                startSample = 0;
                endSample   = Math.min(fadeSamples, editAudio.length);
            } else {
                startSample = Math.max(editAudio.length - fadeSamples, 0);
                endSample   = editAudio.length;
            }
        }

        int len = endSample - startSample;
        if (len <= 0) return;

        for (int i = 0; i < len; i++) {
            float factor;
            if (fadeIn) {
                factor = (float) i / len;
            } else {
                factor = 1f - (float) i / len;
            }
            // Logarithmische Kurve für natürlicheren Klang
            factor = factor * factor;
            editAudio[startSample + i] *= factor;
        }
        cachedPeaks = null;  // In-Place-Modifikation: Cache invalidieren
        drawEditor();
    }

    // ═══════════════════════════════════════════
    //  NORMALISIERUNG (NEU)
    // ═══════════════════════════════════════════
    void applyNormalize() {
        if (editAudio == null) {
            alert(Alert.AlertType.INFORMATION, "Normalisieren",
                  "Bitte zuerst eine Datei laden."); return;
        }
        int[] r = selRange();
        int start = r != null ? r[0] : 0;
        int end   = r != null ? r[1] : editAudio.length;

        // Peak finden
        float peak = 0f;
        for (int i = start; i < end; i++) {
            peak = Math.max(peak, Math.abs(editAudio[i]));
        }
        if (peak == 0) {
            alert(Alert.AlertType.INFORMATION, "Normalisieren",
                  "Bereich ist still (kein Audio).");
            return;
        }

        // Ziel: -1dB (≈ 0.891)
        float targetPeak = 0.891f;
        float factor = targetPeak / peak;

        if (Math.abs(factor - 1f) < 0.01f) {
            alert(Alert.AlertType.INFORMATION, "Normalisieren",
                  "Audio ist bereits optimal normalisiert.");
            return;
        }

        pushUndo();
        for (int i = start; i < end; i++) {
            editAudio[i] = Math.max(-1f, Math.min(1f, editAudio[i] * factor));
        }
        cachedPeaks = null;  // In-Place-Modifikation: Cache invalidieren
        drawEditor();

        double db = 20 * Math.log10(factor);
        selInfoLbl.setText(String.format("🔊  Normalisiert  (%+.1f dB)", db));
    }

    // ═══════════════════════════════════════════
    //  EDITOR PLAYBACK
    // ═══════════════════════════════════════════
    void editorTogglePlay() {
        if (editAudio == null) return;
        if (editorPlaying.get()) {
            editorStopPlay(); return;
        }
        int[] r = selRange();
        int from = r != null ? r[0] : cursorPos;
        int to   = r != null ? r[1] : editAudio.length;
        if (from >= editAudio.length - 1) from = 0;

        final int fromFinal = from;
        final int toFinal   = to;
        float[] seg = Arrays.copyOfRange(editAudio, fromFinal, toFinal);
        byte[]  buf = floatsToBytes(seg);

        Mixer.Info outInfo = selectedOutput();
        editorPlaying.set(true);
        btnPlayEdit.setText("⏹  Stopp");

        // Cursor sofort an Start-Position setzen und zeichnen
        cursorPos = fromFinal;
        drawEditor();

        editorPlayThread = new Thread(() -> {
            try {
                SourceDataLine line;
                if (outInfo != null) {
                    Mixer mx = AudioSystem.getMixer(outInfo);
                    DataLine.Info dli = new DataLine.Info(SourceDataLine.class, FORMAT);
                    line = (SourceDataLine) mx.getLine(dli);
                } else {
                    line = AudioSystem.getSourceDataLine(FORMAT);
                }
                line.open(FORMAT, 8192);
                line.start();
                editorLine = line;

                // ── Cursor-Updater: 25 fps Position-Sync auf JavaFX-Thread ──
                final SourceDataLine lineFinal = line;
                Thread cursorUpd = new Thread(() -> {
                    while (editorPlaying.get()) {
                        try {
                            long playedFrames = lineFinal.getLongFramePosition();
                            int newCursor = fromFinal + (int) playedFrames;
                            if (newCursor >= toFinal) newCursor = toFinal - 1;
                            if (newCursor < 0)        newCursor = fromFinal;
                            final int fc = newCursor;
                            Platform.runLater(() -> {
                                cursorPos = fc;
                                drawEditor();
                            });
                            Thread.sleep(EDITOR_FRAME_INTERVAL_MS);
                        } catch (InterruptedException ie) { break; }
                          catch (Exception ignored)      { break; }
                    }
                }, "editor-cursor");
                cursorUpd.setDaemon(true);
                cursorUpd.start();

                int offset = 0, chunk = 8192;
                while (editorPlaying.get() && offset < buf.length) {
                    int len = Math.min(chunk, buf.length - offset);
                    line.write(buf, offset, len);
                    offset += len;
                }
                line.drain();
                line.close();
            } catch (Exception ex) {
                Platform.runLater(() ->
                    alert(Alert.AlertType.ERROR, "Fehler",
                          "Wiedergabe fehlgeschlagen:\n" + ex.getMessage()));
            }
            // editorPlaying VOR Platform.runLater auf false setzen,
            // damit der Cursor-Updater seinen Loop sofort verlässt
            // und keine weiteren Cursor-Updates mehr queuet
            editorPlaying.set(false);
            final boolean wasRange = (r != null);
            Platform.runLater(() -> {
                btnPlayEdit.setText("▶  Play");
                // Cursor am Ende der Wiedergabe-Region positionieren
                if (editAudio != null) {
                    int endPos = wasRange ? fromFinal : Math.min(toFinal - 1, editAudio.length - 1);
                    cursorPos = Math.max(0, endPos);
                    drawEditor();
                }
            });
        }, "editor-play");
        editorPlayThread.setDaemon(true);
        editorPlayThread.start();
    }

    void editorStopPlay() {
        editorPlaying.set(false);
        if (editorLine != null) {
            try { editorLine.stop(); editorLine.close(); } catch (Exception ignored) {}
            editorLine = null;
        }
        btnPlayEdit.setText("▶  Play");
    }

    void applySpeed(double factor) {
        if (editAudio == null) {
            alert(Alert.AlertType.INFORMATION, "Geschwindigkeit",
                  "Bitte zuerst eine Datei laden."); return;
        }
        if (ffmpeg == null) {
            alert(Alert.AlertType.ERROR, "FFmpeg fehlt",
                  "Diese Funktion benötigt FFmpeg."); return;
        }
        int[] r = selRange();
        float[] part = r != null
            ? Arrays.copyOfRange(editAudio, r[0], r[1]) : editAudio;

        Thread st = new Thread(() -> {
            try {
                File inTmp  = File.createTempFile("ps_sp_in", ".wav");
                File outTmp = File.createTempFile("ps_sp_out", ".wav");
                writeWav(inTmp, part);
                runFFmpeg("-y", "-i", inTmp.getAbsolutePath(),
                          "-filter:a", "atempo=" + factor,
                          outTmp.getAbsolutePath());
                float[] result = readWavToFloat(outTmp);
                inTmp.delete(); outTmp.delete();

                Platform.runLater(() -> {
                    pushUndo();
                    if (r != null) {
                        editAudio = concat(
                            Arrays.copyOfRange(editAudio, 0, r[0]),
                            result,
                            Arrays.copyOfRange(editAudio, r[1], editAudio.length));
                        cursorPos = r[0] + result.length;
                    } else {
                        editAudio = result;
                        cursorPos = 0;
                    }
                    selStart = -1; selEnd = -1;
                    updateEditInfo(); drawEditor();
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                    alert(Alert.AlertType.ERROR, "FFmpeg-Fehler",
                          "Fehlgeschlagen:\n" + ex.getMessage()));
            }
        }, "speed-thread");
        st.setDaemon(true);
        st.start();
    }

    void editorSaveWav() {
        if (editAudio == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Als WAV speichern");
        fc.setInitialDirectory(outputDir.toFile());
        fc.setInitialFileName(editPath != null
            ? editPath.getFileName().toString().replaceAll("\\.\\w+$", "_edit.wav")
            : "edit.wav");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("WAV", "*.wav"));
        File f = fc.showSaveDialog(null);
        if (f == null) return;
        try {
            writeWav(f, editAudio);
            alert(Alert.AlertType.INFORMATION, "Gespeichert",
                  "Gespeichert:\n" + f.getAbsolutePath());
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Fehler",
                  "Speichern fehlgeschlagen:\n" + ex.getMessage());
        }
    }

    void editorExportMp3() {
        if (editAudio == null) return;
        if (ffmpeg == null) {
            alert(Alert.AlertType.ERROR, "FFmpeg fehlt",
                  "MP3-Export benötigt FFmpeg."); return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Als MP3 exportieren");
        fc.setInitialDirectory(outputDir.toFile());
        fc.setInitialFileName(editPath != null
            ? editPath.getFileName().toString().replaceAll("\\.\\w+$", ".mp3")
            : "podcast.mp3");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("MP3", "*.mp3"));
        File f = fc.showSaveDialog(null);
        if (f == null) return;

        Thread mp = new Thread(() -> {
            try {
                File tmp = File.createTempFile("ps_mp3", ".wav");
                writeWav(tmp, editAudio);
                runFFmpeg("-y", "-i", tmp.getAbsolutePath(),
                          "-codec:a", "libmp3lame", "-b:a", "128k",
                          f.getAbsolutePath());
                tmp.delete();
                Platform.runLater(() ->
                    alert(Alert.AlertType.INFORMATION, "Export fertig",
                          "MP3 exportiert:\n" + f.getAbsolutePath()));
            } catch (Exception ex) {
                Platform.runLater(() ->
                    alert(Alert.AlertType.ERROR, "FFmpeg-Fehler",
                          "MP3-Export fehlgeschlagen:\n" + ex.getMessage()));
            }
        }, "mp3-export");
        mp.setDaemon(true);
        mp.start();
    }

    // ═══════════════════════════════════════════
    //  ANIMATION LOOP
    // ═══════════════════════════════════════════
    void startLoop() {
        new AnimationTimer() {
            long lastWaveform = 0;
            long lastTimer    = 0;
            int  lastDisplayedSec = -1;
            boolean lastMeterZero = false;

            @Override public void handle(long now) {
                // Timer nur 1x pro Sekunde aktualisieren (nicht 60x!)
                if (now - lastTimer > 250_000_000L) {
                    if (recording && !paused && startTime != null) {
                        long wallMs = java.time.Duration.between(startTime, Instant.now()).toMillis();
                        elapsed = (int)((wallMs - totalPausedMs) / 1000);
                    }
                    if (elapsed != lastDisplayedSec && timerLbl != null) {
                        int h = elapsed / 3600, m = (elapsed % 3600) / 60, s = elapsed % 60;
                        timerLbl.setText(String.format("%02d:%02d:%02d", h, m, s));
                        lastDisplayedSec = elapsed;
                    }
                    lastTimer = now;
                }

                // Meter-Update nur wenn Pegel > 0 oder gerade verstummt
                // Verwendet gecachte Color-Objekte (siehe statische Felder) - vorher
                // Color.web() Aufruf in Hot-Path war 60fps Allocation-Quelle
                if (meterFill != null && meterPane != null) {
                    if (level > 0.001f || !lastMeterZero) {
                        double pw = meterPane.getWidth();
                        if (pw > 0) {
                            double lw = pw * level;
                            meterFill.setWidth(lw);
                            Color c = level > 0.85 ? METER_COLOR_HIGH
                                    : level > 0.6  ? METER_COLOR_MID
                                    : METER_COLOR_LOW;
                            meterFill.setFill(c);
                        }
                        level *= 0.92f;
                        lastMeterZero = (level <= 0.001f);
                        if (lastMeterZero) level = 0f;
                    }
                }

                // Waveform nur 20x pro Sekunde + nur bei aktiver Aufnahme; nach Stopp einmal leeren
                if (recording && !paused && now - lastWaveform > 50_000_000L) {
                    drawLiveWaveform();
                    liveCanvasCleared = false;
                    lastWaveform = now;
                } else if (!recording && !liveCanvasCleared) {
                    Arrays.fill(waveBuf, 0f);
                    drawLiveWaveform();
                    liveCanvasCleared = true;
                }
            }
        }.start();
    }

    boolean liveCanvasCleared = false;

    void drawLiveWaveform() {
        if (liveCanvas == null) return;
        GraphicsContext gc = liveCanvas.getGraphicsContext2D();
        double w = liveCanvas.getWidth(), h = liveCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        // fillRect ueberschreibt eh - clearRect waere redundant
        gc.setFill(Color.web(CARD));
        gc.fillRect(0, 0, w, h);

        // Saubere Mittellinie
        gc.setStroke(Color.web(BORDER));
        gc.setLineWidth(1);
        gc.strokeLine(0, h / 2, w, h / 2);

        // Nur zeichnen wenn wirklich Audio kommt UND aktiv aufgenommen wird
        // (verhindert "Geister-Punkte" durch Restwerte im Buffer)
        if (!recording && !paused) return;  // im Ruhezustand nur Mittellinie

        boolean hasSignal = false;
        for (float v : waveBuf) {
            if (Math.abs(v) > 0.01f) { hasSignal = true; break; }
        }
        if (!hasSignal) return;

        String col = paused ? ACCENT2 : recording ? ACCENT : MUTED;
        gc.setStroke(Color.web(col));
        gc.setLineWidth(2);

        double bw = w / waveBuf.length;
        for (int i = 0; i < waveBuf.length; i++) {
            double amp = waveBuf[i] * (h / 2) * 0.9;
            if (Math.abs(amp) < 2.0) continue;  // keine sichtbaren Punkte zeichnen (höherer Threshold)
            double x = i * bw;
            gc.strokeLine(x, h / 2 - amp, x, h / 2 + amp);
        }
    }

    void setUIState(String state) {
        Platform.runLater(() -> {
            switch (state) {
                case "recording" -> {
                    statusLbl.setText("● REC");
                    statusLbl.setStyle(statusLbl.getStyle()
                        .replaceAll("-fx-text-fill:[^;]+", "-fx-text-fill:" + ACCENT));
                    btnRec.setText("⏹  STOPP & SPEICHERN");
                    btnPause.setDisable(false);
                    btnStop.setDisable(false);
                    btnMarker.setDisable(false);
                    btnPause.setText("⏸  Pause");
                }
                case "paused" -> {
                    statusLbl.setText("⏸  PAUSE");
                    statusLbl.setStyle(statusLbl.getStyle()
                        .replaceAll("-fx-text-fill:[^;]+", "-fx-text-fill:" + ACCENT2));
                    btnPause.setText("▶  Weiter");
                }
                default -> {
                    statusLbl.setText("BEREIT");
                    statusLbl.setStyle(statusLbl.getStyle()
                        .replaceAll("-fx-text-fill:[^;]+", "-fx-text-fill:" + MUTED));
                    btnRec.setText("●  AUFNAHME STARTEN");
                    btnPause.setDisable(true);
                    btnStop.setDisable(true);
                    btnMarker.setDisable(true);
                    btnPause.setText("⏸  Pause");
                }
            }
        });
    }

    // ═══════════════════════════════════════════
    //  AUDIO UTILS
    // ═══════════════════════════════════════════
    float[] readWavToFloat(File f) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(f)) {
            AudioFormat fmt = ais.getFormat();
            byte[] bytes = ais.readAllBytes();
            if (fmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED ||
                fmt.getSampleSizeInBits() != 16) {
                AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    fmt.getSampleRate(), 16,
                    fmt.getChannels(), fmt.getChannels() * 2, fmt.getSampleRate(), false);
                AudioInputStream conv = AudioSystem.getAudioInputStream(target,
                    new AudioInputStream(new ByteArrayInputStream(bytes), fmt,
                                         bytes.length / fmt.getFrameSize()));
                bytes = conv.readAllBytes();
                fmt   = target;
            }
            int ch   = fmt.getChannels();
            int nFrames = bytes.length / (2 * ch);
            float[] out = new float[nFrames];
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < nFrames; i++) {
                float sum = 0;
                for (int c = 0; c < ch; c++) sum += bb.getShort() / 32768f;
                out[i] = sum / ch;
            }
            return out;
        }
    }

    void writeWav(File f, float[] audio) throws Exception {
        byte[] bytes = floatsToBytes(audio);
        AudioInputStream ais = new AudioInputStream(
            new ByteArrayInputStream(bytes), FORMAT, bytes.length / 2);
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, f);
    }

    byte[] floatsToBytes(float[] audio) {
        ByteBuffer bb = ByteBuffer.allocate(audio.length * 2)
                                  .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : audio)
            bb.putShort((short) Math.max(-32768, Math.min(32767, v * 32767f)));
        return bb.array();
    }

    float[] concat(float[]... arrays) {
        int total = 0;
        for (float[] a : arrays) total += a.length;
        float[] out = new float[total];
        int pos = 0;
        for (float[] a : arrays) { System.arraycopy(a, 0, out, pos, a.length); pos += a.length; }
        return out;
    }

    // ═══════════════════════════════════════════
    //  FFMPEG
    // ═══════════════════════════════════════════
    String findFFmpeg() {
        String exe = System.getProperty("user.dir") + File.separator + "ffmpeg.exe";
        if (new File(exe).exists()) return exe;
        try {
            Process p = Runtime.getRuntime().exec("where ffmpeg");
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!out.isBlank()) return out.split("\\R")[0];
        } catch (Exception ignored) {}
        for (String loc : List.of(
            "C:\\ffmpeg\\bin\\ffmpeg.exe",
            "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe")) {
            if (new File(loc).exists()) return loc;
        }
        return null;
    }

    void runFFmpeg(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0)
            throw new IOException("FFmpeg fehlgeschlagen (" + exit + "):\n"
                + output.substring(Math.max(0, output.length() - 600)));
    }

    // ═══════════════════════════════════════════
    //  WHISPER  (Speech-to-Text)
    // ═══════════════════════════════════════════
    String findWhisper() {
        String dir = System.getProperty("user.dir");
        // Mögliche Pfade / Dateinamen für whisper.exe
        String[] candidates = {
            dir + File.separator + "whisper.exe",
            dir + File.separator + "whisper-cli.exe",
            dir + File.separator + "main.exe",
            dir + File.separator + "whisper" + File.separator + "whisper-cli.exe",
            dir + File.separator + "whisper" + File.separator + "whisper.exe",
            dir + File.separator + "whisper" + File.separator + "main.exe"
        };
        for (String c : candidates) {
            if (new File(c).exists()) return c;
        }
        return null;
    }

    String findWhisperModel() {
        String dir = System.getProperty("user.dir");
        // Reihenfolge: small > medium > base > tiny (Qualität / Größe abwägen)
        String[] names = {
            "ggml-small.bin", "ggml-small.de.bin",
            "ggml-medium.bin", "ggml-medium.de.bin",
            "ggml-base.bin", "ggml-base.de.bin",
            "ggml-tiny.bin", "ggml-tiny.de.bin",
            "ggml-large-v3.bin"
        };
        String[] subdirs = { "", "whisper" + File.separator,
                             "whisper" + File.separator + "models" + File.separator,
                             "models" + File.separator };
        for (String sub : subdirs) {
            for (String n : names) {
                File f = new File(dir, sub + n);
                if (f.exists()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    record TranscriptSegment(double startSec, double endSec, String text) {}

    /** Parst Whisper.cpp-SRT-Output in eine Liste von Segmenten. */
    List<TranscriptSegment> parseSrt(String srt) {
        List<TranscriptSegment> result = new ArrayList<>();
        String[] blocks = srt.split("\\r?\\n\\r?\\n");
        for (String block : blocks) {
            String[] lines = block.split("\\r?\\n");
            if (lines.length < 3) continue;
            String timeRange = lines[1].trim();
            String[] times = timeRange.split(" --> ");
            if (times.length != 2) continue;
            double start = parseSrtTime(times[0].trim());
            double end   = parseSrtTime(times[1].trim());
            StringBuilder text = new StringBuilder();
            for (int i = 2; i < lines.length; i++) {
                if (i > 2) text.append(" ");
                text.append(lines[i].trim());
            }
            String t = text.toString().trim();
            if (!t.isEmpty()) result.add(new TranscriptSegment(start, end, t));
        }
        return result;
    }

    double parseSrtTime(String t) {
        // Format: HH:MM:SS,mmm
        try {
            String[] hms = t.split(":");
            int h = Integer.parseInt(hms[0]);
            int m = Integer.parseInt(hms[1]);
            String[] sms = hms[2].split(",");
            int s = Integer.parseInt(sms[0]);
            int ms = sms.length > 1 ? Integer.parseInt(sms[1]) : 0;
            return h * 3600.0 + m * 60.0 + s + ms / 1000.0;
        } catch (Exception e) { return 0; }
    }

    /** Startet die Transkription des aktuell im Editor geladenen Audios. */
    void editorTranscribe() {
        if (editAudio == null) {
            alert(Alert.AlertType.INFORMATION, "Keine Datei",
                  "Bitte zuerst eine Aufnahme in den Editor laden."); return;
        }
        if (whisperExe == null || whisperModel == null) {
            showWhisperSetupDialog();
            return;
        }

        // Fortschritts-Dialog
        Stage prog = new Stage();
        prog.initOwner(uiStage);
        prog.initModality(Modality.WINDOW_MODAL);
        prog.setTitle("Transkription läuft...");
        VBox pbox = new VBox(14);
        pbox.setPadding(new Insets(24));
        pbox.setStyle(bg(SURFACE));
        pbox.setAlignment(Pos.CENTER);
        Label info = styledLbl("📝  Whisper transkribiert das Audio...",
            FG, "Calibri,Segoe UI,Arial", "14", "bold");
        Label sub  = styledLbl("Das kann je nach Modell und Dauer einige Minuten brauchen.",
            MUTED, "Calibri,Segoe UI,Arial", "11", "normal");
        ProgressBar pb = new ProgressBar();
        pb.setPrefWidth(360);
        pbox.getChildren().addAll(info, sub, pb);
        Scene ps = new Scene(pbox, 440, 160);
        ps.setFill(Color.web(BG));
        prog.setScene(ps);
        prog.show();

        Thread t = new Thread(() -> {
            File wav = null, outBase = null, srtFile = null;
            try {
                // Audio in WAV speichern (Whisper braucht WAV-Input)
                wav = File.createTempFile("ps_whisper_", ".wav");
                writeWav(wav, editAudio);

                // Output-Basis (Whisper hängt .srt an)
                outBase = File.createTempFile("ps_whisper_out_", "");
                outBase.delete(); // Whisper erstellt selbst

                List<String> cmd = new ArrayList<>();
                cmd.add(whisperExe);
                cmd.add("-m"); cmd.add(whisperModel);
                cmd.add("-f"); cmd.add(wav.getAbsolutePath());
                cmd.add("-l"); cmd.add("de");
                cmd.add("-osrt");                         // SRT-Output
                cmd.add("-of"); cmd.add(outBase.getAbsolutePath());
                cmd.add("--no-prints");                   // weniger Log-Spam

                ProcessBuilder build = new ProcessBuilder(cmd);
                build.redirectErrorStream(true);
                Process p = build.start();
                // Output verschlucken (sonst blockiert der Pipe)
                new Thread(() -> {
                    try { p.getInputStream().readAllBytes(); } catch (Exception ignored) {}
                }, "whisper-output").start();
                int exit = p.waitFor();
                if (exit != 0) throw new IOException("Whisper exit " + exit);

                srtFile = new File(outBase.getAbsolutePath() + ".srt");
                if (!srtFile.exists()) throw new IOException("Keine SRT-Datei erzeugt");

                String srt = Files.readString(srtFile.toPath());
                List<TranscriptSegment> segs = parseSrt(srt);

                Platform.runLater(() -> {
                    prog.close();
                    if (segs.isEmpty()) {
                        alert(Alert.AlertType.WARNING, "Leeres Transkript",
                              "Whisper hat keinen Text erkannt. Vielleicht ist das Audio zu leise?");
                    } else {
                        showTranscript(segs);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    prog.close();
                    alert(Alert.AlertType.ERROR, "Whisper-Fehler",
                          "Transkription fehlgeschlagen:\n" + ex.getMessage());
                });
            } finally {
                if (wav     != null) wav.delete();
                if (srtFile != null) srtFile.delete();
            }
        }, "whisper-run");
        t.setDaemon(true);
        t.start();
    }

    /** Zeigt das Transkript in einem eigenen Fenster mit Klickbarer Liste. */
    void showTranscript(List<TranscriptSegment> segs) {
        Stage st = new Stage();
        st.setTitle("📝 Transkript  ·  "
            + (editPath != null ? editPath.getFileName().toString() : ""));

        VBox root = new VBox(0);
        root.setStyle(bg(BG));

        // Header
        HBox hdr = new HBox(10);
        hdr.setPadding(new Insets(14, 20, 14, 20));
        hdr.setAlignment(Pos.CENTER_LEFT);
        hdr.setStyle(bg(SURFACE) + "-fx-border-color:" + BORDER +
                     ";-fx-border-width:0 0 1 0;");

        Label hdrLbl = styledLbl(
            "📝  TRANSKRIPT  ·  " + segs.size() + " Segmente",
            FG, "Calibri,Segoe UI,Arial", "16", "bold");
        Region hsp = new Region();
        HBox.setHgrow(hsp, Priority.ALWAYS);

        Button btnTxt  = secBtn("💾  Als TXT speichern");
        Button btnSrt  = secBtn("🎬  Als SRT speichern");
        Button btnCopy = ghostBtn("📋  Alles kopieren");
        btnTxt.setOnAction(e -> saveTranscriptTxt(segs));
        btnSrt.setOnAction(e -> saveTranscriptSrt(segs));
        btnCopy.setOnAction(e -> {
            StringBuilder sb = new StringBuilder();
            for (TranscriptSegment s : segs) sb.append(s.text()).append("\n");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                new java.util.HashMap<>() {{
                    put(javafx.scene.input.DataFormat.PLAIN_TEXT, sb.toString());
                }});
        });

        hdr.getChildren().addAll(hdrLbl, hsp, btnCopy, btnTxt, btnSrt);
        root.getChildren().add(hdr);

        // Liste
        VBox list = new VBox(2);
        list.setPadding(new Insets(12, 0, 12, 0));
        list.setStyle(bg(BG));

        int idx = 0;
        for (TranscriptSegment s : segs) {
            HBox row = new HBox(14);
            row.setAlignment(Pos.TOP_LEFT);
            row.setPadding(new Insets(8, 20, 8, 20));
            row.setStyle("-fx-cursor:hand;");
            // Zebra-Streifen
            if ((idx & 1) == 1) row.setStyle(row.getStyle() + bg(CARD));

            Label time = styledLbl(formatTimeShort(s.startSec()),
                ACCENT, "Calibri,Segoe UI,Arial", "12", "bold");
            time.setMinWidth(80);

            Label txt = styledLbl(s.text(), FG, "Calibri,Segoe UI,Arial", "13", "normal");
            txt.setWrapText(true);
            txt.setMaxWidth(700);
            HBox.setHgrow(txt, Priority.ALWAYS);

            // Klick → Cursor an diese Stelle im Editor springen
            row.setOnMouseClicked(e -> {
                if (editAudio == null) return;
                int sample = (int)(s.startSec() * editSr);
                if (sample >= 0 && sample < editAudio.length) {
                    cursorPos = sample;
                    drawEditor();
                }
            });
            row.setOnMouseEntered(e -> row.setStyle(
                row.getStyle().replaceAll("-fx-background-color:[^;]+;?", "") +
                "-fx-background-color:" + SELECT_COL + ";-fx-cursor:hand;"));
            row.setOnMouseExited(e -> {
                String s2 = "-fx-cursor:hand;";
                int finalIdx = list.getChildren().indexOf(row);
                if ((finalIdx & 1) == 1) s2 = s2 + bg(CARD);
                row.setStyle(s2);
            });

            row.getChildren().addAll(time, txt);
            list.getChildren().add(row);
            idx++;
        }

        ScrollPane sp = new ScrollPane(list);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color:" + BG + ";-fx-background:" + BG + ";");
        VBox.setVgrow(sp, Priority.ALWAYS);
        root.getChildren().add(sp);

        // Footer mit Hinweis
        Label foot = styledLbl(
            "💡  Tipp: Klick auf eine Zeile → Cursor springt zur entsprechenden Stelle im Audio",
            MUTED, "Calibri,Segoe UI,Arial", "11", "italic");
        foot.setPadding(new Insets(10, 20, 12, 20));
        foot.setStyle(foot.getStyle() + bg(SURFACE) +
            "-fx-border-color:" + BORDER + ";-fx-border-width:1 0 0 0;");
        root.getChildren().add(foot);

        Scene sc = new Scene(root, 900, 700);
        sc.setFill(Color.web(BG));
        st.setScene(sc);
        st.show();
    }

    String formatTimeShort(double sec) {
        int m = (int)(sec / 60);
        int s = (int)(sec % 60);
        return String.format("%02d:%02d", m, s);
    }

    void saveTranscriptTxt(List<TranscriptSegment> segs) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Transkript als Text speichern");
        fc.setInitialDirectory(outputDir.toFile());
        fc.setInitialFileName(editPath != null
            ? editPath.getFileName().toString().replaceAll("\\.\\w+$", "_transkript.txt")
            : "transkript.txt");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text", "*.txt"));
        File f = fc.showSaveDialog(uiStage);
        if (f == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Transkript erstellt mit PodcastStudio + Whisper\n");
            sb.append("=".repeat(60)).append("\n\n");
            for (TranscriptSegment s : segs) {
                sb.append("[").append(formatTimeShort(s.startSec())).append("]  ")
                  .append(s.text()).append("\n");
            }
            Files.writeString(f.toPath(), sb.toString());
            alert(Alert.AlertType.INFORMATION, "Gespeichert",
                  "Transkript gespeichert:\n" + f.getAbsolutePath());
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Fehler", ex.getMessage());
        }
    }

    void saveTranscriptSrt(List<TranscriptSegment> segs) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Untertitel als SRT speichern");
        fc.setInitialDirectory(outputDir.toFile());
        fc.setInitialFileName(editPath != null
            ? editPath.getFileName().toString().replaceAll("\\.\\w+$", ".srt")
            : "untertitel.srt");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SRT", "*.srt"));
        File f = fc.showSaveDialog(uiStage);
        if (f == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            int n = 1;
            for (TranscriptSegment s : segs) {
                sb.append(n++).append("\n")
                  .append(formatSrtTime(s.startSec())).append(" --> ")
                  .append(formatSrtTime(s.endSec())).append("\n")
                  .append(s.text()).append("\n\n");
            }
            Files.writeString(f.toPath(), sb.toString());
            alert(Alert.AlertType.INFORMATION, "Gespeichert",
                  "Untertitel gespeichert:\n" + f.getAbsolutePath());
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Fehler", ex.getMessage());
        }
    }

    String formatSrtTime(double sec) {
        int h = (int)(sec / 3600);
        int m = (int)((sec % 3600) / 60);
        int s = (int)(sec % 60);
        int ms = (int)((sec - Math.floor(sec)) * 1000);
        return String.format("%02d:%02d:%02d,%03d", h, m, s, ms);
    }

    void showWhisperSetupDialog() {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Whisper nicht gefunden");
        a.setHeaderText("Speech-to-Text benötigt Whisper");
        a.setContentText(
            "Um Aufnahmen zu transkribieren, fehlen noch:\n\n" +
            "  • whisper.exe (Speech-to-Text Engine)\n" +
            "  • ein Sprachmodell (ggml-small.bin, ~488 MB)\n\n" +
            "Lösung:\n" +
            "  Führe WHISPER_SETUP.bat im App-Ordner aus.\n" +
            "  Das lädt alles automatisch herunter (~500 MB).\n\n" +
            "Whisper läuft komplett offline und ist gratis.");
        a.showAndWait();
    }


    void openFolder() {
        try { new ProcessBuilder("explorer", outputDir.toString()).start(); }
        catch (Exception ignored) {}
    }

    void showInFolder(Path path) {
        try { new ProcessBuilder("explorer", "/select,",
                                 path.toString()).start(); }
        catch (Exception ignored) {}
    }

    String toTime(double sec) {
        int m = (int)(sec / 60);
        double s = sec % 60;
        return String.format("%02d:%06.3f", m, s);
    }

    void onClose() {
        recording = false;
        backingPlaying.set(false);
        editorPlaying.set(false);
        monitorActive.set(false);
        playStop = true;
        // Alle SourceDataLines (Ausgang) sauber schließen
        for (SourceDataLine l : Arrays.asList(playLine, backingLine, editorLine, monitorLine))
            if (l != null) try { l.stop(); l.close(); } catch (Exception ignored) {}
        // Alle TargetDataLines (Eingang) sauber schließen
        for (TargetDataLine l : Arrays.asList(inputLine, monitorMicLine))
            if (l != null) try { l.stop(); l.close(); } catch (Exception ignored) {}
        Platform.exit();
        System.exit(0);
    }

    // ═══════════════════════════════════════════
    //  UI HELPERS
    // ═══════════════════════════════════════════
    static String bg(String c) { return "-fx-background-color:" + c + ";"; }

    static Label lbl(String txt, int size) {
        Label l = new Label(txt);
        l.setStyle("-fx-font-size:" + size + "px;");
        return l;
    }

    static Label styledLbl(String txt, String color, String font,
                            String size, String weight) {
        Label l = new Label(txt);
        // "italic" ist kein font-weight sondern ein font-style:
        // erkennen und korrekt zuweisen statt einer CSS-Warnung zu erzeugen
        boolean isItalic = "italic".equalsIgnoreCase(weight);
        String w = isItalic ? "normal" : weight;
        String s = isItalic ? "italic" : "normal";
        l.setStyle("-fx-text-fill:" + color + ";" +
                   "-fx-font-family:'" + font + "';" +
                   "-fx-font-size:" + size + "px;" +
                   "-fx-font-weight:" + w + ";" +
                   "-fx-font-style:" + s + ";");
        return l;
    }

    /**
     * Globales Dark-Theme-Stylesheet auf die Scene anwenden.
     * Stylt TabPane-Labels, Scrollbars und CheckBox-Boxen über CSS-Selektoren,
     * weil diese Sub-Komponenten nicht inline gestylt werden können.
     */
    void applyDarkThemeStylesheet(Scene scene) {
        String css =
            // ── TabPane ──
            ".tab-pane > .tab-header-area > .tab-header-background { -fx-background-color:" + SURFACE + "; }" +
            ".tab-pane > .tab-header-area > .headers-region > .tab { -fx-background-color:" + CARD + "; -fx-padding:8 22; -fx-background-radius:6 6 0 0; }" +
            ".tab-pane > .tab-header-area > .headers-region > .tab:selected { -fx-background-color:" + BG + "; }" +
            ".tab-pane > .tab-header-area > .headers-region > .tab > .tab-container > .tab-label { -fx-text-fill:" + MUTED + "; -fx-font-family:'Calibri,Segoe UI,Arial'; -fx-font-size:14px; -fx-font-weight:bold; }" +
            ".tab-pane > .tab-header-area > .headers-region > .tab:selected > .tab-container > .tab-label { -fx-text-fill:" + ACCENT + "; }" +
            ".tab-pane > .tab-header-area > .headers-region > .tab:hover > .tab-container > .tab-label { -fx-text-fill:" + FG + "; }" +
            // Roter Unterstrich beim aktiven Tab
            ".tab-pane > .tab-header-area > .headers-region > .tab:selected .focus-indicator { -fx-border-color:transparent; }" +
            // ── ComboBox-Anzeige (Display-Zelle im Box selbst) ──
            ".combo-box .list-cell { -fx-background-color:" + CARD + "; -fx-text-fill:" + FG + "; -fx-font-family:'Calibri,Segoe UI,Arial'; -fx-font-size:13px; }" +
            ".combo-box .arrow-button { -fx-background-color:" + CARD + "; }" +
            ".combo-box .arrow-button .arrow { -fx-background-color:" + FG + "; }" +
            // ── ComboBox-Popup (Dropdown-Liste) ──
            ".combo-box-popup .list-view { -fx-background-color:" + CARD + "; -fx-border-color:" + BORDER + "; }" +
            ".combo-box-popup .list-view .list-cell { -fx-background-color:" + CARD + "; -fx-text-fill:" + FG + "; -fx-font-family:'Calibri,Segoe UI,Arial'; -fx-font-size:13px; -fx-padding:6 10; }" +
            ".combo-box-popup .list-view .list-cell:hover { -fx-background-color:" + BORDER + "; }" +
            ".combo-box-popup .list-view .list-cell:selected { -fx-background-color:" + ACCENT + "; -fx-text-fill:white; }" +
            // ── Scrollbars ──
            ".scroll-bar { -fx-background-color:" + SURFACE + "; }" +
            ".scroll-bar .thumb { -fx-background-color:" + BORDER + "; -fx-background-radius:6; }" +
            ".scroll-bar .thumb:hover { -fx-background-color:" + MUTED + "; }" +
            ".scroll-bar .track { -fx-background-color:" + BG + "; -fx-border-color:transparent; }" +
            ".scroll-bar .increment-button, .scroll-bar .decrement-button { -fx-background-color:" + SURFACE + "; -fx-padding:0; }" +
            ".scroll-bar .increment-arrow, .scroll-bar .decrement-arrow { -fx-shape:''; -fx-padding:0; }" +
            ".scroll-pane > .viewport { -fx-background-color:transparent; }" +
            // ── CheckBox ──
            ".check-box .box { -fx-background-color:" + CARD + "; -fx-border-color:" + BORDER + "; -fx-border-radius:3; -fx-background-radius:3; }" +
            ".check-box:selected .box { -fx-background-color:" + ACCENT + "; -fx-border-color:" + ACCENT + "; }" +
            ".check-box:selected .mark { -fx-background-color:white; }" +
            // ── ContextMenu (Alert / Dialog) ──
            ".context-menu { -fx-background-color:" + CARD + "; -fx-border-color:" + BORDER + "; }";

        try {
            String dataUri = "data:text/css," +
                java.net.URLEncoder.encode(css, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            scene.getStylesheets().add(dataUri);
        } catch (Exception ignored) {}
    }

    /** Tooltip im Dark-Theme-Stil. */
    static Tooltip styledTooltip(String text) {
        Tooltip t = new Tooltip(text);
        t.setStyle(
            "-fx-background-color:" + CARD + ";" +
            "-fx-text-fill:" + FG + ";" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';" +
            "-fx-font-size:12px;" +
            "-fx-border-color:" + BORDER + ";" +
            "-fx-border-width:1;" +
            "-fx-background-radius:6;" +
            "-fx-padding:6 10;");
        return t;
    }

    static Button accentBtn(String txt) {
        Button b = new Button(txt);
        b.setStyle(
            "-fx-background-color:" + ACCENT + ";-fx-text-fill:white;" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:13px;" +
            "-fx-font-weight:bold;-fx-padding:12 24;-fx-cursor:hand;" +
            "-fx-background-radius:6;-fx-border-radius:6;");
        return b;
    }

    static Button secBtn(String txt) {
        Button b = new Button(txt);
        b.setStyle(
            "-fx-background-color:" + CARD + ";-fx-text-fill:" + FG + ";" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:12px;" +
            "-fx-padding:10 16;-fx-cursor:hand;-fx-border-color:" + BORDER + ";" +
            "-fx-border-radius:6;-fx-background-radius:6;");
        return b;
    }

    /** Aufnahme-Steuerbutton (Pause, Stopp, Marker) – auch im disabled-State gut lesbar. */
    static Button controlBtn(String txt) {
        Button b = new Button(txt);
        // Heller als secBtn + erkennbar auch wenn disabled (bleibt opazit)
        String style =
            "-fx-background-color:" + CARD + ";-fx-text-fill:" + FG + ";" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:14px;" +
            "-fx-font-weight:bold;-fx-padding:11 18;-fx-cursor:hand;" +
            "-fx-border-color:" + BORDER + ";-fx-border-width:1.5;" +
            "-fx-border-radius:6;-fx-background-radius:6;-fx-opacity:1.0;";
        b.setStyle(style);
        // Hover-Effekt
        b.setOnMouseEntered(e -> {
            if (!b.isDisabled())
                b.setStyle(style + "-fx-background-color:" + BORDER + ";");
        });
        b.setOnMouseExited(e -> b.setStyle(style));
        return b;
    }

    static Button miniBtn(String txt) {
        Button b = new Button(txt);
        b.setStyle(
            "-fx-background-color:" + CARD + ";-fx-text-fill:" + FG + ";" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:12px;" +
            "-fx-padding:6 12;-fx-cursor:hand;" +
            "-fx-border-color:" + BORDER + ";-fx-border-radius:4;" +
            "-fx-background-radius:4;");
        return b;
    }

    static Button ghostBtn(String txt) {
        Button b = new Button(txt);
        b.setStyle(
            "-fx-background-color:transparent;-fx-text-fill:" + MUTED + ";" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:12px;" +
            "-fx-padding:9 13;-fx-cursor:hand;");
        return b;
    }

    static Button greenBtn(String txt) {
        Button b = new Button(txt);
        b.setStyle(
            "-fx-background-color:" + GREEN + ";-fx-text-fill:white;" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:12px;" +
            "-fx-font-weight:bold;-fx-padding:10 16;-fx-cursor:hand;" +
            "-fx-background-radius:6;");
        return b;
    }

    static Button blueBtn(String txt) {
        Button b = new Button(txt);
        b.setStyle(
            "-fx-background-color:" + BLUE + ";-fx-text-fill:white;" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:12px;" +
            "-fx-font-weight:bold;-fx-padding:10 16;-fx-cursor:hand;" +
            "-fx-background-radius:6;");
        return b;
    }

    static ComboBox<String> combo() {
        ComboBox<String> c = new ComboBox<>();
        c.setPrefWidth(100);
        c.setStyle(
            "-fx-background-color:" + CARD + ";-fx-text-fill:" + FG + ";" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';-fx-font-size:13px;" +
            "-fx-font-weight:normal;" +
            "-fx-border-color:" + BORDER + ";-fx-border-width:1.5;" +
            "-fx-border-radius:6;-fx-background-radius:6;-fx-padding:4 4;" +
            "-fx-prompt-text-fill:" + FG + ";");
        return c;
    }

    static TextField textField(String prompt) {
        TextField t = new TextField();
        t.setPromptText(prompt);
        t.setStyle(
            "-fx-background-color:" + CARD + ";-fx-text-fill:" + FG + ";" +
            "-fx-border-color:" + BORDER + ";-fx-border-radius:6;" +
            "-fx-background-radius:6;-fx-padding:10 12;-fx-font-size:13px;" +
            "-fx-font-family:'Calibri,Segoe UI,Arial';");
        return t;
    }

    static CheckBox checkBox(String txt) {
        CheckBox c = new CheckBox(txt);
        c.setStyle(
            "-fx-text-fill:" + FG + ";-fx-font-family:'Calibri,Segoe UI,Arial';" +
            "-fx-font-size:13px;-fx-font-weight:bold;-fx-cursor:hand;");
        return c;
    }

    static Node divider() {
        Region line = new Region();
        line.setMinHeight(1);
        line.setPrefHeight(1);
        line.setMaxHeight(1);
        line.setMaxWidth(Double.MAX_VALUE);
        line.setStyle("-fx-background-color:" + BORDER + ";");
        VBox box = new VBox(line);
        box.setPadding(new Insets(6, 0, 8, 0));
        box.setFillWidth(true);
        return box;
    }

    static Node pad(Node node, double top, double right, double bottom, double left) {
        VBox box = new VBox(node);
        box.setPadding(new Insets(top, right, bottom, left));
        return box;
    }

    void alert(Alert.AlertType type, String title, String msg) {
        Alert a = new Alert(type);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    boolean confirm(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(msg);
        return a.showAndWait().filter(r -> r == ButtonType.OK).isPresent();
    }

    record Rec(Path path, String title, double duration,
               long sizeKb, int index) {}

    record Marker(double timeSec, String label) {}

    public static void main(String[] args) {
        launch(args);
    }
}
