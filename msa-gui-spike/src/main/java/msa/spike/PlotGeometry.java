package msa.spike;

import msa.trace.GraphData;
import msa.trace.Markers;
import msa.trace.SweepData;

/**
 * Toolkit-neutral plot maths: grid, scaling and the polyline, so both trials draw exactly the same.
 * x: frequency over the sweep span; y: dBm over a fixed -120..0 dBm axis (10 divisions each way).
 */
final class PlotGeometry {
    static final int LEFT = 60, RIGHT = 20, TOP = 40, BOTTOM = 40, DIVS = 10;
    static final double Y_MIN = -120, Y_MAX = 0;

    final double w, h, plotW, plotH;

    PlotGeometry(double width, double height) {
        w = width;
        h = height;
        plotW = Math.max(1, width - LEFT - RIGHT);
        plotH = Math.max(1, height - TOP - BOTTOM);
    }

    double x(double index, int points) { return LEFT + plotW * index / Math.max(1, points - 1); }

    double y(double dBm) {
        double t = (Math.max(Y_MIN, Math.min(Y_MAX, dBm)) - Y_MIN) / (Y_MAX - Y_MIN);
        return TOP + plotH * (1 - t);
    }

    /** Polyline vertices for the measured points: {x0, y0, x1, y1, ...}; count = number of points used. */
    record Line(double[] xy, int count) {}

    /** dBm for display; without a path calibration a rough ADC scale (about 328 counts per dB) is used. */
    static double displayDbm(SweepData d, int i) {
        double v = d.value(GraphData.DBM, i);
        return Double.isNaN(v) ? -100 + (d.adc(i) - 10000) / 328.0 : v;
    }

    static Line line(SweepData d, PlotGeometry g) {
        double[] xy = new double[d.size() * 2];
        int k = 0;
        for (int i = 0; i < d.size(); i++) {
            if (!d.isMeasured(i)) continue;
            xy[2 * k] = g.x(i, d.size());
            xy[2 * k + 1] = g.y(displayDbm(d, i));
            k++;
        }
        return new Line(xy, k);
    }

    /** Highest displayed point (P+), using the same plateau rule as the real markers; null if nothing measured. */
    static Markers.Peaks peak(SweepData d) {
        if (d.measuredCount() == 0) return null;
        SweepData shown = new SweepData(d.number(), d.frequencies());
        for (int i = 0; i < d.size(); i++) if (d.isMeasured(i)) shown.set(i, displayDbm(d, i));
        return Markers.findPeaks(shown, GraphData.DBM, 0, d.size() - 1);
    }
}
