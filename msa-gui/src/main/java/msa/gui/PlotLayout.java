package msa.gui;

import msa.model.DisplaySettings.AxisRange;

/**
 * Where things go on the graph canvas, and pixel/value conversions. The x axis runs by step index, so a
 * log sweep (log-spaced steps) gets a log axis, as in the BASIC program.
 *
 * <pre>
 *   +-------------------------------------------------------------+
 *   | mode / state line                                           |
 *   | Y1 labels |            plot area            | Y2 | setup     |
 *   |           |                                 |    | info      |
 *   |           |      frequency labels           |               |
 *   | marker table                                                |
 *   +-------------------------------------------------------------+
 * </pre>
 */
public record PlotLayout(double width, double height, double left, double top, double right, double bottom) {

    public static final double MARGIN_TOP = 26, MARGIN_LEFT = 62, MARGIN_RIGHT_AXIS = 56, INFO_WIDTH = 118,
            X_LABELS = 22, MARKER_AREA = 96;

    /** Layout for a canvas of this size. */
    public static PlotLayout of(double width, double height) {
        double l = MARGIN_LEFT, t = MARGIN_TOP;
        double r = Math.max(l + 50, width - MARGIN_RIGHT_AXIS - INFO_WIDTH);
        double b = Math.max(t + 50, height - X_LABELS - MARKER_AREA);
        return new PlotLayout(width, height, l, t, r, b);
    }

    public double plotWidth() { return right - left; }
    public double plotHeight() { return bottom - top; }

    /** Pixel x of a (fractional) step index, for a sweep of {@code points} points. */
    public double x(double index, int points) {
        return points <= 1 ? left + plotWidth() / 2 : left + plotWidth() * index / (points - 1);
    }

    /** Step index under a pixel x (fractional, clamped). */
    public double index(double px, int points) {
        if (points <= 1) return 0;
        double i = (px - left) / plotWidth() * (points - 1);
        return Math.max(0, Math.min(points - 1, i));
    }

    /** Pixel y for a value on an axis; values outside the range are clipped to the plot edge. */
    public double y(double v, AxisRange a) {
        double t = (v - a.min()) / (a.max() - a.min());
        t = Math.max(-0.002, Math.min(1.002, t));
        return bottom - t * plotHeight();
    }

    public boolean inPlot(double px, double py) {
        return px >= left && px <= right && py >= top && py <= bottom;
    }

    /** The Y1 (left) axis label strip. */
    public boolean onY1Labels(double px, double py) { return px < left && py >= top - 6 && py <= bottom + 6; }

    /** The Y2 (right) axis label strip. */
    public boolean onY2Labels(double px, double py) {
        return px > right && px < right + MARGIN_RIGHT_AXIS && py >= top - 6 && py <= bottom + 6;
    }

    /** Frequency labels under the plot, or the setup info column: both open Sweep Parameters, as in BASIC. */
    public boolean onSweepArea(double px, double py) {
        boolean xLabels = px >= left - 5 && px <= right + 5 && py > bottom && py < bottom + X_LABELS + 8;
        boolean info = px > right + MARGIN_RIGHT_AXIS && py >= top && py <= bottom;
        return xLabels || info;
    }

    public double infoLeft() { return right + MARGIN_RIGHT_AXIS + 4; }
    public double markerTop() { return bottom + X_LABELS + 2; }
}
