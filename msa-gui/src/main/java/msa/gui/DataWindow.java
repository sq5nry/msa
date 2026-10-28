package msa.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import msa.model.DisplaySettings;
import msa.trace.GraphData;
import msa.trace.SweepData;

/**
 * "Data > Graph Data" (values as graphed, after reference math) and "Data > Input Data" (raw ADC and
 * calibrated power) as tables of the last sweep, with Refresh and Copy (tab-separated, for spreadsheets).
 */
public final class DataWindow {

    private record Column(String title, Function<Integer, String> value) {}

    private final Stage stage = new Stage();
    private final TableView<Integer> table = new TableView<>();
    private final Label info = new Label();
    private final java.util.function.Supplier<SweepData> source;
    private final java.util.function.Supplier<List<Column>> columns;

    private DataWindow(Window owner, String title, java.util.function.Supplier<SweepData> source,
                       java.util.function.Supplier<List<Column>> columns) {
        this.source = source;
        this.columns = columns;
        stage.initOwner(owner);
        stage.setTitle(title);
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> fill());
        Button copy = new Button("Copy");
        copy.setOnAction(e -> copy());
        HBox bar = new HBox(8, refresh, copy, info);
        bar.setPadding(new Insets(6));
        BorderPane root = new BorderPane(table);
        root.setTop(bar);
        stage.setScene(new Scene(root, 560, 520));
        fill();
    }

    public void show() { stage.show(); stage.toFront(); }

    private SweepData data;
    private List<Column> cols;

    private void fill() {
        data = source.get();
        cols = columns.get();
        table.getColumns().clear();
        for (Column c : cols) {
            TableColumn<Integer, String> tc = new TableColumn<>(c.title());
            tc.setCellValueFactory(f -> new ReadOnlyStringWrapper(c.value().apply(f.getValue())));
            tc.setSortable(false);
            table.getColumns().add(tc);
        }
        List<Integer> rows = new ArrayList<>();
        if (data != null) for (int i = 0; i < data.size(); i++) rows.add(i);
        table.setItems(FXCollections.observableArrayList(rows));
        info.setText(data == null ? "no data yet" : "sweep " + data.number() + (data.complete() ? "" : " (in progress)")
                + ", " + data.measuredCount() + " of " + data.size() + " points");
    }

    private void copy() {
        if (data == null) return;
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cols.size(); c++) sb.append(c == 0 ? "" : "\t").append(cols.get(c).title());
        sb.append('\n');
        for (int i = 0; i < data.size(); i++) {
            for (int c = 0; c < cols.size(); c++) sb.append(c == 0 ? "" : "\t").append(cols.get(c).value().apply(i));
            sb.append('\n');
        }
        ClipboardContent cc = new ClipboardContent();
        cc.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private static SweepData last(GraphState st) {
        return st.store.lastComplete() != null ? st.store.lastComplete() : st.shown();
    }

    /** [DataWin_GraphData]: step, frequency, Y1 and Y2 as graphed. */
    public static DataWindow graphData(Window owner, Session s, GraphState st) {
        java.util.function.Supplier<SweepData> src = () -> last(st);
        return new DataWindow(owner, "Graph Data", src, () -> {
            DisplaySettings d = s.display();
            GraphData g1 = GraphData.ofCode(d.y1Data()), g2 = GraphData.ofCode(d.y2Data());
            List<Column> c = new ArrayList<>();
            c.add(new Column("Step", i -> String.valueOf(i)));
            c.add(new Column("Freq (MHz)", i -> Fmt.mhz(src.get().frequency(i))));
            if (g1 != GraphData.NONE) c.add(new Column("Y1 " + g1.unit, i -> Fmt.value(g1, st.value(src.get(), g1, 1, i))));
            if (g2 != GraphData.NONE) c.add(new Column("Y2 " + g2.unit, i -> Fmt.value(g2, st.value(src.get(), g2, 2, i))));
            return c;
        });
    }

    /**
     * [MSAinputData]: step, frequency, band, ADC magnitude reading and calibrated power; in the VNA modes
     * also the phase ADC reading, the PDM state and the phase before the operating calibration.
     */
    public static DataWindow inputData(Window owner, Session s, GraphState st) {
        java.util.function.Supplier<SweepData> src = () -> last(st);
        return new DataWindow(owner, "Input Data", src, () -> {
            List<Column> c = new ArrayList<>(List.of(
                    new Column("Step", i -> String.valueOf(i)),
                    new Column("Freq (MHz)", i -> Fmt.mhz(src.get().frequency(i))),
                    new Column("Band", i -> src.get().isMeasured(i) && src.get().band(i) > 0 ? src.get().band(i) + "G" : ""),
                    new Column("Mag ADC", i -> src.get().isMeasured(i) ? String.valueOf(src.get().adc(i)) : "-"),
                    new Column("Mag (dBm)", i -> src.get().isMeasured(i)
                            ? String.format(Locale.ROOT, "%.5f", src.get().dBm(i)) : "-")));
            if (s.mode().readsPhase()) {
                c.add(new Column("Phase ADC", i -> src.get().isMeasured(i) ? String.valueOf(src.get().phaseAdc(i)) : "-"));
                c.add(new Column("PDM", i -> src.get().isMeasured(i) ? (src.get().pdm(i) == 1 ? "Inv" : "Norm") : "-"));
                c.add(new Column("Phase (deg)", i -> src.get().isMeasured(i) && !Double.isNaN(src.get().phase(i))
                        ? String.format(Locale.ROOT, "%.2f", src.get().phase(i)) : "-"));
            }
            return c;
        });
    }

    /**
     * [MagPhaS21] and [LineCalArray]: S21 as referenced, and the reference (installed line calibration) at
     * each step.
     */
    public static DataWindow s21Data(Window owner, Session s, GraphState st) {
        java.util.function.Supplier<SweepData> src = () -> last(st);
        return new DataWindow(owner, "S21 Parameters", src, () -> {
            boolean vector = s.mode().readsPhase();
            List<Column> c = new ArrayList<>();
            c.add(new Column("Step", i -> String.valueOf(i)));
            c.add(new Column("Freq (MHz)", i -> Fmt.mhz(src.get().frequency(i))));
            c.add(new Column("S21 (dB)", i -> src.get().isMeasured(i) ? String.format(Locale.ROOT, "%.5f", src.get().s21Db(i)) : "-"));
            if (vector) c.add(new Column("S21 (deg)", i -> src.get().isMeasured(i) ? String.format(Locale.ROOT, "%.2f", src.get().s21Deg(i)) : "-"));
            c.add(new Column("Ref (dB)", i -> {
                double[] r = src.get().correction().db();
                return r == null ? "none" : String.format(Locale.ROOT, "%.5f", r[i]);
            }));
            if (vector) c.add(new Column("Ref (deg)", i -> {
                double[] r = src.get().correction().deg();
                return r == null ? "none" : String.format(Locale.ROOT, "%.2f", r[i]);
            }));
            return c;
        });
    }
}
