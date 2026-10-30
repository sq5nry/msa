package msa.gui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import msa.model.DisplaySettings;
import msa.model.DisplaySettings.AxisRange;
import msa.replay.SweepRecorder;
import msa.sweep.MsaMode;
import msa.sweep.SweepEngine;
import msa.sweep.SweepSettings;
import msa.sweep.Sweeper;
import msa.vna.TransmissionCal;
import msa.trace.AxisScale;
import msa.trace.DataFile;
import msa.trace.GraphData;
import msa.trace.Markers;
import msa.trace.SweepData;

/**
 * The analyser's graph window ([CreateGraphWindow]): menus, graph, marker controls and the sweep
 * buttons. Ported: the SA modes and SNA/VNA transmission with its operating calibration; not yet: VNA
 * reflection, filter analysis and other functions, test setups, appearance/colour settings and the hardware
 * configuration manager.
 */
public final class MainWindow implements GraphView.Actions {

    private final Session session;
    private final GraphState state = new GraphState(200);
    private final Stage stage;
    private final GraphView graph;
    private final Label status = new Label();
    private final ComboBox<String> markerBox = new ComboBox<>();
    private final TextField markerFreq = new TextField();
    private final Button restartBtn = new Button("Running"), continueBtn = new Button("Halt"),
            oneStepBtn = new Button("HaltAtEnd"), stepLeftBtn = new Button("Step Left"), redrawBtn = new Button("Redraw");
    private final RadioMenuItem modeSa = new RadioMenuItem("Spectrum Analyzer"),
            modeSaTg = new RadioMenuItem("Spectrum Analyzer with TG"), modeTrans = new RadioMenuItem("VNA Transmission"),
            modeRefl = new RadioMenuItem(MsaMode.REFLECTION.title);
    private final RadioMenuItem refBand = new RadioMenuItem(TransmissionCal.Level.BAND.title),
            refBase = new RadioMenuItem(TransmissionCal.Level.BASE.title), refNone = new RadioMenuItem(TransmissionCal.Level.NONE.title);
    private final Menu calMenu = new Menu("Operating Cal");
    private final CheckMenuItem recordItem = new CheckMenuItem("Record Sweeps...");

    private SweepEngine.State engineState = SweepEngine.State.IDLE;
    private String engineReason = "";
    private long lastSweepNanos;
    private final CheckMenuItem sweepTimeItem = new CheckMenuItem("Display Sweep Time");
    private final CheckMenuItem refreshItem = new CheckMenuItem("Refresh Screen Each Scan");
    private SweepPanel sweepPanel;
    private SweepRecorder recorder;
    private VariablesWindow variables;
    private Path lastFolder;

    public MainWindow(Session session, Stage stage) {
        this.session = session;
        this.stage = stage;
        this.graph = new GraphView(session, state, this, this::topLine);
        this.lastFolder = session.msaInfo();
        DisplaySettings d = session.display();
        state.setTraceWidth(1, 1);
        state.setTraceWidth(2, 1);
        if (d.y2Data() == DisplaySettings.DATA_NONE && d.y1Data() == DisplaySettings.DATA_NONE)
            session.applyDisplay(d.withGraphData(DisplaySettings.DATA_NONE, DisplaySettings.DATA_DBM));
    }

