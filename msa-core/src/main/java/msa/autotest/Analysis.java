package msa.autotest;

import java.util.Arrays;

/** Small numeric helpers for evaluating sweeps. NaN entries are ignored everywhere. */
public final class Analysis {

    private Analysis() {}

    /** Index of the largest value, or -1 if all are NaN. */
    public static int peak(double[] y) {
        int best = -1;
        for (int i = 0; i < y.length; i++) if (!Double.isNaN(y[i]) && (best < 0 || y[i] > y[best])) best = i;
        return best;
    }

    public static double max(double[] y) { int i = peak(y); return i < 0 ? Double.NaN : y[i]; }

    public static double min(double[] y) {
        double m = Double.NaN;
        for (double v : y) if (!Double.isNaN(v) && (Double.isNaN(m) || v < m)) m = v;
        return m;
    }

    public static double mean(double[] y) {
        double s = 0;
        int n = 0;
        for (double v : y) if (!Double.isNaN(v)) { s += v; n++; }
        return n == 0 ? Double.NaN : s / n;
    }

    /** Sample standard deviation. */
    public static double std(double[] y) {
        double m = mean(y), s = 0;
        int n = 0;
        for (double v : y) if (!Double.isNaN(v)) { s += (v - m) * (v - m); n++; }
        return n < 2 ? Double.NaN : Math.sqrt(s / (n - 1));
    }

    public static double median(double[] y) {
        double[] v = Arrays.stream(y).filter(d -> !Double.isNaN(d)).sorted().toArray();
        if (v.length == 0) return Double.NaN;
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }

    /** Values of y whose x lies in [lo, hi]. */
    public static double[] within(double[] x, double[] y, double lo, double hi) {
        double[] out = new double[y.length];
        int n = 0;
        for (int i = 0; i < y.length; i++) if (x[i] >= lo && x[i] <= hi) out[n++] = y[i];
        return Arrays.copyOf(out, n);
    }

    /**
     * Where the curve crosses {@code level} on each side of the peak, interpolated between steps:
     * {left x, right x}; NaN on a side where it does not get that low inside the sweep.
     */
    public static double[] crossings(double[] x, double[] y, double level) {
        int p = peak(y);
        double l = Double.NaN, r = Double.NaN;
        if (p < 0) return new double[] {l, r};
        for (int i = p; i > 0; i--) {
            if (!Double.isNaN(y[i - 1]) && y[i - 1] < level) {
                l = x[i - 1] + (level - y[i - 1]) * (x[i] - x[i - 1]) / (y[i] - y[i - 1]);
                break;
            }
        }
        for (int i = p; i < y.length - 1; i++) {
            if (!Double.isNaN(y[i + 1]) && y[i + 1] < level) {
                r = x[i] + (y[i] - level) * (x[i + 1] - x[i]) / (y[i] - y[i + 1]);
                break;
            }
        }
        return new double[] {l, r};
    }

    /** Phase in degrees made continuous: steps of more than 180 degrees are taken as wraps. */
    public static double[] unwrap(double[] deg) {
        double[] u = deg.clone();
        double offset = 0, prev = Double.NaN;
        for (int i = 0; i < u.length; i++) {
            if (Double.isNaN(deg[i])) continue;
            if (!Double.isNaN(prev)) {
                double d = deg[i] - prev;
                if (d > 180) offset -= 360;
                else if (d < -180) offset += 360;
            }
            prev = deg[i];
            u[i] = deg[i] + offset;
        }
        return u;
    }

    /** Least-squares line y = a + b x: {a, b}. */
    public static double[] fitLine(double[] x, double[] y) {
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int n = 0;
        for (int i = 0; i < x.length; i++) {
            if (Double.isNaN(y[i])) continue;
            sx += x[i]; sy += y[i]; sxx += x[i] * x[i]; sxy += x[i] * y[i];
            n++;
        }
        double den = n * sxx - sx * sx;
        if (n < 2 || den == 0) return new double[] {Double.NaN, Double.NaN};
        double b = (n * sxy - sx * sy) / den;
        return new double[] {(sy - b * sx) / n, b};
    }

    /** y - (a + b x). */
    public static double[] residuals(double[] x, double[] y, double[] line) {
        double[] r = new double[y.length];
        for (int i = 0; i < y.length; i++) r[i] = y[i] - (line[0] + line[1] * x[i]);
        return r;
    }

    /** Root mean square. */
    public static double rms(double[] y) {
        double s = 0;
        int n = 0;
        for (double v : y) if (!Double.isNaN(v)) { s += v * v; n++; }
        return n == 0 ? Double.NaN : Math.sqrt(s / n);
    }

    /** A stored curve {x[], y[]} at other x values: linear, NaN outside its range. */
    public static double[] resample(double[][] curve, double[] x) {
        double[] out = new double[x.length];
        double[] cx = curve[0], cy = curve[1];
        for (int i = 0; i < x.length; i++) {
            double v = Double.NaN;
            if (cx.length > 0 && x[i] >= cx[0] - 1e-12 && x[i] <= cx[cx.length - 1] + 1e-12) {
                int k = Arrays.binarySearch(cx, x[i]);
                if (k >= 0) v = cy[k];
                else {
                    k = -k - 1;
                    if (k <= 0) v = cy[0];
                    else if (k >= cx.length) v = cy[cx.length - 1];
                    else v = cy[k - 1] + (x[i] - cx[k - 1]) * (cy[k] - cy[k - 1]) / (cx[k] - cx[k - 1]);
                }
            }
            out[i] = v;
        }
        return out;
    }
}
