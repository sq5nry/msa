package msa.gui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;

import msa.sweep.Sweeper;
import msa.synth.SynthPlanner;
import msa.trace.SweepData;

/**
 * "Show Variables" ([Showvar]), the synthesiser part: what was commanded and measured at one step,
 * the selected marker's step if there is one, else the last step measured. Updated as the sweep runs.
 */
public final class VariablesWindow {

    private final Stage stage = new Stage();
    private final GridPane grid = new GridPane();
    private final Session session;
    private final GraphState state;
    private final Map<String, Label> values = new LinkedHashMap<>();
    private long lastRefresh;

    public VariablesWindow(Window owner, Session session, GraphState state, Runnable onClose) {
        this.session = session;
        this.state = state;
        stage.initOwner(owner);
        stage.setTitle("Variables");
        grid.setHgap(14);
        grid.setVgap(3);
        grid.setPadding(new Insets(10));
        String[] names = {"Step", "Source", "Frequency (MHz)", "Band", "Equiv. 1G Freq (MHz)", "LO1 (MHz)", "PLL1 N (B, A)",
                "DDS1 (MHz)", "DDS1 word", "LO2 (MHz)", "Real Final I.F. (MHz)", "LO3 (MHz)", "PLL3 N (B, A)", "DDS3 (MHz)",
                "TG / SG out (MHz)", "Mag ADC", "Freq corr (dB)", "Mag (dBm)"};
        int r = 0;
        for (String n : names) {
            Label v = new Label("-");
            v.setFont(Font.font("Monospaced", 12));
            values.put(n, v);
            grid.addRow(r++, new Label(n), v);
        }
        stage.setScene(new Scene(grid));
        stage.setOnHidden(e -> onClose.run());
        refresh();
    }

    public void show() { stage.show(); stage.toFront(); }

    /** Called when data changes; limited to about 5 updates per second. */
    public void refresh() {
        long now = System.nanoTime();
        if (now - lastRefresh < 200_000_000L) return;
        lastRefresh = now;
        SweepData d = state.shown();
        int step = state.selected().flatMap(state.markers::index).map(i -> (int) Math.round(i))
                .orElse(session.engine().snapshot().lastIndex());
        set("Step", step < 0 ? "-" : String.valueOf(step));
        set("Source", state.selected().isPresent() ? "marker " + state.selected().get().label : "last step measured");
        Sweeper.Point p = MainWindow.pointAt(session, step);
        if (p == null) {
            for (String k : values.keySet()) if (!k.equals("Step") && !k.equals("Source")) set(k, "-");
            if (d != null && step >= 0 && step < d.size()) set("Frequency (MHz)", Fmt.mhz(d.frequency(step)));
            return;
        }
        SynthPlanner.StepPlan s = p.plan();
        set("Frequency (MHz)", Fmt.mhz(s.trueFreq()));
        set("Band", s.band() + "G");
        set("Equiv. 1G Freq (MHz)", Fmt.mhz(s.equivFreq()));
        set("LO1 (MHz)", Fmt.mhz(s.lo1()));
        set("PLL1 N (B, A)", s.ncounter1() + " (" + s.bcounter1() + ", " + s.acounter1() + ")");
        set("DDS1 (MHz)", String.format(Locale.ROOT, "%.9f", s.dds1Output()));
        set("DDS1 word", String.format(Locale.ROOT, "0x%08X", s.dds1Base()));
        set("LO2 (MHz)", Fmt.mhz(s.lo2()));
        set("Real Final I.F. (MHz)", Fmt.mhz(s.realFinalIf()));
        set("LO3 (MHz)", s.lo3() == 0 ? "-" : Fmt.mhz(s.lo3()));
        set("PLL3 N (B, A)", s.lo3() == 0 ? "-" : s.ncounter3() + " (" + s.bcounter3() + ", " + s.acounter3() + ")");
        set("DDS3 (MHz)", s.dds3Output() == 0 ? "-" : String.format(Locale.ROOT, "%.9f", s.dds3Output()));
        set("TG / SG out (MHz)", s.lo3() == 0 ? "-" : Fmt.mhz(s.generatorOut()));
        set("Mag ADC", String.valueOf(p.adc().adc1()));
        set("Freq corr (dB)", Double.isNaN(p.freqCorrDb()) ? "no cal" : String.format(Locale.ROOT, "%.3f", p.freqCorrDb()));
        set("Mag (dBm)", Double.isNaN(p.dBm()) ? "no cal" : String.format(Locale.ROOT, "%.5f", p.dBm()));
    }

    private void set(String k, String v) { values.get(k).setText(v); }
}
