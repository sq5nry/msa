package msa.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import msa.model.MsaSettings;
import msa.model.VnaSettings;
import msa.sweep.SweepSettings;
import msa.trace.SweepData;
import msa.trace.TraceStore;
import msa.vna.CalStandards;
import msa.vna.OslCal;

/**
 * "Operating Cal &gt; Perform Cal" in reflection ([PerformOSLCal], the "Reflection Calibration" window, with the
 * band/base part of [RunVNACal]) and "Update Cal" ([PerformOSLCalUpdate]).
 *
 * <p>Full OSL measures the open, short and load; the standards are described by a set from
 * MSA_Info/OperatingCal/CalSets or by custom RLC specifications (e.g. a load of 48.68 ohms). A reference
 * calibration measures only the open or short (bridge), the short (series fixture) or the open (shunt
 * fixture); the other standards are taken as ideal. Each "Perform" restarts the sweep and takes the next
 * complete sweep as that standard's reading.
 */
final class ReflectionCalDialog {

    interface Host {
        void calibrationChanged();
        void message(String text);
    }

    private static final String CUSTOM = "Custom";

    // remembered while the program runs, as the BASIC globals are
    private static boolean lastFull = true;
    private static String lastSet = null;
    private static String customOpen = "RLC[S, C0, D0]", customShort = "RLC[P, R0, L0, D0]", customLoad = "RLC[P, R50, C0]";

    private final Stage stage = new Stage();
    private final Session session;
    private final TraceStore store;
    private final Host host;
    private final boolean update;

    private final RadioButton full = new RadioButton("Full OSL"), ref = new RadioButton("Reference Cal (Open or Short only)");
    private final RadioButton bridge = new RadioButton("Reflection Bridge"), series = new RadioButton("Series Fixture"),
            shunt = new RadioButton("Shunt Fixture");
    private final TextField r0 = new TextField(), shuntDelay = new TextField();
    private final ComboBox<String> sets = new ComboBox<>();
    private final TextField openSpec = new TextField(), shortSpec = new TextField(), loadSpec = new TextField();
    private final Label setDescription = new Label(), status = new Label(), bandInfo = new Label(), baseInfo = new Label();
    private final Button doOpen = new Button("Perform Open"), doShort = new Button("Perform Short"),
            doLoad = new Button("Perform Load"), saveSet = new Button("Save Set As..."), finish = new Button("Done"),
            close = new Button("Close"), saveBase = new Button("Save Band As Base"), clearBase = new Button("Clear Base Cal");
    private final Label openDone = new Label(), shortDone = new Label(), loadDone = new Label();
    private final HBox delayRow = new HBox(6);
    private List<CalStandards> standards;

    private OslCal.Reading open, shortR, load;
    private double[] freqs;
    private SweepSettings measuredWith;
    private TraceStore.ChangeListener waiting;
    private Button running;

