package msa.gui;

import java.io.IOException;
import java.util.Locale;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import msa.model.MsaSettings;
import msa.sweep.MsaMode;
import msa.sweep.SweepSettings;
import msa.trace.SweepData;
import msa.trace.TraceStore;
import msa.vna.LineCal;
import msa.vna.TransmissionCal;

/**
 * "Operating Cal &gt; Perform Cal" in the transmission modes ([RunVNACal]) and "Update Cal" ([RunCalUpdate]):
 * a band calibration is one complete sweep with the TG output connected straight through to the MSA input;
 * it can be saved as the base calibration (OperatingCal/BaseLineCal.txt), which is then interpolated to
 * other sweeps.
 *
 * <p>Perform restarts the sweep and takes the first sweep that completes after that; the sweep keeps
 * running afterwards (the BASIC program halts), so the result, a flat line at 0 dB, is seen at once.
 */
final class TransmissionCalDialog {

    /** What the dialog needs from the main window. */
    interface Host {
        /** The calibration or the reference choice changed: recalculate the data on the screen. */
        void calibrationChanged();
        void message(String text);
    }

    private final Stage stage = new Stage();
    private final Session session;
    private final TraceStore store;
    private final Host host;
    private final boolean update;
    private final Label bandInfo = new Label(), baseInfo = new Label(), status = new Label();
    private final TextField delay = new TextField();
    private final Button perform = new Button(), clearBand = new Button("Clear Band Cal"),
            saveBase = new Button("Save As Base"), clearBase = new Button("Clear Base Cal"), done = new Button("Done");
    private TraceStore.ChangeListener waiting;
    private int startedAfter;

    /** @param update "Update Cal": only re-measure the band calibration (it must be current) */
    TransmissionCalDialog(Window owner, Session session, TraceStore store, Host host, boolean update) {
        this.session = session;
        this.store = store;
        this.host = host;
        this.update = update;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(update ? "Update Calibration" : "Perform Calibration");

        SweepSettings s = session.sweep();
        MsaSettings ms = session.settings();
        Label setup = wrap(String.format(Locale.ROOT, "The MSA is in path %d (%s kHz). Wait between steps = %d ms."
                        + " Video filter %s. Calibration is saved only for this path. For maximum smoothing the video"
                        + " filter should be Narrow and the wait 100 to 500 ms; if the path, wait or video filter is not"
                        + " what you want, close this window and change them in the sweep panel.",
                s.path(), plainNum(session.config().filter(s.path()).bwKHz()), s.waitMs(),
                MsaSettings.videoName(s.video(), session.config())));
        Label how = wrap(update
                ? "To update the band calibration, attach the through connection and click Perform Through. This"
                  + " partially adjusts for drift since the full calibration was performed."
                : "Band calibration is run using the steps of the current sweep. You may save the band calibration"
                  + " as a base calibration, used as a coarse reference (interpolated) when the band calibration is"
                  + " not current.");
        Label connect = new Label("TG output must have THROUGH connection to MSA input.");
        connect.setStyle("-fx-font-weight: bold;");

        delay.setPrefColumnCount(6);
        delay.setText(plainNum(ms.vna().throughDelayNs()));
        HBox delayRow = new HBox(6, new Label("Delay of calibration through connection (ns):"), delay);
        delayRow.setAlignment(Pos.CENTER_LEFT);
        delayRow.setVisible(s.mode() == MsaMode.VECTOR_TRANS);
        delayRow.setManaged(s.mode() == MsaMode.VECTOR_TRANS);

        perform.setText(update ? "Perform Through" : "Perform Band Cal");
        perform.setOnAction(e -> { if (waiting != null) abort(); else perform(); });
        clearBand.setOnAction(e -> {
            session.transmissionCal().clearBand();
            host.calibrationChanged();
            showInfo();
        });
        saveBase.setOnAction(e -> saveBase());
        clearBase.setOnAction(e -> clearBase());
        Button help = new Button("Help");
        help.setOnAction(e -> help());
        done.setOnAction(e -> stage.close());
        stage.setOnCloseRequest(e -> { if (waiting != null) e.consume(); });   // not in the middle of a calibration

        GridPane cal = new GridPane();
        cal.setHgap(8);
        cal.setVgap(6);
        cal.addRow(0, bold("Band:"), bandInfo);
        if (!update) {
            cal.add(new HBox(8, perform, clearBand), 1, 1);
            cal.addRow(2, bold("Base:"), baseInfo);
            cal.add(new HBox(8, saveBase, clearBase), 1, 3);
        } else {
            cal.add(perform, 1, 1);
        }
        HBox buttons = new HBox(8, help, done);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(10, setup, how, cal, connect, delayRow, status, buttons);
        root.setPadding(new Insets(12));
        root.setPrefWidth(620);
        stage.setScene(new Scene(root));
        showInfo();
    }

