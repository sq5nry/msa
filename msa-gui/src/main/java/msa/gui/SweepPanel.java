package msa.gui;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import msa.config.MsaConfig;
import msa.hw.SlimCommands.VideoFilter;
import msa.model.DisplaySettings;
import msa.model.MsaSettings;
import msa.sweep.AutoSamples;
import msa.sweep.AutoSteps;
import msa.sweep.AutoWait;
import msa.sweep.MsaMode;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;

/**
 * The BASIC "Sweep Parameters" window as a permanent part of the main window's lower panel.
 * Lists, buttons and check boxes take effect at once; a text field takes effect on Enter or when it
 * loses focus (edited, not yet applied fields are shown yellow, rejected ones red). The rules are those of
 * the dialog's OK button ({@link SweepForm}).
 *
 * <p>Mode block (only with TG hardware): Spectrum Analyzer (the TG is a signal generator at a fixed
 * frequency), SA with TG (tracking, with offset and normal/reverse) or VNA/SNA transmission (tracking with
 * no offset; the field below is the reference plane extension in VNA); switching restarts like the Mode menu.
 */
public final class SweepPanel extends VBox {

    /** What the panel asks of the window. */
    public interface Host {
        /** Settings were changed here and applied to the session. */
        void sweepChanged(SweepSettings old, List<String> notices);
        /** The mode switch was flipped: same as the Mode menu. */
        void modeChanged(MsaMode mode, boolean tracking);
        /** The plane extension changed (the data on the screen is recalculated). */
        default void correctionChanged() {}
        /** A message for the status line. */
        void message(String text);
    }

    private static final String DIRTY = "-fx-control-inner-background: #fff4b0;";
    private static final String BAD = "-fx-control-inner-background: #ffc8c8;";
    /** Applied, but more steps than {@link AutoSteps#RECOMMENDED_MAX}: allowed, the sweep will just be slow. */
    private static final String MANY = "-fx-text-fill: red; -fx-font-weight: bold; -fx-opacity: 1;";

    private final Session session;
    private final Host host;
    private final RadioButton centSpan = new RadioButton(), startStop = new RadioButton();
    private final TextField cent = field(8), span = field(8), start = field(8), stop = field(8);
    private final TextField steps = field(5), waitMs = field(4), samples = field(3), genFreq = field(6);
    private final CheckBox autoSteps = new CheckBox("Auto"), autoWait = new CheckBox("Auto"), autoSamples = new CheckBox("Auto");
    private final ComboBox<String> pathBox = new ComboBox<>(), videoBox = new ComboBox<>(), bandBox = new ComboBox<>(),
            scaleBox = new ComboBox<>(), dirBox = new ComboBox<>();
    private final ComboBox<Integer> divBox = new ComboBox<>();
    private final ToggleButton sgMode = new ToggleButton("SA"), tgMode = new ToggleButton("SA with TG"),
            vnaMode = new ToggleButton("VNA Trans");
    private final ToggleButton normRev = new ToggleButton("Normal");
    private final Label genLabel = lbl(""), genUnit = lbl("MHz");
    private final HBox revBox = new HBox(6);
    private final HBox genRow = row(genLabel, genFreq, genUnit);
    private boolean updating;

