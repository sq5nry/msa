package msa.model;

import java.util.Objects;

/**
 * What the graph shows and how (the parts of Prefs.txt's Grid, Trace and Sweep contexts the GUI needs).
 * Everything else in those contexts (fonts, margins, custom colour presets, ...) is kept untouched in
 * {@link msa.config.Preferences}.
 *
 * @param y1Data / y2Data graph data type codes of the BASIC program (GraphData=Y1 Y2), see the DATA_ constants
 * @param y1Axis / y2Axis axis ranges (MinMaxY1Axis / MinMaxY2Axis)
 * @param autoscaleY1 / autoscaleY2 Autoscale=
 * @param y1Linear / y2Linear IsLinear= (2nd and 3rd values)
 * @param refreshEachScan RefreshEachScan= (Trace)
 * @param gridDivX / gridDivY GridDivisions= (Grid)
 * @param background BackColor=, @param gridLine GridLineColor=
 * @param trace1 / trace2 TraceColors= (Trace)
 * @param y1Mode / y2Mode TraceModes= (Trace): 0 off, 1 normal erase, 2 normal stick, 3 histogram erase,
 *                        4 histogram stick (see {@link msa.trace.TraceStore.DisplayMode})
 */
public record DisplaySettings(int y1Data, int y2Data, AxisRange y1Axis, AxisRange y2Axis,
                              boolean autoscaleY1, boolean autoscaleY2, boolean y1Linear, boolean y2Linear,
                              boolean refreshEachScan, int gridDivX, int gridDivY,
                              Rgb background, Rgb gridLine, Rgb trace1, Rgb trace2,
                              int y1Mode, int y2Mode) {

    /** BASIC constGraph... codes relevant to the spectrum analyser. */
    public static final int DATA_DBM = 17, DATA_WATTS = 18, DATA_VOLTS = 19, DATA_NONE = 40;

    public record AxisRange(double min, double max) {
        public AxisRange {
            if (!(max > min)) throw new IllegalArgumentException("axis max must be > min");
        }
    }

    public record Rgb(int r, int g, int b) {
        public Rgb {
            if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) throw new IllegalArgumentException("RGB 0..255");
        }

        /** "0 255 255" as written by the BASIC program. */
        public static Rgb parse(String s) {
            String[] w = s.trim().split("\\s+");
            if (w.length != 3) throw new IllegalArgumentException("not an RGB triple: " + s);
            return new Rgb(Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2]));
        }

        @Override
        public String toString() { return r + " " + g + " " + b; }
    }

    public DisplaySettings {
        Objects.requireNonNull(y1Axis);
        Objects.requireNonNull(y2Axis);
        Objects.requireNonNull(background);
        Objects.requireNonNull(gridLine);
        Objects.requireNonNull(trace1);
        Objects.requireNonNull(trace2);
        if (gridDivX < 1 || gridDivY < 1) throw new IllegalArgumentException("grid divisions must be >= 1");
        if (y1Mode < 0 || y1Mode > 4 || y2Mode < 0 || y2Mode > 4) throw new IllegalArgumentException("trace mode 0..4");
    }

    /** The BASIC program's SA defaults: no Y1 graph, Y2 = dBm from -100 to 0, dark theme. */
    public static DisplaySettings defaults() {
        return new DisplaySettings(DATA_NONE, DATA_DBM, new AxisRange(-1, 0), new AxisRange(-100, 0),
                false, false, true, true, true, 10, 10,
                new Rgb(0, 0, 0), new Rgb(150, 150, 140), new Rgb(0, 255, 255), new Rgb(255, 200, 90), 1, 1);
    }

    public DisplaySettings withY2Axis(AxisRange r) {
        return new DisplaySettings(y1Data, y2Data, y1Axis, r, autoscaleY1, autoscaleY2, y1Linear, y2Linear,
                refreshEachScan, gridDivX, gridDivY, background, gridLine, trace1, trace2, y1Mode, y2Mode);
    }

    public DisplaySettings withY1Axis(AxisRange r) {
        return new DisplaySettings(y1Data, y2Data, r, y2Axis, autoscaleY1, autoscaleY2, y1Linear, y2Linear,
                refreshEachScan, gridDivX, gridDivY, background, gridLine, trace1, trace2, y1Mode, y2Mode);
    }

    public DisplaySettings withGraphData(int y1, int y2) {
        return new DisplaySettings(y1, y2, y1Axis, y2Axis, autoscaleY1, autoscaleY2, y1Linear, y2Linear,
                refreshEachScan, gridDivX, gridDivY, background, gridLine, trace1, trace2, y1Mode, y2Mode);
    }

    public DisplaySettings withTraceModes(int m1, int m2) {
        return new DisplaySettings(y1Data, y2Data, y1Axis, y2Axis, autoscaleY1, autoscaleY2, y1Linear, y2Linear,
                refreshEachScan, gridDivX, gridDivY, background, gridLine, trace1, trace2, m1, m2);
    }

    public DisplaySettings withAutoscale(boolean a1, boolean a2) {
        return new DisplaySettings(y1Data, y2Data, y1Axis, y2Axis, a1, a2, y1Linear, y2Linear,
                refreshEachScan, gridDivX, gridDivY, background, gridLine, trace1, trace2, y1Mode, y2Mode);
    }

    public DisplaySettings withGridDivisions(int x, int y) {
        return new DisplaySettings(y1Data, y2Data, y1Axis, y2Axis, autoscaleY1, autoscaleY2, y1Linear, y2Linear,
                refreshEachScan, x, y, background, gridLine, trace1, trace2, y1Mode, y2Mode);
    }

    public DisplaySettings withRefreshEachScan(boolean r) {
        return new DisplaySettings(y1Data, y2Data, y1Axis, y2Axis, autoscaleY1, autoscaleY2, y1Linear, y2Linear,
                r, gridDivX, gridDivY, background, gridLine, trace1, trace2, y1Mode, y2Mode);
    }
}
