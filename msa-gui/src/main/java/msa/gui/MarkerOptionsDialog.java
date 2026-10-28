package msa.gui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import msa.trace.Markers;

/** "Marker Options" (mMenuMarkerOptions). */
public final class MarkerOptionsDialog extends Dialog<MarkerOptionsDialog.Result> {

    public record Result(boolean show, Markers.LrMode mode) {}

    public MarkerOptionsDialog(Window owner, boolean show, Markers.LrMode mode) {
        initOwner(owner);
        setTitle("Marker Options");
        CheckBox showBox = new CheckBox("Show Markers On Graph");
        showBox.setSelected(show);
        ToggleGroup g = new ToggleGroup();
        RadioButton indep = radio("L,R Independent", g), bounded = radio("P+, P- bounded by L,R", g),
                down3 = radio("L,R -3dB re P+", g), up3 = radio("L,R 3dB re P-", g);
        (switch (mode) {
            case INDEPENDENT -> indep;
            case PEAKS_BOUNDED -> bounded;
            case MINUS_3DB_FROM_PEAK -> down3;
            case PLUS_3DB_FROM_DIP -> up3;
        }).setSelected(true);
        TitledPane lr = new TitledPane("L,R Markers", new VBox(6, indep, bounded, down3, up3));
        lr.setCollapsible(false);
        VBox box = new VBox(10, showBox, lr);
        box.setPadding(new Insets(10));
        getDialogPane().setContent(box);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(b -> {
            if (b.getButtonData() != ButtonBar.ButtonData.OK_DONE) return null;
            Markers.LrMode m = bounded.isSelected() ? Markers.LrMode.PEAKS_BOUNDED
                    : down3.isSelected() ? Markers.LrMode.MINUS_3DB_FROM_PEAK
                    : up3.isSelected() ? Markers.LrMode.PLUS_3DB_FROM_DIP : Markers.LrMode.INDEPENDENT;
            return new Result(showBox.isSelected(), m);
        });
    }

    private static RadioButton radio(String text, ToggleGroup g) {
        RadioButton r = new RadioButton(text);
        r.setToggleGroup(g);
        return r;
    }
}