    public SweepPanel(Session session, Host host) {
        this.session = session;
        this.host = host;
        MsaConfig cfg = session.config();
        setSpacing(3);
        setPadding(new Insets(0, 4, 0, 4));

        // ---- frequencies
        ToggleGroup fm = new ToggleGroup();
        centSpan.setToggleGroup(fm);
        startStop.setToggleGroup(fm);
        startStop.setSelected(true);
        centSpan.setOnAction(e -> enableFreqFields());
        startStop.setOnAction(e -> enableFreqFields());
        centSpan.setTooltip(new Tooltip("Enter centre and span"));
        startStop.setTooltip(new Tooltip("Enter start and stop"));
        GridPane freq = grid();
        freq.addRow(0, centSpan, lbl("Cent"), cent, startStop, lbl("Start"), start, lbl("MHz"));
        freq.addRow(1, lbl(""), lbl("Span"), span, lbl(""), lbl("Stop"), stop, lbl("MHz"));
        autoSteps.setTooltip(new Tooltip("Steps from span and filter: step <= RBW/2 (" + AutoSteps.MIN_STEPS + ".."
                + "; red above " + AutoSteps.RECOMMENDED_MAX + ": allowed but slow); " + AutoSteps.TRACKING_STEPS
                + " with the tracking generator. Untick to enter your own."));
        HBox stepRow = row(lbl("Steps"), steps, autoSteps, gap(6), lbl("Wait"), waitMs, lbl("ms"), autoWait,
                gap(6), lbl("Samples"), samples, autoSamples);

        // ---- hardware and sweep shape
        List<MsaConfig.Filter> filters = cfg.filters();
        for (int i = 0; i < filters.size(); i++)
            pathBox.getItems().add(String.format(Locale.ROOT, "%s kHz (P%d)", plain(filters.get(i).bwKHz()), i + 1));
        for (VideoFilter v : VideoFilter.values()) videoBox.getItems().add(MsaSettings.videoName(v, cfg));
        bandBox.getItems().addAll("Auto", "1G", "2G", "3G");
        bandBox.setTooltip(new Tooltip("1G approx. 0-1050 MHz, 2G 960-2050 MHz, 3G 1970-3070 MHz"));
        scaleBox.getItems().addAll("Linear", "Log");
        dirBox.getItems().addAll("L-R", "R-L", "Alternate");
        divBox.getItems().addAll(4, 6, 8, 10, 12);
        divBox.setTooltip(new Tooltip("Horizontal divisions"));
        pathBox.setPrefWidth(134);
        videoBox.setPrefWidth(92);
        bandBox.setPrefWidth(70);
        divBox.setPrefWidth(70);
        scaleBox.setPrefWidth(88);
        dirBox.setPrefWidth(92);
        GridPane hw = grid();
        pathBox.setTooltip(new Tooltip("Final filter path (resolution bandwidth)"));
        hw.addRow(0, lbl("RBW"), pathBox, lbl("Video"), videoBox);
        hw.addRow(1, lbl("Band"), bandBox, lbl("Div"), divBox);
        hw.addRow(2, lbl("Sweep"), scaleBox, lbl(""), dirBox);

        HBox top = new HBox(14, new VBox(3, freq, stepRow), hw);

        // ---- generator
        if (session.hasTrackingGenerator()) {
            ToggleGroup g = new ToggleGroup();
            sgMode.setToggleGroup(g);
            tgMode.setToggleGroup(g);
            vnaMode.setToggleGroup(g);
            sgMode.setTooltip(new Tooltip("Plain spectrum analyser; the TG output is a signal generator at a fixed frequency"));
            tgMode.setTooltip(new Tooltip("Tracking generator follows the sweep"));
            MsaMode trans = session.transmissionMode();
            vnaMode.setText(trans == MsaMode.VECTOR_TRANS ? "VNA Trans" : "SNA Trans");
            vnaMode.setTooltip(new Tooltip(trans.title + ": TG through the device under test into the MSA input; "
                    + (trans == MsaMode.VECTOR_TRANS ? "magnitude and phase" : "magnitude") + " referenced to a through calibration"));
            sgMode.setOnAction(e -> modeClicked(MsaMode.SA, false));
            tgMode.setOnAction(e -> modeClicked(MsaMode.SA, true));
            vnaMode.setOnAction(e -> modeClicked(trans, true));
            normRev.setOnAction(e -> {
                normRev.setText(normRev.isSelected() ? "Reverse" : "Normal");
                applyNow();
            });
            normRev.setTooltip(new Tooltip("Normal: TG at the analyser frequency + offset. Reverse: the TG sweeps the other way."));
            revBox.getChildren().addAll(lbl("TG"), normRev);
            revBox.setAlignment(Pos.CENTER_LEFT);
            VBox gen = new VBox(3, new HBox(0, sgMode, tgMode, vnaMode), genRow, revBox);
            top.getChildren().add(gen);
        }
        getChildren().add(top);

        // ---- behaviour
        for (TextField t : List.of(cent, span, start, stop, steps, waitMs, samples, genFreq)) {
            t.setOnAction(e -> applyNow());
            t.focusedProperty().addListener((o, was, is) -> {
                if (is) return;
                if (isDirty(t)) applyNow(); else if (!BAD.equals(t.getStyle())) refresh();   // catch up on skipped updates
            });
            t.textProperty().addListener((o, a, b) -> { if (!updating) t.setStyle(DIRTY); });
            t.setMinWidth(Region.USE_PREF_SIZE);
        }
        for (ComboBox<?> c : List.of(pathBox, videoBox, bandBox, scaleBox, dirBox, divBox)) {
            c.setOnAction(e -> { if (!updating) applyNow(); });
            c.setMinWidth(Region.USE_PREF_SIZE);
        }
        for (javafx.scene.control.Control c : List.of(autoSteps, autoWait, autoSamples, sgMode, tgMode, vnaMode, normRev, centSpan, startStop))
            c.setMinWidth(Region.USE_PREF_SIZE);
        setMinWidth(Region.USE_PREF_SIZE);
        autoSamples.setOnAction(e -> {
            if (updating) return;
            session.setAutoSamples(autoSamples.isSelected());
            if (autoSamples.isSelected()) host.sweepChanged(session.sweep(), List.of());
            refresh();
        });
        autoSamples.setTooltip(new Tooltip("ADC readings averaged per point: " + AutoSamples.WIDE_SAMPLES
                + " with the Wide video filter (fast enough for averaging to smooth the noise), 1 with Mid and slower"
                + " (they already smooth far longer than the readings take). Untick to enter your own (1-255)."));
        autoWait.setOnAction(e -> {
            if (updating) return;
            session.setAutoWait(autoWait.isSelected());
            if (autoWait.isSelected()) host.sweepChanged(session.sweep(), List.of());
            refresh();
        });
        autoSteps.setOnAction(e -> {
            if (updating) return;
            session.setAutoSteps(autoSteps.isSelected());
            if (autoSteps.isSelected()) host.sweepChanged(session.sweep(), List.of());
            refresh();
        });
        refresh();
    }