    void show() { stage.show(); }

    private void showInfo() {
        TransmissionCal tc = session.transmissionCal();
        bandInfo.setText(tc.band() == null ? "Calibration is cleared." : tc.band().info()
                + (tc.band().matches(session.sweep()) ? "" : " (not the current sweep)"));
        baseInfo.setText(tc.base() == null ? "Calibration is cleared." : tc.base().info());
        saveBase.setDisable(tc.band() == null || waiting != null);
        clearBase.setDisable(tc.base() == null || waiting != null);
        clearBand.setDisable(tc.band() == null || waiting != null);
    }

    /** [PerformCal] / [PerformLineCalUpdate]: restart, take the next complete sweep. */
    private void perform() {
        double ns;
        try {
            ns = SweepForm.number("Delay", delay.getText());
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            return;
        }
        session.setThroughDelay(session.sweep().mode() == MsaMode.VECTOR_TRANS ? ns : 0);
        SweepData cur = store.current();
        startedAfter = cur == null ? 0 : cur.number();
        SweepSettings wanted = session.sweep();
        waiting = (c, index) -> {
            SweepData last = store.lastComplete();
            if (index >= 0 || last == null || last.number() <= startedAfter || !wanted.equals(store.settings())) return;
            finish(last, wanted);
        };
        store.addChangeListener(waiting);
        perform.setText("Abort Cal");
        done.setDisable(true);
        showInfo();
        status.setText("Calibrating: waiting for a complete sweep...");
        session.engine().restart();
    }

    private void finish(SweepData raw, SweepSettings s) {
        stopWaiting();
        try {
            LineCal c = session.performBandCal(raw, s);
            status.setText("Band calibration done: " + c.steps() + " steps.");
            host.message("Band calibration done; Reference To: Band Sweep Cal");
        } catch (IllegalArgumentException e) {
            status.setText("Calibration failed: " + e.getMessage());
        }
        host.calibrationChanged();
        showInfo();
    }

    private void abort() {
        stopWaiting();
        status.setText("Calibration aborted.");
        showInfo();
    }

    private void stopWaiting() {
        if (waiting != null) store.removeChangeListener(waiting);
        waiting = null;
        perform.setText(update ? "Perform Through" : "Perform Band Cal");
        done.setDisable(false);
    }

    private void saveBase() {
        try {
            session.transmissionCal().saveBandAsBase();
            status.setText("Saved as base calibration in " + session.transmissionCal().baseFile());
            host.calibrationChanged();
        } catch (IOException | RuntimeException e) {
            status.setText("Could not save: " + e.getMessage());
        }
        showInfo();
    }

    private void clearBase() {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Clear the base calibration and delete "
                + session.transmissionCal().baseFile() + "?", ButtonType.OK, ButtonType.CANCEL);
        a.initOwner(stage);
        a.setHeaderText(null);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            session.transmissionCal().clearBase();
            host.calibrationChanged();
        } catch (IOException e) {
            status.setText("Could not delete: " + e.getMessage());
        }
        showInfo();
    }

    /** ExplainTransCal. */
    private void help() {
        Alert a = new Alert(Alert.AlertType.INFORMATION,
                "Band calibration is performed at the frequency points of immediate interest and is used only as long as"
                + " the sweep matches those points. Base calibration is performed over a broad frequency range, to be"
                + " interpolated to the current sweep frequencies when there is no current band calibration.\n\n"
                + "To create a base calibration you perform a band calibration and save it as a base calibration. It is"
                + " intended as a convenient coarse reference, especially when phase precision is not required. Base"
                + " calibrations are saved in a file for use in future sessions.\n\n"
                + "In VNA transmission you also specify the time delay of the calibration through connection, which is"
                + " ideally zero but may be greater if you need to use an adapter.", ButtonType.OK);
        a.initOwner(stage);
        a.setTitle("Transmission Calibration Help");
        a.setHeaderText(null);
        a.getDialogPane().setPrefWidth(560);
        a.show();
    }

    private static Label wrap(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        return l;
    }

    private static Label bold(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    static String plainNum(double v) {
        String s = String.format(Locale.ROOT, "%.6f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