    ReflectionCalDialog(Window owner, Session session, TraceStore store, Host host, boolean update) {
        this.session = session;
        this.store = store;
        this.host = host;
        this.update = update;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(update ? "Update Calibration" : "Reflection Calibration");

        SweepSettings s = session.sweep();
        Label setup = wrap(String.format(Locale.ROOT, "Calibration is made at the %d steps of the current sweep, %s to %s MHz,"
                        + " in path %d (%s kHz), video filter %s, wait %d ms. It is used only for this path and fixture."
                        + " For smooth readings use the Narrow video filter and a longer wait.",
                s.steps(), TransmissionCalDialog.plainNum(s.startMHz()), TransmissionCalDialog.plainNum(s.stopMHz()), s.path(),
                TransmissionCalDialog.plainNum(session.config().filter(s.path()).bwKHz()),
                MsaSettings.videoName(s.video(), session.config()), s.waitMs()));

        VBox root;
        if (update) {
            OslCal band = session.reflectionCal().band();
            String std = band.refType();
            Label how = wrap("To update the band calibration, attach the " + std + " standard and click Perform " + std
                    + ". This replaces the reference reading, partially adjusting for drift since the full calibration.");
            Button perform = std.equals("Open") ? doOpen : std.equals("Short") ? doShort : doLoad;
            perform.setOnAction(e -> measure(perform, std));
            HBox buttons = new HBox(8, perform, openDone, shortDone, loadDone, spacer(), close);
            buttons.setAlignment(Pos.CENTER_LEFT);
            root = new VBox(10, setup, how, buttons, status);
        } else {
            ToggleGroup type = new ToggleGroup();
            full.setToggleGroup(type);
            ref.setToggleGroup(type);
            ToggleGroup fix = new ToggleGroup();
            bridge.setToggleGroup(fix);
            series.setToggleGroup(fix);
            shunt.setToggleGroup(fix);
            VnaSettings v = session.vna();
            switch (v.reflectFixture()) {
                case BRIDGE -> bridge.setSelected(true);
                case SERIES -> series.setSelected(true);
                case SHUNT -> shunt.setSelected(true);
            }
            (lastFull ? full : ref).setSelected(true);
            r0.setText(TransmissionCalDialog.plainNum(v.s11BridgeR0()));
            r0.setPrefColumnCount(5);
            shuntDelay.setText(TransmissionCalDialog.plainNum(v.s21ShuntDelay()));
            shuntDelay.setPrefColumnCount(5);
            delayRow.getChildren().addAll(new Label("Connect delay (ns)"), shuntDelay);
            delayRow.setAlignment(Pos.CENTER_LEFT);
            for (RadioButton b : List.of(full, ref, bridge, series, shunt)) b.setOnAction(e -> layoutForType());

            standards = session.calStandards();
            for (CalStandards c : standards) sets.getItems().add(c.name());
            sets.getItems().add(CUSTOM);
            String want = lastSet != null && sets.getItems().contains(lastSet) ? lastSet : "Ideal 50 ohms";
            sets.getSelectionModel().select(sets.getItems().contains(want) ? want : sets.getItems().get(0));
            sets.setOnAction(e -> showSet());
            for (TextField t : List.of(openSpec, shortSpec, loadSpec)) t.setPrefColumnCount(22);
            setDescription.setWrapText(true);
            setDescription.setStyle("-fx-text-fill: #555;");
            saveSet.setOnAction(e -> saveSet());

            doOpen.setOnAction(e -> measure(doOpen, "Open"));
            doShort.setOnAction(e -> measure(doShort, "Short"));
            doLoad.setOnAction(e -> measure(doLoad, "Load"));
            finish.setOnAction(e -> finish());
            saveBase.setOnAction(e -> saveBase());
            clearBase.setOnAction(e -> clearBase());

            GridPane fixture = new GridPane();
            fixture.setHgap(8);
            fixture.setVgap(6);
            fixture.addRow(0, bridge);
            fixture.addRow(1, series);
            fixture.addRow(2, shunt, delayRow);
            TitledPane fixturePane = new TitledPane("Fixture", fixture);
            fixturePane.setCollapsible(false);
            fixturePane.visibleProperty().bind(ref.selectedProperty());
            fixturePane.managedProperty().bind(ref.selectedProperty());

            GridPane std = new GridPane();
            std.setHgap(8);
            std.setVgap(6);
            std.addRow(0, new Label("Standards"), sets, saveSet);
            std.add(setDescription, 1, 1, 2, 1);
            std.addRow(2, doOpen, openSpec, openDone);
            std.addRow(3, doShort, shortSpec, shortDone);
            std.addRow(4, doLoad, loadSpec, loadDone);
            for (Button b : List.of(doOpen, doShort, doLoad)) b.setMaxWidth(Double.MAX_VALUE);
            Label specHelp = wrap("Each standard is an RLC specification: S or P (series / parallel), R ohms, L henries,"
                    + " C farads, D one-way delay in seconds, with multipliers p n u m k. Ideal open RLC[S, C0, D0],"
                    + " ideal short RLC[P, R0, L0, D0], a 48.68 ohm load RLC[P, R48.68, C0].");
            specHelp.setStyle("-fx-text-fill: #555; -fx-font-size: 0.9em;");

            HBox typeRow = new HBox(16, full, ref, new Label("R0 (ohms)"), r0);
            typeRow.setAlignment(Pos.CENTER_LEFT);
            Button help = new Button("Help");
            help.setOnAction(e -> help());
            close.setText("Cancel");
            HBox buttons = new HBox(8, help, spacer(), finish, close);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            GridPane cal = new GridPane();
            cal.setHgap(8);
            cal.setVgap(6);
            cal.addRow(0, bold("Band:"), bandInfo);
            cal.addRow(1, bold("Base:"), baseInfo);
            cal.add(new HBox(8, saveBase, clearBase), 1, 2);
            root = new VBox(10, setup, typeRow, fixturePane, std, specHelp, status, buttons, new javafx.scene.control.Separator(), cal);
            showSet();
            layoutForType();
            showInfo();
        }
        close.setOnAction(e -> { if (waiting == null) stage.close(); });
        stage.setOnCloseRequest(e -> { if (waiting != null) e.consume(); });
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        root.setPadding(new Insets(12));
        root.setPrefWidth(680);
        stage.setScene(new Scene(root));
    }