    // ---------------------------------------------------------------- public

    /** Shows the session's current settings (fields being edited keep their text). */
    public void refresh() {
        updating = true;
        try {
            SweepSettings s = session.sweep();
            DisplaySettings d = session.display();
            set(cent, SweepForm.box(s.range().centerMHz()));
            set(span, SweepForm.box(s.range().spanMHz()));
            set(start, SweepForm.box(s.startMHz()));
            set(stop, SweepForm.box(s.stopMHz()));
            set(steps, String.valueOf(s.steps()));
            markManySteps(s.steps());
            set(waitMs, String.valueOf(s.waitMs()));
            set(samples, String.valueOf(s.samples()));
            autoSteps.setSelected(session.autoSteps());
            steps.setDisable(session.autoSteps());
            autoWait.setSelected(session.autoWait());
            waitMs.setDisable(session.autoWait());
            autoSamples.setSelected(session.autoSamples());
            samples.setDisable(session.autoSamples());
            double tc = AutoWait.videoTimeConstantMs(s.video(), session.config());
            double rbw = session.config().filter(s.path()).bwKHz();
            autoWait.setTooltip(new Tooltip(String.format(Locale.ROOT,
                    "Wait from the video filter and RBW: %.0f x video time constant (%s: %.4g ms) + %.0f / RBW (%s kHz: %.3g ms)"
                            + " = %d ms. Untick to enter your own.",
                    AutoWait.TIME_CONSTANTS, MsaSettings.videoName(s.video(), session.config()), tc,
                    AutoWait.RBW_PERIODS, plain(rbw), AutoWait.RBW_PERIODS / rbw, session.autoWaitFor(s))));
            pathBox.getSelectionModel().select(Math.min(pathBox.getItems().size(), s.path()) - 1);
            videoBox.getSelectionModel().select(s.video().ordinal());
            bandBox.getSelectionModel().select(s.band());
            scaleBox.getSelectionModel().select(s.scale() == SweepSettings.Scale.LINEAR ? 0 : 1);
            dirBox.getSelectionModel().select(s.direction().ordinal());
            divBox.setValue(d.gridDivX());
            divBox.setDisable(s.scale() == SweepSettings.Scale.LOG);
            if (s.mode().networkAnalyser()) {
                vnaMode.setSelected(true);
                genLabel.setText("Plane Ext");
                genUnit.setText("ns");
                set(genFreq, plain(session.vna().planeExtNs()));
                genFreq.setTooltip(new Tooltip("Reference plane extension: phase advanced by 360 degrees x frequency x delay"));
                genRow.setVisible(s.mode().readsPhase());
                revBox.setVisible(false);
            } else {
                genUnit.setText("MHz");
                genFreq.setTooltip(null);
                genRow.setVisible(true);
                switch (s.generator()) {
                    case SynthPlanner.Tracking t -> {
                        tgMode.setSelected(true);
                        genLabel.setText("TG Offset");
                        set(genFreq, plain(t.offsetMHz()));
                        normRev.setSelected(t.reverse());
                        normRev.setText(t.reverse() ? "Reverse" : "Normal");
                        revBox.setVisible(true);
                    }
                    case SynthPlanner.SignalGen g -> {
                        sgMode.setSelected(true);
                        genLabel.setText("Sig Gen Freq");
                        set(genFreq, plain(g.sgoutMHz()));
                        revBox.setVisible(false);
                    }
                }
            }
            enableFreqFields();
        } finally {
            updating = false;
        }
    }