    public void show() {
        BorderPane root = new BorderPane();
        root.setTop(menus());
        root.setCenter(graph);
        sweepPanel = new SweepPanel(session, sweepHost());
        VBox bottom = new VBox(controls(), statusBar());
        root.setBottom(bottom);
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(root, Math.min(1640, screen.getWidth() - 40), Math.min(800, screen.getHeight() - 60));
        stage.setScene(scene);
        restoreWindowGeometry();
        stage.setTitle(title());
        // Platform.exit() must not run inside the close-request handler (JavaFX then fails with "Not on FX
        // application thread" while hiding the window), so clean up once the window is gone.
        stage.setOnHidden(e -> shutdown());
        stage.show();

        state.store.setCorrectionProvider(session::correctionFor);
        state.store.addChangeListener((cur, index) -> sweepDataChanged(index));
        FxBatcher toUi = new FxBatcher();   // one executor for both listeners keeps their events in order
        session.engine().addListener(state.store, toUi);
        session.engine().addListener(new SweepEngine.Listener() {
            @Override
            public void stateChanged(SweepEngine.State s, String reason) { engineStateChanged(s, reason); }

            @Override
            public void sweepCompleted(int n, long ns) { sweepCompletedOnUi(ns); }

            @Override
            public void error(Throwable t) { message("Error: " + t.getMessage()); }
        }, toUi);
        graph.setBeforeDraw(this::updateDerived);
        restoreState();
        session.start();
        updateSweepTime(true);
        // remember the window state for the next run (written only when something changed)
        javafx.animation.Timeline autosave = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(2), e -> saveState()));
        autosave.setCycleCount(javafx.animation.Animation.INDEFINITE);
        autosave.play();
        buttonsFor(SweepEngine.State.RUNNING);
        graph.requestFocus();
        if (!session.warnings().isEmpty())
            info("Prefs.txt", "Some saved settings could not be used:\n\n" + String.join("\n", session.warnings()));
    }

    private String title() { return "MSA - " + modeTitle() + " - " + session.source().label(); }

    private String modeTitle() {
        if (session.mode() != MsaMode.SA) return session.mode().title;
        return session.trackingMode() ? "Spectrum Analyzer with TG" : "Spectrum Analyzer";
    }

    private boolean shutDown;

    private void shutdown() {
        if (shutDown) return;
        shutDown = true;
        stopRecording();
        saveState();
        session.close();
        Platform.runLater(Platform::exit);
    }

    /** Markers, marker options and view choices from the last run (the settings were restored by Session). */
    private void restoreState() {
        session.restored().ifPresent(r -> {
            state.restoreMarkers(r.markerFreqs());
            state.setShowMarkers(r.showMarkers());
            state.markers.setLrMode(r.lrMode());
            state.setTraceWidth(1, r.traceWidth1());
            state.setTraceWidth(2, r.traceWidth2());
            sweepTimeItem.setSelected(r.displaySweepTime());
            if (r.selected() != null) {
                state.select(r.selected());
                markerBox.getSelectionModel().select(r.selected().label);
            }
        });
        if (!session.remembersSettings()) message("rememberSettings=false: started from Prefs.txt");
    }

    /** Last position and size while the window was neither maximised nor minimised. */
    private double normalX = Double.NaN, normalY, normalW, normalH;

    /**
     * Puts the window where it was last time, if that place is still on a screen (a monitor may have been
     * unplugged); otherwise the default size, centred. Also starts tracking the normal bounds.
     */
    private void restoreWindowGeometry() {
        session.restored().map(SessionState::window).ifPresent(w -> {
            boolean visible = !javafx.stage.Screen.getScreensForRectangle(w.x(), w.y(), w.width(), w.height()).isEmpty();
            if (!visible) return;
            stage.setX(w.x());
            stage.setY(w.y());
            stage.setWidth(w.width());
            stage.setHeight(w.height());
            normalX = w.x();
            normalY = w.y();
            normalW = w.width();
            normalH = w.height();
            stage.setMaximized(w.maximized());
        });
        javafx.beans.InvalidationListener track = o -> {
            if (stage.isMaximized() || stage.isIconified() || stage.isFullScreen()) return;
            normalX = stage.getX();
            normalY = stage.getY();
            normalW = stage.getWidth();
            normalH = stage.getHeight();
        };
        stage.xProperty().addListener(track);
        stage.yProperty().addListener(track);
        stage.widthProperty().addListener(track);
        stage.heightProperty().addListener(track);
    }

    private SessionState.WindowGeometry windowGeometry() {
        if (Double.isNaN(normalX) || Double.isNaN(normalW) || normalW <= 0) return null;
        return new SessionState.WindowGeometry(normalX, normalY, normalW, normalH, stage.isMaximized());
    }

    private void saveState() {
        try {
            session.saveState(state, sweepTimeItem.isSelected(), windowGeometry());
        } catch (IOException | RuntimeException e) {
            message("Could not save the window state: " + e.getMessage());
        }
    }

    // ================================================================ menus

    private MenuBar menus() {
        MenuItem saveImage = item("Save Image...", e -> saveImage());
        MenuItem loadPrefs = item("Load Prefs...", e -> loadPrefs());
        MenuItem savePrefs = item("Save Prefs", e -> savePrefs(false));
        savePrefs.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        MenuItem savePrefsAs = item("Save Prefs As...", e -> savePrefs(true));
        MenuItem saveData = item("Save Data...", e -> saveData());
        recordItem.setOnAction(e -> toggleRecording());
        MenuItem exit = item("Exit", e -> stage.close());
        Menu file = new Menu("File", null, saveImage, new SeparatorMenuItem(), loadPrefs, savePrefs, savePrefsAs,
                new SeparatorMenuItem(), saveData, recordItem, new SeparatorMenuItem(), exit);

        MenuItem copy = item("Copy Image", e -> copyImage());
        copy.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN));
        Menu edit = new Menu("Edit", null, copy);

        MenuItem sweepParams = item("Sweep Parameters", e -> openSweepParameters());
        sweepParams.setAccelerator(new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN));
        Menu options = new Menu("Options", null,
                item("Markers", e -> openMarkerOptions()),
                sweepParams,
                item("Y1 Axis (left)", e -> openAxisDialog(1)),
                item("Y2 Axis (right)", e -> openAxisDialog(2)),
                item("Show Variables", e -> showVariables()),
                item("Reference Lines", e -> openReferenceDialog()),
                new SeparatorMenuItem(), sweepTimeItem, refreshItem);
        sweepTimeItem.setOnAction(e -> graph.invalidate());
        refreshItem.setSelected(session.display().refreshEachScan());
        refreshItem.setOnAction(e -> session.applyDisplay(session.display().withRefreshEachScan(refreshItem.isSelected())));

        MenuItem s21 = item("S21 Parameters", e -> DataWindow.s21Data(stage, session, state).show());
        Menu data = new Menu("Data", null,
                item("Graph Data", e -> DataWindow.graphData(stage, session, state).show()),
                item("Input Data", e -> DataWindow.inputData(stage, session, state).show()), s21);
        data.setOnShowing(e -> s21.setDisable(!session.mode().transmission()));

        MenuBar bar = new MenuBar(file, edit, options, data);
        Menu setup = new Menu("Setup", null, item("Autotest...", e -> openAutotest()));
        if (session.hasVna()) setup.getItems().add(item("PDM Calibration...", e -> openPdmCalibration()));
        bar.getMenus().add(2, setup);
        if (session.hasTrackingGenerator()) {
            // Operating Cal: transmission reference, or reflection OSL calibration
            MenuItem perform = item("Perform Cal...", e -> openTransmissionCal(false));
            MenuItem update = item("Update Cal...", e -> openTransmissionCal(true));
            ToggleGroup rg = new ToggleGroup();
            for (RadioMenuItem r : List.of(refBand, refBase, refNone)) r.setToggleGroup(rg);
            refBand.setOnAction(e -> setReference(TransmissionCal.Level.BAND));
            refBase.setOnAction(e -> setReference(TransmissionCal.Level.BASE));
            refNone.setOnAction(e -> setReference(TransmissionCal.Level.NONE));
            Menu refTo = new Menu("Reference To", null, refBand, refBase, refNone);
            calMenu.getItems().addAll(perform, refTo, update);
            calMenu.setOnShowing(e -> {
                boolean trans = session.mode().networkAnalyser();
                for (MenuItem m : List.of(perform, refTo, update)) m.setDisable(!trans);
                syncReferenceMenu();
            });
            bar.getMenus().add(calMenu);

            ToggleGroup g = new ToggleGroup();
            modeSa.setToggleGroup(g);
            modeSaTg.setToggleGroup(g);
            modeTrans.setToggleGroup(g);
            modeRefl.setToggleGroup(g);
            modeTrans.setText(session.transmissionMode().title);
            modeSa.setOnAction(e -> changeMode(MsaMode.SA, false));
            modeSaTg.setOnAction(e -> changeMode(MsaMode.SA, true));
            modeTrans.setOnAction(e -> changeMode(session.transmissionMode(), true));
            modeRefl.setOnAction(e -> changeMode(MsaMode.REFLECTION, true));
            Menu mode = new Menu("Mode", null, modeSa, modeSaTg, modeTrans);
            if (session.hasVna()) mode.getItems().add(modeRefl);
            if (session.source() instanceof Session.Demo) {
                Menu dut = new Menu("Demo Test Fixture");
                mode.getItems().addAll(new SeparatorMenuItem(), dut);
                mode.setOnShowing(e -> {
                    // what the simulated fixture can hold depends on the mode: two-ports or one-ports
                    dut.getItems().clear();
                    ToggleGroup dg = new ToggleGroup();
                    boolean refl = session.mode() == MsaMode.REFLECTION;
                    for (msa.replay.DemoSignals.Dut d : msa.replay.DemoSignals.Dut.values()) {
                        if (d.onePort != refl) continue;
                        RadioMenuItem r = new RadioMenuItem(d.title);
                        r.setToggleGroup(dg);
                        r.setSelected(d == session.demoDut());
                        r.setOnAction(ev -> session.setDemoDut(d));
                        dut.getItems().add(r);
                    }
                    dut.setDisable(session.mode() == MsaMode.SA);
                });
            }
            bar.getMenus().add(mode);
            syncModeMenu();
        }
        return bar;
    }

    private void syncReferenceMenu() {
        switch (session.vna().calLevel()) {
            case BAND -> refBand.setSelected(true);
            case BASE -> refBase.setSelected(true);
            case NONE -> refNone.setSelected(true);
        }
    }

    /** [menuVNARef]: applies at once to the data on the screen (BASIC: at the next restart). */
    private void setReference(TransmissionCal.Level l) {
        session.setCalLevel(l);
        calibrationChanged();
        TransmissionCal.Level applied = session.appliedCalLevel(session.sweep());
        message(applied == l ? "Reference: " + l.title
                : "Reference: " + l.title + " wanted, but " + (applied == TransmissionCal.Level.NONE ? "none" : applied.title)
                + " applies (" + (l == TransmissionCal.Level.BAND ? "no band calibration for this sweep" : "no base calibration for this path") + ")");
    }

    /** The calibration, reference choice or plane extension changed: recalculate the data shown. */
    void calibrationChanged() {
        state.store.refreshCorrections();
        syncReferenceMenu();
        graph.invalidate();
        markersChanged();
    }

    void openTransmissionCal(boolean update) {
        if (session.mode() == MsaMode.REFLECTION) {
            if (update) {
                msa.vna.OslCal band = session.reflectionCal().band();
                if (session.vna().calLevel() != TransmissionCal.Level.BAND || band == null || !band.matches(session.sweep())) {
                    info("Update Cal", "Must have current band calibration to update. Cannot update base cal.");
                    return;
                }
            }
            new ReflectionCalDialog(stage, session, state.store, new ReflectionCalDialog.Host() {
                @Override public void calibrationChanged() { MainWindow.this.calibrationChanged(); }
                @Override public void message(String text) { MainWindow.this.message(text); }
            }, update).show();
            return;
        }
        if (!session.mode().transmission()) {
            info("Operating Cal", "Calibration is for the network-analyser modes.");
            return;
        }
        if (update) {
            msa.vna.LineCal band = session.transmissionCal().band();
            if (session.vna().calLevel() != TransmissionCal.Level.BAND || band == null || !band.matches(session.sweep())) {
                info("Update Cal", "Must have current band calibration to update. Cannot update base cal.");
                return;
            }
        }
        new TransmissionCalDialog(stage, session, state.store, new TransmissionCalDialog.Host() {
            @Override public void calibrationChanged() { MainWindow.this.calibrationChanged(); }
            @Override public void message(String text) { MainWindow.this.message(text); }
        }, update).show();
    }

    void openAutotest() {
        new AutotestWindow(stage, session, new AutotestWindow.Host() {
            private java.util.Map<Markers.Id, Double> markers;
            private msa.replay.DemoSignals.Dut dut;
            private boolean wasHalted;

            @Override
            public void runStarting() {
                markers = state.markerFrequencies();
                wasHalted = engineState == SweepEngine.State.HALTED;
                dut = session.demoDut();
                // the demo's TG tests expect a cable from TG to input
                if (session.source() instanceof Session.Demo) session.setDemoDut(msa.replay.DemoSignals.Dut.THROUGH);
                message("Autotest running: the graph shows the test sweeps");
            }

            @Override
            public void runFinished() {
                if (dut != null) session.setDemoDut(dut);
                session.engine().apply(session.sweep());
                restart();
                // the tests replaced the halted trace: sweep once with the user's settings, then halt again
                if (wasHalted) session.engine().haltAtEnd();
                if (markers != null) state.restoreMarkers(markers);
                markersChanged();
                graph.invalidate();
                message(wasHalted ? "Autotest finished: your sweep is restored; it halts after one sweep, as before"
                        : "Autotest finished: your sweep is restored");
            }

            @Override
            public void message(String text) { MainWindow.this.message(text); }

            @Override
            public void wiring(msa.autotest.AutotestModel.Setup s) {
                // the demo's simulated cable goes where the prompt said
                if (session.source() instanceof Session.Demo)
                    session.setDemoDut(s == msa.autotest.AutotestModel.Setup.TG_REFLECT
                            ? msa.replay.DemoSignals.Dut.THROUGH_R : msa.replay.DemoSignals.Dut.THROUGH);
            }
        }).show();
    }

    void openPdmCalibration() {
        new PdmCalDialog(stage, session, () -> engineState, this::message).show();
    }

    private static MenuItem item(String text, javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        MenuItem m = new MenuItem(text);
        m.setOnAction(h);
        return m;
    }

    // ================================================================ controls

    private HBox controls() {
        markerBox.getItems().add("None");
        for (Markers.Id id : Markers.Id.values()) if (id != Markers.Id.HALT) markerBox.getItems().add(id.label);
        markerBox.getSelectionModel().select(0);
        markerBox.setOnAction(e -> {
            String s = markerBox.getValue();
            state.select(s == null || s.equals("None") ? null : idOf(s));
            showSelectedMarker();
            graph.invalidate();
        });
        markerBox.setPrefWidth(92);
        markerBox.setMinWidth(Region.USE_PREF_SIZE);
        Button delete = small("Delete", e -> { state.deleteSelected(); markersChanged(); });
        Button clear = small("Clear Marks", e -> { state.markers.clear(); markersChanged(); });
        Button dec = small("-", e -> { state.stepSelected(-1, points()); markersChanged(); });
        Button inc = small("+", e -> { state.stepSelected(1, points()); markersChanged(); });
        markerFreq.setPrefColumnCount(8);
        markerFreq.setMinWidth(Region.USE_PREF_SIZE);
        markerFreq.setOnAction(e -> enterMarkerFrequency());
        Button enter = small("Enter", e -> enterMarkerFrequency());
        Button expand = small("Expand L-R", e -> expandLR());
        Button toCenter = small("Mark->Cent", e -> markToCenter());

        restartBtn.setOnAction(e -> {
            if (engineState == SweepEngine.State.RUNNING) session.engine().halt();
            else if (engineState == SweepEngine.State.ERROR) session.engine().reconnect();
            else restart();
        });
        continueBtn.setOnAction(e -> {
            if (engineState == SweepEngine.State.RUNNING) session.engine().halt();
            else resume();
        });
        oneStepBtn.setOnAction(e -> {
            if (engineState == SweepEngine.State.RUNNING) session.engine().haltAtEnd();
            else session.engine().stepRight();
        });
        stepLeftBtn.setOnAction(e -> session.engine().stepLeft());
        redrawBtn.setOnAction(e -> redraw());
        for (Button b : List.of(restartBtn, continueBtn, oneStepBtn, stepLeftBtn, redrawBtn)) {
            b.setStyle("-fx-font-weight: bold;");
            b.setPrefWidth(98);
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        for (Button b : List.of(delete, clear, dec, inc, enter, expand, toCenter)) {
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setMaxWidth(Double.MAX_VALUE);
        }
        // markers in three rows: select/delete, -freq+/enter, clear/mark->cent/expand
        GridPane marks = new GridPane();
        marks.setHgap(4);
        marks.setVgap(3);
        Label markLabel = new Label("Marker");
        markLabel.setMinWidth(Region.USE_PREF_SIZE);
        marks.add(new HBox(4, markLabel, markerBox, delete), 0, 0, 3, 1);
        marks.add(new HBox(2, dec, markerFreq, inc, enter), 0, 1, 3, 1);
        marks.add(clear, 0, 2);
        marks.add(toCenter, 1, 2);
        marks.add(expand, 2, 2);
        ((HBox) marks.getChildren().get(0)).setAlignment(Pos.CENTER_LEFT);
        ((HBox) marks.getChildren().get(1)).setAlignment(Pos.CENTER_LEFT);
        GridPane sweep = new GridPane();
        sweep.setHgap(4);
        sweep.setVgap(3);
        sweep.add(continueBtn, 0, 0);
        sweep.add(redrawBtn, 1, 0);
        sweep.add(restartBtn, 0, 1);
        sweep.add(oneStepBtn, 1, 1);
        sweep.add(stepLeftBtn, 1, 2);
        // three sections: markers | sweep parameters | sweep buttons
        Region spacer1 = new Region(), spacer2 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);
        HBox.setHgrow(spacer2, Priority.ALWAYS);
        HBox box = new HBox(8, marks, spacer1, new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL),
                sweepPanel, new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL), spacer2, sweep);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(4, 6, 2, 6));
        return box;
    }

    private static Button small(String text, javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        Button b = new Button(text);
        b.setOnAction(h);
        return b;
    }

    private HBox statusBar() {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        sweepTime.setMinWidth(Region.USE_PREF_SIZE);
        sweepTime.setTooltip(new javafx.scene.control.Tooltip(
                "Time for one sweep, left to right: points x (wait + 1 ms after each synthesiser step + USB/ADC time)."
                        + " The USB/ADC time is measured on each completed sweep."));
        HBox b = new HBox(12, status, gap, sweepTime);
        b.setPadding(new Insets(2, 8, 4, 8));
        status.setMinHeight(18);
        return b;
    }

    private final Label sweepTime = new Label();
    /** Measured USB/ADC time per point, by ADC averaging (samples), from completed sweeps. */
    private final java.util.Map<Integer, Double> overheadMs = new java.util.HashMap<>();
    private long sweepTimeShownAt;
    private int sweepTimedNumber = -1, sweepTimedStartPoints;
    private long sweepTimedStart;

    /**
     * Bottom bar, right: "Sweep ≈ 8.40 s (4,201 points × 2.0 ms)", the last measured sweep and, while
     * sweeping, progress and time left. Limited to a few updates per second.
     */
    private void updateSweepTime(boolean force) {
        long now = System.nanoTime();
        if (!force && now - sweepTimeShownAt < 250_000_000L) return;
        sweepTimeShownAt = now;
        SweepSettings s = session.sweep();
        double ov = overheadMs.getOrDefault(s.samples(), msa.sweep.SweepTime.DEFAULT_OVERHEAD_MS);
        double est = msa.sweep.SweepTime.seconds(s, ov);
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "Sweep ≈ %s (%,d points × %.1f ms)",
                msa.sweep.SweepTime.format(est), s.points(), msa.sweep.SweepTime.perPointMs(s, ov)));
        if (!overheadMs.containsKey(s.samples())) sb.append(" estimated");
        if (lastSweepNanos > 0) sb.append("   last ").append(msa.sweep.SweepTime.format(lastSweepNanos / 1e9));
        SweepData cur = state.store.current();
        if (engineState == SweepEngine.State.RUNNING && cur != null && !cur.complete() && cur.size() == s.points()) {
            int done = cur.measuredCount(), total = cur.size();
            if (cur.number() != sweepTimedNumber) {       // a new sweep: time it from here
                sweepTimedNumber = cur.number();
                sweepTimedStart = now;
                sweepTimedStartPoints = done;
            }
            double perPointMs = msa.sweep.SweepTime.perPointMs(s, ov);
            double elapsed = (now - sweepTimedStart) / 1e9;
            int measuredHere = done - sweepTimedStartPoints;
            if (measuredHere >= 20 && elapsed >= 0.5) perPointMs = elapsed * 1000 / measuredHere;   // actual rate so far
            double left = (total - done) * perPointMs / 1000;
            sb.append(String.format(Locale.ROOT, "   %d %%, %s left", 100L * done / total, msa.sweep.SweepTime.format(left)));
        }
        sweepTime.setText(sb.toString());
    }

    private static Markers.Id idOf(String label) {
        for (Markers.Id id : Markers.Id.values()) if (id.label.equals(label)) return id;
        return null;
    }

    private int points() {
        SweepData d = state.shown();
        return d == null ? session.sweep().points() : d.size();
    }

    // ================================================================ engine events

    private void engineStateChanged(SweepEngine.State s, String reason) {
        engineState = s;
        engineReason = reason == null ? "" : reason;
        buttonsFor(s);
        if (s == SweepEngine.State.HALTED) {
            int last = session.engine().snapshot().lastIndex();
            if (last >= 0) state.markers.place(Markers.Id.HALT, last);
        } else if (s == SweepEngine.State.RUNNING) {
            state.markers.remove(Markers.Id.HALT);
        }
        updateSweepTime(true);
        if (s == SweepEngine.State.ERROR) message("MSA not available: " + engineReason + " (retrying every second)");
        else if (s == SweepEngine.State.RUNNING) stepWarning();
        graph.invalidate();
    }

    /** [DisplayButtonsForRunning] / [DisplayButtonsForHalted]. */
    private void buttonsFor(SweepEngine.State s) {
        boolean running = s == SweepEngine.State.RUNNING;
        restartBtn.setText(running ? "Running" : "Restart");
        continueBtn.setText(running ? "Halt" : "Continue");
        oneStepBtn.setText(running ? "HaltAtEnd" : "One Step");
        stepLeftBtn.setDisable(running || s == SweepEngine.State.ERROR);
        oneStepBtn.setDisable(s == SweepEngine.State.ERROR);
        continueBtn.setDisable(s == SweepEngine.State.ERROR);
        redrawBtn.setVisible(!running);
    }

    private boolean derivedDirty;

    /**
     * Called for every measured point, so it must stay cheap: the work that looks at the whole sweep
     * (peak markers, marker box, Variables window) is done once per frame in {@link #updateDerived}.
     */
    private void sweepDataChanged(int index) {
        if (index < 0 && sweepPanel != null) sweepPanel.refresh();   // a sweep started or ended: show what is in use
        if (index < 0) state.followAxis(state.store.current());     // markers stay on their frequencies
        derivedDirty = true;
        graph.invalidate();
    }

    /** Once per frame before drawing, if data arrived: P+/P- and relative L/R, marker box, Variables. */
    private void updateDerived() {
        if (!derivedDirty) return;
        derivedDirty = false;
        SweepData d = state.shown();
        if (d != null) {
            state.markers.update(d, GraphData.ofCode(GraphView.primary(session.display()) == 1
                    ? session.display().y1Data() : session.display().y2Data()));
            showSelectedMarker();
        }
        if (variables != null) variables.refresh();
        updateSweepTime(false);
    }

    private void sweepCompletedOnUi(long ns) {
        lastSweepNanos = ns;
        SweepSettings done = state.store.settings();
        if (done != null) overheadMs.put(done.samples(), msa.sweep.SweepTime.overheadFrom(done, ns));
        updateSweepTime(true);
        DisplaySettings d = session.display();
        SweepData last = state.store.lastComplete();
        if (last != null && (d.autoscaleY1() || d.autoscaleY2())) {
            if (d.autoscaleY1()) d = autoscaled(d, 1, last);
            if (d.autoscaleY2()) d = autoscaled(d, 2, last);
            session.applyDisplay(d);
        }
        graph.invalidate();
    }

    private DisplaySettings autoscaled(DisplaySettings d, int axis, SweepData data) {
        GraphData g = GraphData.ofCode(axis == 1 ? d.y1Data() : d.y2Data());
        AxisRange r = AxisScale.autoscale(data, g);
        if (r == null) return d;
        return axis == 1 ? d.withY1Axis(r) : d.withY2Axis(r);
    }

    private static final String STEP_WARNING = "Frequency step size exceeds RBW; signals may be missed.";

    /** STEP_WARNING in plain SA mode only, as in BASIC; cleared when it no longer applies. */
    private void stepWarning() {
        SweepSettings s = session.sweep();
        double rbwMHz = session.config().filter(s.path()).bwKHz() / 1000;
        boolean warn = !session.trackingMode() && (s.stopMHz() - s.startMHz()) / s.steps() > rbwMHz;
        if (warn) message(STEP_WARNING);
        else if (STEP_WARNING.equals(status.getText())) message("");
    }

    private String topLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(switch (engineState) {
            case RUNNING -> "Running";
            case HALTED -> "Halted";
            case IDLE -> "Starting";
            case ERROR -> "No MSA: " + engineReason;
            case CLOSED -> "Closed";
        });
        sb.append("   ").append(session.source().label());
        SweepData cur = state.store.current();
        if (cur != null) sb.append("   sweep ").append(cur.number());
        if (sweepTimeItem.isSelected() && lastSweepNanos > 0)
            sb.append(String.format(Locale.ROOT, "   Sweep Time=%.2f sec.", lastSweepNanos / 1e9));
        if (session.display().refreshEachScan()) { /* drawn every point anyway */ }
        return sb.toString();
    }

    // ================================================================ sweep buttons

    private void restart() {
        state.markers.remove(Markers.Id.HALT);
        state.clearStick();
        session.engine().restart();
    }

    private void resume() {
        state.markers.remove(Markers.Id.HALT);
        message("");
        session.engine().resume();
    }

    /** [btnRedraw]: drop the Halt marker and stick traces, redraw. */
    private void redraw() {
        state.markers.remove(Markers.Id.HALT);
        state.clearStick();
        graph.invalidate();
    }

    // ================================================================ markers

    @Override
    public void markersChanged() {
        SweepData d = state.shown();
        if (d != null) state.markers.update(d, GraphData.ofCode(GraphView.primary(session.display()) == 1
                ? session.display().y1Data() : session.display().y2Data()));
        showSelectedMarker();
        graph.invalidate();
        if (variables != null) variables.refresh();
    }

    private void showSelectedMarker() {
        if (markerFreq.isFocused()) return;
        markerFreq.setText(state.selectedFrequency(state.shown()).map(Fmt::mhz).orElse(""));
    }

    private void enterMarkerFrequency() {
        try {
            double f = Double.parseDouble(markerFreq.getText().trim());
            Optional<Double> placed = state.enterFrequency(f, state.shown());
            if (placed.isEmpty()) {
                message(state.selected().isEmpty() ? "No marker is selected." : "P+ and P- are placed automatically.");
                return;
            }
            markerFreq.setText(Fmt.mhz(placed.get()));
            graph.requestFocus();
            markersChanged();
        } catch (NumberFormatException e) {
            message("Not a frequency: " + markerFreq.getText());
        }
    }

    private void expandLR() {
        state.expandLR(state.shown()).ifPresentOrElse(r -> {
            session.applySweep(session.sweep().withRange(r));
            state.markers.clear();
            sweepPanel.refresh();
            stepWarning();
            restart();
        }, () -> message("Expand L-R needs an L or R marker."));
    }

    private void markToCenter() {
        if (state.selected().isEmpty()) {
            info("Mark->Cent", "No marker is selected.");
            return;
        }
        state.markToCenter(state.shown(), session.sweep().range()).ifPresent(r -> {
            session.applySweep(session.sweep().withRange(r));
            sweepPanel.refresh();
            stepWarning();
            restart();
        });
    }

    // ================================================================ dialogs

    /** The Sweep Parameters are always shown in the lower panel; this puts the cursor there. */
    @Override
    public void openSweepParameters() { sweepPanel.focusFrequency(); }

    private SweepPanel.Host sweepHost() {
        return new SweepPanel.Host() {
            @Override
            public void sweepChanged(SweepSettings old, List<String> notices) {
                SweepSettings now = session.sweep();
                if (!old.range().equals(now.range()) || old.steps() != now.steps()) state.clearStick();
                stage.setTitle(title());
                stepWarning();
                if (!notices.isEmpty()) message(String.join("  ", notices));
                if (old.points() != now.points() || old.waitMs() != now.waitMs() || old.samples() != now.samples())
                    lastSweepNanos = 0;    // the last measured sweep no longer applies
                updateSweepTime(true);
                graph.invalidate();
            }

            @Override
            public void modeChanged(MsaMode mode, boolean tracking) { changeMode(mode, tracking); }

            @Override
            public void correctionChanged() { calibrationChanged(); }

            @Override
            public void message(String text) { MainWindow.this.message(text); }
        };
    }

    @Override
    public void openAxisDialog(int axis) {
        AxisDialog dlg = new AxisDialog(stage, axis, session.display(), GraphView.primary(session.display()),
                state.traceWidth(axis), state.store.lastComplete() != null ? state.store.lastComplete() : state.shown(),
                session.mode());
        dlg.showAndWait().ifPresent(r -> {
            state.setTraceWidth(axis, r.traceWidth());
            session.applyDisplay(r.display());
            if (r.notice() != null) info("Axis", r.notice());
            markersChanged();
        });
    }

    void openMarkerOptions() {
        new MarkerOptionsDialog(stage, state.showMarkers(), state.markers.lrMode()).showAndWait().ifPresent(r -> {
            state.setShowMarkers(r.show());
            state.markers.setLrMode(r.mode());
            markersChanged();
        });
    }

    void openReferenceDialog() {
        new ReferenceDialog(stage, state.referenceSpec(), session.display()).showAndWait().ifPresent(r -> {
            state.setReference(r.spec(), r.updateData(), session.display());
            if (r.spec().kind() == GraphState.ReferenceSpec.Kind.CURRENT_DATA && state.shown() == null)
                message("No data yet for the reference.");
            graph.invalidate();
        });
    }

    void showVariables() {
        if (variables == null) variables = new VariablesWindow(stage, session, state, () -> variables = null);
        variables.show();
    }

    void changeMode(MsaMode mode, boolean tracking) {
        boolean changed = session.setMode(mode, tracking);
        if (changed) {
            state.markers.clear();
            state.clearReference();
        }
        state.clearStick();
        lastSweepNanos = 0;
        syncModeMenu();
        if (sweepPanel != null) sweepPanel.refresh();
        stepWarning();
        graph.invalidate();
        if (changed && mode.networkAnalyser()) {
            TransmissionCal.Level applied = session.appliedCalLevel(session.sweep());
            message(mode.title + ": reference " + (applied == TransmissionCal.Level.NONE
                    ? "none (Operating Cal > Perform Cal with " + (mode == MsaMode.REFLECTION ? "the calibration standards)"
                    : "a through connection)") : applied.title));
        }
    }

    // ================================================================ files

    private FileChooser chooser(String title, String desc, String... ext) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(desc, ext));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        if (lastFolder != null && lastFolder.toFile().isDirectory()) fc.setInitialDirectory(lastFolder.toFile());
        return fc;
    }

    private Optional<Path> remember(File f) {
        if (f == null) return Optional.empty();
        lastFolder = f.toPath().toAbsolutePath().getParent();
        return Optional.of(f.toPath());
    }

    private WritableImage snapshot() { return graph.canvas().snapshot(null, null); }

    private void saveImage() {
        FileChooser fc = chooser("Save Image", "PNG image", "*.png");
        fc.setInitialFileName("msa-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png");
        remember(fc.showSaveDialog(stage)).ifPresent(p -> {
            try {
                javax.imageio.ImageIO.write(toBufferedImage(snapshot()), "png", p.toFile());
                message("Saved " + p);
            } catch (IOException e) {
                error("Save Image", e);
            }
        });
    }

    /** WritableImage to AWT image without the javafx-swing module. */
    private static java.awt.image.BufferedImage toBufferedImage(WritableImage img) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        javafx.scene.image.PixelReader r = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            r.getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w);
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    private void copyImage() {
        ClipboardContent c = new ClipboardContent();
        c.putImage(snapshot());
        Clipboard.getSystemClipboard().setContent(c);
        message("Graph copied to the clipboard.");
    }

    private void saveData() {
        SweepData d = state.store.lastComplete() != null ? state.store.lastComplete() : state.shown();
        if (d == null) {
            message("No data to save yet.");
            return;
        }
        FileChooser fc = chooser("Save Data", "Text files", "*.txt", "*.csv");
        fc.setInitialFileName("msa-data.txt");
        remember(fc.showSaveDialog(stage)).ifPresent(p -> {
            try {
                SweepSettings s = session.sweep();
                List<String> header = new ArrayList<>();
                header.add("MSA " + modeTitle() + " data, " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                header.add(String.format(Locale.ROOT, "Path %d, RBW %s kHz, Video %s, Wait %d ms, Steps %d, Samples %d",
                        s.path(), session.config().filter(s.path()).bwKHz(),
                        msa.model.MsaSettings.videoName(s.video(), session.config()), s.waitMs(), s.steps(), s.samples()));
                if (s.mode().networkAnalyser()) {
                    TransmissionCal.Level cal = session.appliedCalLevel(s);
                    header.add("Reference: " + (cal == TransmissionCal.Level.NONE ? "none" : cal.title)
                            + (s.mode().readsPhase() ? ", plane extension " + session.vna().planeExtNs() + " ns" : ""));
                    if (s.mode() == MsaMode.REFLECTION)
                        header.add(session.vna().reflectFixture().title + " R0 " + session.vna().s11BridgeR0()
                                + " ohms; graph R0 " + session.vna().s11GraphR0() + " ohms");
                }
                DataFile.write(p, d, header, s.mode());
                message("Saved " + p);
            } catch (IOException e) {
                error("Save Data", e);
            }
        });
    }

    private void toggleRecording() {
        if (recorder != null) {
            stopRecording();
            return;
        }
        FileChooser fc = chooser("Record Sweeps To", "Recordings", "*.csv");
        fc.setInitialFileName("msa-recording.csv");
        Optional<Path> p = remember(fc.showSaveDialog(stage));
        if (p.isEmpty()) {
            recordItem.setSelected(false);
            return;
        }
        try {
            recorder = new SweepRecorder(p.get());
            session.engine().addListener(recorder, Runnable::run);
            recordItem.setText("Stop Recording (" + p.get().getFileName() + ")");
            recordItem.setSelected(true);
            message("Recording to " + p.get() + " (replay with --replay)");
        } catch (IOException e) {
            recordItem.setSelected(false);
            error("Record", e);
        }
    }

    private void stopRecording() {
        if (recorder == null) return;
        session.engine().removeListener(recorder);
        try {
            recorder.close();
        } catch (Exception e) {
            // nothing useful to do while closing
        }
        message("Recorded " + recorder.sweepsCompleted() + " sweeps to " + recorder.file());
        recorder = null;
        recordItem.setText("Record Sweeps...");
        recordItem.setSelected(false);
    }

    private void loadPrefs() {
        FileChooser fc = chooser("Load Prefs", "Preference files", "*.txt");
        remember(fc.showOpenDialog(stage)).ifPresent(p -> {
            try {
                List<String> w = session.loadPrefs(p);
                state.markers.clear();
                state.clearReference();
                state.clearStick();
                syncModeMenu();
                sweepPanel.refresh();
                state.store.refreshCorrections();
                refreshItem.setSelected(session.display().refreshEachScan());
                if (!w.isEmpty()) info("Load Prefs", String.join("\n", w));
                graph.invalidate();
            } catch (IOException | RuntimeException e) {
                error("Load Prefs", e);
            }
        });
    }

    private void savePrefs(boolean chooseFile) {
        try {
            if (!chooseFile) {
                session.savePrefs();
                message("Saved " + session.msaInfo().resolve("MSA_Prefs").resolve("Prefs.txt") + " (previous file kept as .bak)");
                return;
            }
            FileChooser fc = chooser("Save Prefs", "Preference files", "*.txt");
            fc.setInitialFileName("Prefs.txt");
            Optional<Path> p = remember(fc.showSaveDialog(stage));
            if (p.isPresent()) {
                session.savePrefsAs(p.get());
                message("Saved " + p.get());
            }
        } catch (IOException e) {
            error("Save Prefs", e);
        }
    }

    private void syncModeMenu() {
        if (session.hasTrackingGenerator()) {
            if (session.mode() == MsaMode.REFLECTION) modeRefl.setSelected(true);
            else if (session.mode() != MsaMode.SA) modeTrans.setSelected(true);
            else (session.trackingMode() ? modeSaTg : modeSa).setSelected(true);
        }
        stage.setTitle(title());
    }

    // ================================================================ messages

    @Override
    public void message(String text) { status.setText(text); }

    private void info(String title, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        a.initOwner(stage);
        a.setTitle(title);
        a.setHeaderText(null);
        a.show();
    }

    private void error(String title, Exception e) {
        Alert a = new Alert(Alert.AlertType.ERROR, e.getMessage() == null ? e.toString() : e.getMessage(), ButtonType.OK);
        a.initOwner(stage);
        a.setTitle(title);
        a.setHeaderText(null);
        a.show();
    }

    /** For tests and tools in this package. */
    GraphState graphState() { return state; }
    GraphView graphView() { return graph; }
    Stage stage() { return stage; }

    static Sweeper.Point pointAt(Session s, int index) {
        SweepEngine.Trace t = s.engine().snapshot();
        return index >= 0 && index < t.points().length ? t.points()[index] : null;
    }
}