    void show() { stage.show(); }

    // ---------------------------------------------------------------- layout

    private OslCal.Fixture fixture() {
        if (full.isSelected() || bridge.isSelected()) return OslCal.Fixture.BRIDGE;
        return series.isSelected() ? OslCal.Fixture.SERIES : OslCal.Fixture.SHUNT;
    }

    /** [OSLCheckFull] / [OSLCheckRef] / [OSLcalSetBridge] ...: which standards can be measured. */
    private void layoutForType() {
        boolean isFull = full.isSelected();
        OslCal.Fixture f = fixture();
        boolean o = isFull || f != OslCal.Fixture.SERIES, s = isFull || f != OslCal.Fixture.SHUNT, l = isFull;
        show(doOpen, o);
        show(openSpec, o);
        show(openDone, o);
        show(doShort, s);
        show(shortSpec, s);
        show(shortDone, s);
        show(doLoad, l);
        show(loadSpec, l);
        show(loadDone, l);
        delayRow.setDisable(!shunt.isSelected());
        if (!isFull) {
            open = shortR = load = null;
            for (Label d : List.of(openDone, shortDone, loadDone)) d.setText("");
        }
        finish.setDisable(!isFull);
        status.setText(isFull ? "Attach each standard in turn and click its Perform button, then Done."
                : "Attach the " + (f == OslCal.Fixture.SERIES ? "Short" : f == OslCal.Fixture.SHUNT ? "Open" : "Open or Short")
                + " and click Perform: the calibration is made from that one standard.");
    }

    private static void show(javafx.scene.Node n, boolean v) {
        n.setVisible(v);
        n.setManaged(v);
    }

    /** [OSLEnterPresetValues]. */
    private void showSet() {
        String name = sets.getValue();
        boolean custom = CUSTOM.equals(name);
        for (TextField t : List.of(openSpec, shortSpec, loadSpec)) t.setEditable(custom);
        for (TextField t : List.of(openSpec, shortSpec, loadSpec)) t.setStyle(custom ? "" : "-fx-opacity: 0.8;");
        saveSet.setDisable(!custom);
        if (custom) {
            openSpec.setText(customOpen);
            shortSpec.setText(customShort);
            loadSpec.setText(customLoad);
            setDescription.setText("Your own standards: edit the specifications.");
            return;
        }
        CalStandards c = standards.stream().filter(x -> x.name().equals(name)).findFirst().orElse(CalStandards.ideal50());
        openSpec.setText(c.open());
        shortSpec.setText(c.shortSpec());
        loadSpec.setText(c.load());
        setDescription.setText(c.description());
    }

    private CalStandards currentStandards() {
        CalStandards c = new CalStandards(sets.getValue(), "", openSpec.getText().strip(), shortSpec.getText().strip(),
                loadSpec.getText().strip(), null);
        c.openSpec();
        c.shortSpecParsed();
        c.loadSpec();
        return c;
    }

