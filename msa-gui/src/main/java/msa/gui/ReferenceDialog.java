package msa.gui;

import java.util.Locale;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import msa.model.DisplaySettings;
import msa.trace.GraphData;
import msa.trace.Reference;

/**
 * "Reference Line Specification" (ReferenceDialog), SA parts: no reference, current data or a fixed value,
 * per axis, and whether to graph data and reference or the sum/difference. RLC and Smith chart references
 * belong to the VNA modes and are not ported; reference colours and widths are appearance settings.
 */
public final class ReferenceDialog extends Dialog<ReferenceDialog.Result> {

    /** @param updateData "Close; Update Data" (capture the current data now) rather than "Close; Keep Data" */
    public record Result(GraphState.ReferenceSpec spec, boolean updateData) {}

    private static final ButtonType KEEP = new ButtonType("Close; Keep Data", ButtonBar.ButtonData.OTHER);
    private static final ButtonType UPDATE = new ButtonType("Close; Update Data", ButtonBar.ButtonData.OK_DONE);

    public ReferenceDialog(Window owner, GraphState.ReferenceSpec spec, DisplaySettings d) {
        initOwner(owner);
        setTitle("Reference Line Specification");
        Label intro = new Label("You may create reference lines from fixed values or the current data. You may select to "
                + "graph the reference and the input data, or to graph the result of adding or subtracting them.");
        intro.setWrapText(true);
        intro.setPrefWidth(460);

        ToggleGroup kind = new ToggleGroup();
        RadioButton none = radio("No Reference Lines", kind), data = radio("Use Current Data", kind), fixed = radio("Use Fixed Value", kind);
        (switch (spec.kind()) {
            case NONE -> none;
            case CURRENT_DATA -> data;
            case FIXED -> fixed;
        }).setSelected(true);

        GraphData g1 = GraphData.ofCode(d.y1Data()), g2 = GraphData.ofCode(d.y2Data());
        CheckBox t2 = new CheckBox("Do Trace for Right Axis" + unit(g2)), t1 = new CheckBox("Do Trace for Left Axis" + unit(g1));
        t2.setSelected(spec.y2());
        t1.setSelected(spec.y1());
        t2.setDisable(g2 == GraphData.NONE);
        t1.setDisable(g1 == GraphData.NONE);
        TextField v2 = new TextField(plain(spec.fixedY2())), v1 = new TextField(plain(spec.fixedY1()));
        v1.setPrefColumnCount(8);
        v2.setPrefColumnCount(8);
        GridPane traces = new GridPane();
        traces.setHgap(8);
        traces.setVgap(6);
        traces.addRow(0, t2, new Label("Ref Val"), v2);
        traces.addRow(1, t1, new Label("Ref Val"), v1);

        ToggleGroup opt = new ToggleGroup();
        RadioButton both = radio("Data and Ref", opt), add = radio("Data + Ref", opt), sub = radio("Data - Ref", opt),
                rev = radio("Ref - Data", opt);
        Reference.TraceMath m = spec.math();
        (m.equals(Reference.TraceMath.ADD_REFERENCE) ? add : m.equals(Reference.TraceMath.SUBTRACT_REFERENCE) ? sub
                : m.equals(Reference.TraceMath.REFERENCE_MINUS_DATA) ? rev : both).setSelected(true);
        TitledPane options = new TitledPane("Graph Options", new VBox(4, both, add, sub, rev));
        options.setCollapsible(false);

        Runnable enable = () -> {
            boolean f = fixed.isSelected();
            v1.setDisable(!f || t1.isDisabled());
            v2.setDisable(!f || t2.isDisabled());
        };
        fixed.setOnAction(e -> enable.run());
        data.setOnAction(e -> enable.run());
        none.setOnAction(e -> enable.run());
        enable.run();

        VBox box = new VBox(10, intro, new HBox(14, none, data, fixed), traces, options);
        box.setPadding(new Insets(10));
        getDialogPane().setContent(box);
        getDialogPane().getButtonTypes().addAll(KEEP, UPDATE, ButtonType.CANCEL);

        Result[] out = new Result[1];
        for (ButtonType bt : new ButtonType[] {KEEP, UPDATE}) {
            Button b = (Button) getDialogPane().lookupButton(bt);
            b.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
                try {
                    GraphState.ReferenceSpec.Kind k = none.isSelected() ? GraphState.ReferenceSpec.Kind.NONE
                            : data.isSelected() ? GraphState.ReferenceSpec.Kind.CURRENT_DATA : GraphState.ReferenceSpec.Kind.FIXED;
                    double f1 = k == GraphState.ReferenceSpec.Kind.FIXED && !t1.isDisabled() ? SweepForm.number("Left Ref Val", v1.getText()) : spec.fixedY1();
                    double f2 = k == GraphState.ReferenceSpec.Kind.FIXED && !t2.isDisabled() ? SweepForm.number("Right Ref Val", v2.getText()) : spec.fixedY2();
                    Reference.TraceMath math = add.isSelected() ? Reference.TraceMath.ADD_REFERENCE
                            : sub.isSelected() ? Reference.TraceMath.SUBTRACT_REFERENCE
                            : rev.isSelected() ? Reference.TraceMath.REFERENCE_MINUS_DATA : Reference.TraceMath.NONE;
                    out[0] = new Result(new GraphState.ReferenceSpec(k, t1.isSelected() && !t1.isDisabled(),
                            t2.isSelected() && !t2.isDisabled(), f1, f2, math), bt == UPDATE);
                } catch (IllegalArgumentException e) {
                    Alert a = new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
                    a.initOwner(getDialogPane().getScene().getWindow());
                    a.setHeaderText(null);
                    a.showAndWait();
                    ev.consume();
                }
            });
        }
        setResultConverter(b -> b == KEEP || b == UPDATE ? out[0] : null);
    }

    private static String unit(GraphData g) { return g == GraphData.NONE ? " (off)" : " (" + g.unit + ")"; }

    private static RadioButton radio(String text, ToggleGroup g) {
        RadioButton r = new RadioButton(text);
        r.setToggleGroup(g);
        return r;
    }

    private static String plain(double v) {
        String s = String.format(Locale.ROOT, "%.6g", v);
        return s.contains(".") && !s.contains("e") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }
}
