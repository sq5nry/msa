package msa.gui;

import java.util.Locale;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import msa.sweep.PhaseDetector;
import msa.sweep.SweepEngine;

/**
 * "Setup &gt; PDM Calibration" ([menuCalPDM]): measures how far the phase detector's reading moves when its
 * reference is inverted (nominally 180 degrees), and saves it as config.txt "invdeg". The measurement
 * reads the phase at the current (halted) frequency with the PDM normal and inverted, 5 s settling each.
 */
final class PdmCalDialog {

    private final Stage stage = new Stage();
    private final Label value = new Label(), result = new Label();
    private final Button measure = new Button("PDM Inversion Cal"), save = new Button("Save New Value and Quit"),
            cancel = new Button("Cancel");
    private double measured = Double.NaN;

    PdmCalDialog(Window owner, Session session, java.util.function.Supplier<SweepEngine.State> engineState,
                 java.util.function.Consumer<String> message) {
        stage.initOwner(owner);
        stage.setTitle("PDM Calibration");
        Label text = new Label("The actual phase shift caused by PDM inversion will differ from the theoretical 180"
                + " degrees. This one-time PDM calibration is required to determine the actual phase shift. The"
                + " software will use this calibration phase shift when measuring signal phase. It is important that"
                + " this PDM Calibration be done before the Path Calibrations are performed, and should be performed"
                + " in the Path used for VNA, usually the widest BW. To perform this calibration:");
        text.setWrapText(true);
        text.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label steps = new Label("""
                • Connect Tracking Generator output to MSA input with 1 to 2 foot cable.
                • Set Video Filter to Wide. MSA mode VNA Transmission, Halt.
                • Operating Cal > Reference To: No Reference.
                • Sweep 0-200 MHz. Pick a frequency with its phase near -90, +90 or +270 degrees.
                • Centre the sweep at that frequency, zero span. Video Filter to Narrow.
                • Restart the sweep to verify, then Halt and click PDM Inversion Cal (takes about 10 s).""");
        steps.setWrapText(true);
        steps.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        value.setText(String.format(Locale.ROOT, "Current inversion = %.2f deg.", PhaseDetector.of(session.config()).invDeg()));
        measure.setOnAction(e -> {
            if (engineState.get() != SweepEngine.State.HALTED) {
                result.setText("Halt the sweep first (at the frequency chosen above).");
                return;
            }
            measure.setText("Be Patient");
            measure.setDisable(true);
            save.setDisable(true);
            cancel.setDisable(true);
            result.setText("Measuring, about 10 s...");
            session.engine().calibratePdm().whenComplete((c, err) -> Platform.runLater(() -> {
                measure.setText("PDM Inversion Cal");
                measure.setDisable(false);
                cancel.setDisable(false);
                if (err != null) {
                    result.setText("Failed: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()));
                    return;
                }
                measured = c.invDeg();
                save.setDisable(false);
                steps.setWrapText(true);
        steps.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        value.setText(String.format(Locale.ROOT, "Current inversion = %.2f deg.", measured));
                boolean nearQuadrature = Math.abs(Math.abs(c.phaseNormalDeg() - 180) - 90) <= 20;
                result.setText(String.format(Locale.ROOT, "Phase normal %.2f, inverted %.2f degrees.%s",
                        c.phaseNormalDeg(), c.phaseInvertedDeg(),
                        nearQuadrature ? "" : "  The normal phase should be near 90 or 270 degrees: pick another frequency."));
            }));
        });
        save.setDisable(true);
        save.setOnAction(e -> {
            if (Double.isNaN(measured)) return;
            try {
                session.savePdmInversion(measured);
                message.accept(String.format(Locale.ROOT, "PDM inversion %.2f degrees saved in config.txt", measured));
                stage.close();
            } catch (java.io.IOException ex) {
                result.setText("Could not save config.txt: " + ex.getMessage());
            }
        });
        cancel.setOnAction(e -> stage.close());
        stage.setOnCloseRequest(e -> { if (cancel.isDisabled()) e.consume(); });

        HBox buttons = new HBox(10, save, cancel);
        buttons.setAlignment(Pos.CENTER);
        VBox root = new VBox(10, text, steps, new HBox(12, measure, value), result, buttons);
        ((HBox) root.getChildren().get(2)).setAlignment(Pos.CENTER_LEFT);
        result.setWrapText(true);
        result.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        root.setPadding(new Insets(12));
        root.setPrefWidth(660);
        stage.setScene(new Scene(root));
    }

    void show() { stage.show(); }
}
