package msa.gui;

import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import msa.model.DisplaySettings;
import msa.model.DisplaySettings.AxisRange;
import msa.sweep.MsaMode;
import msa.trace.AxisScale;
import msa.trace.GraphData;
import msa.trace.SweepData;

/**
 * Y axis window (DisplayAxisYPreference): top/bottom reference, auto scale, graph data, trace width and
 * style, vertical divisions. Trace colour and colour cycling are not ported (appearance settings).
 */
public final class AxisDialog extends Dialog<AxisDialog.Result> {

    public record Result(DisplaySettings display, int traceWidth, String notice) {}

    private final List<GraphData> DATA;

    private final TextField top = new TextField(), bot = new TextField();
    private final CheckBox auto = new CheckBox("Auto Scale");
    private final ComboBox<String> data = new ComboBox<>(), style = new ComboBox<>();
    private final ComboBox<Integer> width = new ComboBox<>(), vDiv = new ComboBox<>();
    private GraphData shownData;

    /**
     * @param axis    1 = Y1 (left), 2 = Y2 (right)
     * @param primary primary axis number: only that one offers histogram styles
     * @param sweep   data for "Auto Scale" (may be null)
     */
    public AxisDialog(Window owner, int axis, DisplaySettings d, int primary, int traceWidth, SweepData sweep) {
        this(owner, axis, d, primary, traceWidth, sweep, MsaMode.SA);
    }

    /** @param mode the graph data offered depend on the mode (FillRegularGraphData) */
    public AxisDialog(Window owner, int axis, DisplaySettings d, int primary, int traceWidth, SweepData sweep, MsaMode msaMode) {
        initOwner(owner);
        setTitle(axis == 1 ? "Y1 Axis (left)" : "Y2 Axis (right)");
        GraphData g = GraphData.ofCode(axis == 1 ? d.y1Data() : d.y2Data());
        AxisRange range = axis == 1 ? d.y1Axis() : d.y2Axis();
        List<GraphData.Choice> choices = GraphData.choices(msaMode);
        DATA = choices.stream().map(GraphData.Choice::data).toList();
        if (!DATA.contains(g)) g = GraphData.NONE;
        shownData = g;
        data.getItems().addAll(choices.stream().map(GraphData.Choice::name).toList());
        data.getSelectionModel().select(DATA.indexOf(g));
        top.setText(plain(range.max()));
        bot.setText(plain(range.min()));
        auto.setSelected(axis == 1 ? d.autoscaleY1() : d.autoscaleY2());
        width.getItems().addAll(0, 1, 2, 3);
        width.setValue(Math.max(0, Math.min(3, traceWidth)));
        if (axis == primary) style.getItems().addAll("Off", "Norm Erase", "Norm Stick", "Histo Erase", "Histo Stick");
        else style.getItems().addAll("Off", "Erase", "Stick");
        int mode = axis == 1 ? d.y1Mode() : d.y2Mode();
        style.getSelectionModel().select(Math.min(mode, style.getItems().size() - 1));
        vDiv.getItems().addAll(4, 5, 6, 8, 10, 12);
        vDiv.setValue(d.gridDivY());

        data.setOnAction(e -> {
            // [axisYChangeGraph]: starting limits for the new data type
            GraphData ng = DATA.get(Math.max(0, data.getSelectionModel().getSelectedIndex()));
            if (ng == GraphData.NONE) auto.setSelected(false);
            if (ng != GraphData.NONE && ng != shownData) {
                AxisRange r = AxisScale.startingLimits(ng, range);
                top.setText(plain(r.max()));
                bot.setText(plain(r.min()));
            }
            shownData = ng;
        });
        auto.setOnAction(e -> {
            // [axisAutoscaleOn]: show the autoscaled range now
            if (!auto.isSelected() || sweep == null) return;
            AxisRange r = AxisScale.autoscale(sweep, shownData);
            if (r != null) {
                top.setText(plain(r.max()));
                bot.setText(plain(r.min()));
            }
        });
        Button def = new Button("Default Graph");
        def.setOnAction(e -> {
            // GetDefaultGraphData (Y2 primary): Y2 dBm or dB -100..0; Y1 phase -180..180 in VNA, else none
            GraphData dg = GraphData.defaultFor(msaMode, axis == 2);
            data.getSelectionModel().select(DATA.indexOf(dg));
            shownData = dg;
            AxisRange r = axis == 2 ? new AxisRange(-100, 0)
                    : dg == GraphData.NONE ? DisplaySettings.defaults().y1Axis() : new AxisRange(-180, 180);
            top.setText(plain(r.max()));
            bot.setText(plain(r.min()));
        });

        GridPane p = new GridPane();
        p.setHgap(8);
        p.setVgap(6);
        p.setPadding(new Insets(10));
        top.setPrefColumnCount(9);
        bot.setPrefColumnCount(9);
        p.addRow(0, new Label("Graph Data"), data, def);
        p.addRow(1, new Label("Top Ref"), top, auto);
        p.addRow(2, new Label("Bot Ref"), bot);
        p.addRow(3, new Label("Trace Width"), width);
        p.addRow(4, new Label("Trace Style"), style);
        p.addRow(5, new Label("Number of Divisions\n(affects both Y axes)"), vDiv);
        getDialogPane().setContent(p);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Result[] out = new Result[1];
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                out[0] = result(axis, d, range);
            } catch (IllegalArgumentException e) {
                Alert a = new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
                a.initOwner(getDialogPane().getScene().getWindow());
                a.setHeaderText(null);
                a.showAndWait();
                ev.consume();
            }
        });
        setResultConverter(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE ? out[0] : null);
    }

    /** [axisYFinished]. */
    private Result result(int axis, DisplaySettings d, AxisRange old) {
        double t = SweepForm.number("Top Ref", top.getText()), b = SweepForm.number("Bot Ref", bot.getText());
        if (t < b) { double x = t; t = b; b = x; }
        String notice = null;
        AxisRange r;
        if (t == b) {
            r = old;
            notice = "Axis range cannot be zero. Previous values retained.";
        } else {
            r = new AxisRange(b, t);
        }
        GraphData g = DATA.get(Math.max(0, data.getSelectionModel().getSelectedIndex()));
        int mode = Math.max(0, style.getSelectionModel().getSelectedIndex());
        if (g == GraphData.NONE) mode = 1;
        boolean a = auto.isSelected() && g != GraphData.NONE;
        DisplaySettings n = d.withGridDivisions(d.gridDivX(), vDiv.getValue() == null ? d.gridDivY() : vDiv.getValue());
        if (axis == 1) {
            n = n.withY1Axis(r).withGraphData(g.code, n.y2Data()).withAutoscale(a, n.autoscaleY2()).withTraceModes(mode, n.y2Mode());
        } else {
            n = n.withY2Axis(r).withGraphData(n.y1Data(), g.code).withAutoscale(n.autoscaleY1(), a).withTraceModes(n.y1Mode(), mode);
        }
        return new Result(n, width.getValue() == null ? 1 : width.getValue(), notice);
    }

    private static String plain(double v) {
        if (v != 0 && Math.abs(v) < 0.01) return String.format(java.util.Locale.ROOT, "%.4g", v);
        String s = String.format(java.util.Locale.ROOT, "%.6f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