    /** "Options > Sweep Parameters" or a double-click on the frequency axis: put the cursor in the first box. */
    public void focusFrequency() {
        TextField t = centSpan.isSelected() ? cent : start;
        t.requestFocus();
        t.selectAll();
    }

    // ---------------------------------------------------------------- applying

    private void modeClicked(MsaMode mode, boolean tracking) {
        if (updating) return;
        if (mode == session.mode() && (mode != MsaMode.SA || tracking == session.trackingMode())) {
            refresh();                                              // a toggle group lets the user deselect: undo that
            return;
        }
        host.modeChanged(mode, tracking);
        refresh();
    }

    /** [axisXFinished] for the whole panel. */
    private void applyNow() {
        if (updating) return;
        SweepSettings old = session.sweep();
        try {
            SweepForm.Input in = input(old);
            SweepForm.Result r = SweepForm.apply(in, old, session.display(), session.config(), session.trackingMode());
            boolean extChanged = false;
            if (old.mode().readsPhase()) {
                double ext = SweepForm.number("Plane Ext", genFreq.getText());
                extChanged = ext != session.vna().planeExtNs();
                session.setPlaneExt(ext);
            }
            session.applyDisplay(r.display());
            session.applySweep(r.sweep());
            if (extChanged) host.correctionChanged();
            for (TextField t : List.of(cent, span, start, stop, steps, waitMs, samples, genFreq)) t.setStyle("");
            refresh();
            if (AutoSteps.aboveRecommended(session.sweep().steps()))
                host.message(session.sweep().steps() + " steps: more than " + AutoSteps.RECOMMENDED_MAX + ", sweeps will be slow");
            host.sweepChanged(old, r.notices());
        } catch (IllegalArgumentException e) {
            markBad(e.getMessage());
            host.message(e.getMessage());
        }
    }