    /** OSLSaveCalSetFile for the custom specifications. */
    private void saveSet() {
        CalStandards c;
        try {
            c = currentStandards();
        } catch (IllegalArgumentException e) {
            status.setText("Invalid standard: " + e.getMessage());
            return;
        }
        TextInputDialog d = new TextInputDialog("My standards");
        d.initOwner(stage);
        d.setTitle("Save Calibration Standards");
        d.setHeaderText("Name of the set (shown in the Standards list):");
        d.showAndWait().map(String::strip).filter(n -> !n.isEmpty()).ifPresent(name -> {
            String file = name.replaceAll("[^A-Za-z0-9_.-]+", "_") + ".txt";
            Path f = session.calSetsFolder().resolve(file);
            try {
                if (Files.exists(f)) {
                    Alert a = new Alert(Alert.AlertType.CONFIRMATION, f + " exists. Replace it?", ButtonType.OK, ButtonType.CANCEL);
                    a.initOwner(stage);
                    if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                }
                new CalStandards(name, "Open " + c.open() + ", Short " + c.shortSpec() + ", Load " + c.load(),
                        c.open(), c.shortSpec(), c.load(), f).write(f);
                standards = session.calStandards();
                sets.getItems().setAll(standards.stream().map(CalStandards::name).toList());
                sets.getItems().add(CUSTOM);
                sets.getSelectionModel().select(name);
                showSet();
                status.setText("Saved " + f);
            } catch (IOException e) {
                status.setText("Could not save: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- measuring

    /** [OSLdoCal]: restart, take the next complete sweep with these settings as the standard's reading. */
    private void measure(Button b, String std) {
        if (waiting != null) {                                   // the button says Abort Cal
            stopWaiting();
            status.setText("Cal Aborted");
            return;
        }
        SweepSettings wanted = session.sweep();
        if (measuredWith != null && !measuredWith.equals(wanted)) {
            open = shortR = load = null;
            for (Label d : List.of(openDone, shortDone, loadDone)) d.setText("");
        }
        SweepData cur = store.current();
        int after = cur == null ? 0 : cur.number();
        waiting = (c, index) -> {
            SweepData last = store.lastComplete();
            if (index >= 0 || last == null || last.number() <= after || !wanted.equals(store.settings())) return;
            stopWaiting();
            took(std, last, wanted);
        };
        store.addChangeListener(waiting);
        running = b;
        b.setText("Abort Cal");
        for (Button x : List.of(doOpen, doShort, doLoad, finish, close, saveBase, clearBase))
            if (x != b) x.setDisable(true);
        status.setText("Measuring the " + std + ": waiting for a complete sweep...");
        session.engine().restart();
    }

    private void stopWaiting() {
        if (waiting != null) store.removeChangeListener(waiting);
        waiting = null;
        if (running != null) running.setText("Perform " + (running == doOpen ? "Open" : running == doShort ? "Short" : "Load"));
        running = null;
        for (Button x : List.of(doOpen, doShort, doLoad, close, saveBase, clearBase)) x.setDisable(false);
        finish.setDisable(!full.isSelected());
        if (!update) showInfo();
    }

    private void took(String std, SweepData d, SweepSettings s) {
        int n = d.size();
        double[] db = new double[n], deg = new double[n];
        for (int i = 0; i < n; i++) {
            db[i] = d.dBm(i);
            deg[i] = d.phase(i);
            if (Double.isNaN(db[i]) || Double.isNaN(deg[i])) {
                status.setText("No calibrated reading at " + TransmissionCalDialog.plainNum(d.frequency(i))
                        + " MHz (path calibration missing?)");
                return;
            }
        }
        OslCal.Reading r = new OslCal.Reading(db, deg);
        freqs = d.frequencies();
        measuredWith = s;
        switch (std) {
            case "Open" -> { open = r; openDone.setText("Done"); }
            case "Short" -> { shortR = r; shortDone.setText("Done"); }
            default -> { load = r; loadDone.setText("Done"); }
        }
        if (update) {
            session.updateOslReference(r);
            host.calibrationChanged();
            host.message("Reflection calibration updated");
            status.setText("Calibration updated with the new " + std + " reading.");
            return;
        }
        if (!full.isSelected()) {
            finish();                                            // reference cal: done after one standard
            return;
        }
        status.setText(std + " done. " + (open != null && shortR != null && load != null ? "Click Done."
                : "Attach the next standard."));
    }

    /** [OSLDone]: compute the calibration and install it as the band calibration. */
    private void finish() {
        boolean isFull = full.isSelected();
        if (isFull && (open == null || shortR == null || load == null)) {
            status.setText("You are missing one of the necessary calibrations for full OSL.");
            return;
        }
        double rr;
        double delay = 0;
        try {
            rr = SweepForm.number("R0", r0.getText());
            if (!(rr > 0)) throw new IllegalArgumentException("R0: must be above 0");
            if (shunt.isSelected() && !isFull) delay = SweepForm.number("Delay", shuntDelay.getText());
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            return;
        }
        try {
            CalStandards stds = currentStandards();
            OslCal c = session.performOslCal(measuredWith, freqs, open, shortR, load, stds, fixture(), rr, delay);
            remember();
            host.calibrationChanged();
            host.message("Reflection calibration done (" + (isFull ? "full OSL" : "reference " + c.refType())
                    + "); Reference To: Band Sweep Cal");
            stage.close();
        } catch (IllegalArgumentException e) {
            status.setText("Calibration failed: " + e.getMessage());
            if (!isFull) {
                open = shortR = load = null;
                for (Label d : List.of(openDone, shortDone, loadDone)) d.setText("");
            }
        }
    }

    private void remember() {
        lastFull = full.isSelected();
        lastSet = sets.getValue();
        if (CUSTOM.equals(lastSet)) {
            customOpen = openSpec.getText().strip();
            customShort = shortSpec.getText().strip();
            customLoad = loadSpec.getText().strip();
        }
    }

    // ---------------------------------------------------------------- band / base

    private void showInfo() {
        var rc = session.reflectionCal();
        bandInfo.setText(rc.band() == null ? "Calibration is cleared." : rc.band().info()
                + (rc.band().matches(session.sweep()) ? "" : " (not the current sweep)"));
        baseInfo.setText(rc.base() == null ? "Calibration is cleared." : rc.base().info());
        bandInfo.setWrapText(true);
        baseInfo.setWrapText(true);
        saveBase.setDisable(rc.band() == null || waiting != null);
        clearBase.setDisable(rc.base() == null || waiting != null);
    }

    private void saveBase() {
        try {
            session.reflectionCal().saveBandAsBase();
            status.setText("Saved as base calibration in " + session.reflectionCal().baseFile());
            host.calibrationChanged();
        } catch (IOException | RuntimeException e) {
            status.setText("Could not save: " + e.getMessage());
        }
        showInfo();
    }

    private void clearBase() {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Clear the base reflection calibration and delete "
                + session.reflectionCal().baseFile() + "?", ButtonType.OK, ButtonType.CANCEL);
        a.initOwner(stage);
        a.setHeaderText(null);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            session.reflectionCal().clearBase();
            host.calibrationChanged();
        } catch (IOException e) {
            status.setText("Could not delete: " + e.getMessage());
        }
        showInfo();
    }

    /** ExplainOSL / ExplainJigType. */
    private void help() {
        Alert a = new Alert(Alert.AlertType.INFORMATION,
                "Full OSL calibration measures an Open, a Short and a Load at every step and removes the errors of the"
                + " bridge and cables (directivity, source match, tracking). The standards need not be ideal: describe"
                + " them in the Standards set, e.g. a load that measures 48.68 ohms as RLC[P, R48.68, C0], or an open"
                + " with fringe capacitance as RLC[S, C0, D0] plus C or D. Full OSL treats any fixture as a bridge.\n\n"
                + "Reference calibration measures only one standard, which then is the reference; the others are"
                + " taken as ideal. With a reflection bridge use the Open or the Short; with a series fixture (component"
                + " in series between TG and input) the Short; with a shunt fixture (component to ground) the Open, and"
                + " give the delay of the connection to the component.\n\n"
                + "R0 is the reference resistance of the bridge or fixture. The graphs can be referred to another"
                + " impedance with R0 in the sweep panel.\n\n"
                + "The band calibration applies to this sweep. Saved as base, it is interpolated to other sweeps with"
                + " the same path and fixture (kept in MSA_Info/OperatingCal/BaseOSLCal.txt).", ButtonType.OK);
        a.initOwner(stage);
        a.setTitle("Reflection Calibration Help");
        a.setHeaderText(null);
        a.getDialogPane().setPrefWidth(600);
        a.show();
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, javafx.scene.layout.Priority.ALWAYS);
        return r;
    }

    private static Label wrap(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    private static Label bold(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }
}