    private void markBad(String msg) {
        String field = msg.contains(":") ? msg.substring(0, msg.indexOf(':')) : "";
        TextField t = switch (field) {
            case "Cent" -> cent;
            case "Span" -> span;
            case "Start" -> start;
            case "Stop" -> stop;
            case "Steps", "Steps/Sweep" -> steps;
            case "Wait" -> waitMs;
            case "Samples" -> samples;
            default -> genFreq;                                    // generator frequency or plane extension
        };
        t.setStyle(BAD);
        t.requestFocus();
    }

    private SweepForm.Input input(SweepSettings s) {
        SweepSettings.Direction d = SweepSettings.Direction.values()[Math.max(0, dirBox.getSelectionModel().getSelectedIndex())];
        int path = pathBox.getSelectionModel().getSelectedIndex() + 1;
        VideoFilter v = VideoFilter.values()[Math.max(0, videoBox.getSelectionModel().getSelectedIndex())];
        Integer div = divBox.getValue();
        boolean linear = scaleBox.getSelectionModel().getSelectedIndex() != 1;
        return new SweepForm.Input(centSpan.isSelected(), cent.getText(), span.getText(), start.getText(), stop.getText(),
                session.autoSteps() ? String.valueOf(s.steps()) : steps.getText(),
                session.autoWait() ? String.valueOf(s.waitMs()) : waitMs.getText(),
                session.autoSamples() ? String.valueOf(s.samples()) : samples.getText(),
                path < 1 ? s.path() : path, v, Math.max(0, bandBox.getSelectionModel().getSelectedIndex()),
                div == null ? 10 : div, linear, d,
                session.hasTrackingGenerator() && !s.mode().networkAnalyser() ? genFreq.getText() : "0",
                normRev.isSelected() && !s.mode().networkAnalyser(), session.display().refreshEachScan());
    }

    // ---------------------------------------------------------------- helpers

    /** Log sweeps are entered as start/stop, as in BASIC. */
    private void enableFreqFields() {
        if (scaleBox.getSelectionModel().getSelectedIndex() == 1 && centSpan.isSelected()) startStop.setSelected(true);
        centSpan.setDisable(scaleBox.getSelectionModel().getSelectedIndex() == 1);
        boolean cs = centSpan.isSelected();
        cent.setDisable(!cs);
        span.setDisable(!cs);
        start.setDisable(cs);
        stop.setDisable(cs);
    }

    private boolean isDirty(TextField t) { return DIRTY.equals(t.getStyle()); }

    /** Red step count above the recommended maximum (unless the box is being edited). */
    private void markManySteps(int n) {
        String st = steps.getStyle();
        if (DIRTY.equals(st) || BAD.equals(st)) return;
        steps.setStyle(AutoSteps.aboveRecommended(n) ? MANY : "");
        steps.setTooltip(AutoSteps.aboveRecommended(n)
                ? new Tooltip("More than " + AutoSteps.RECOMMENDED_MAX + " steps: allowed, but each sweep takes long") : null);
    }

    /** Sets a field unless the user is editing it (edited or rejected text stays until applied). */
    private void set(TextField t, String text) {
        // Setting a TextField's text, even to the same value, drops the selection and moves the caret, so a
        // box the user is in is left alone (it is refreshed when they leave it), and equal text is not re-set.
        if (t.isFocused()) return;
        if (text.equals(t.getText())) {
            if (!DIRTY.equals(t.getStyle()) && !BAD.equals(t.getStyle())) return;
            t.setStyle("");
            return;
        }
        boolean was = updating;
        updating = true;
        t.setText(text);
        t.setStyle("");
        updating = was;
    }

    private static Label lbl(String text) {
        Label l = new Label(text);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    private static TextField field(int cols) {
        TextField t = new TextField();
        t.setPrefColumnCount(cols);
        return t;
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(4);
        g.setVgap(3);
        g.setAlignment(Pos.CENTER_LEFT);
        return g;
    }

    private static HBox row(Node... n) {
        HBox h = new HBox(4, n);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Region gap(double w) {
        Region r = new Region();
        r.setMinWidth(w);
        return r;
    }

    private static String plain(double v) {
        String s = String.format(Locale.ROOT, "%.6f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }
}
