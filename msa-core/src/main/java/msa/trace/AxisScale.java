package msa.trace;

import msa.model.DisplaySettings.AxisRange;

/**
 * Y-axis range rules of the BASIC program for the spectrum-analyser and transmission data types:
 * {@link #autoscale} = sub CalcAutoScale, {@link #startingLimits} = sub StartingLimits (the range set when the
 * graph data type of an axis is changed in the axis dialog).
 */
public final class AxisScale {

    private AxisScale() {}

    /**
     * CalcAutoScale for one sweep: dBm is rounded out to multiples of 5 dB (with 0.5 dB headroom);
     * watts and volts are rounded out to powers of ten, never above 1, starting at 0 unless the range is narrow.
     *
     * @return the new range, or null if the sweep has no measured point
     */
    public static AxisRange autoscale(SweepData d, GraphData g) {
        if (d == null || g == GraphData.NONE) return null;
        Markers.Peaks pk = Markers.findPeaks(d, g, 0, d.size() - 1);
        if (pk == null) return null;
        double min = pk.min(), max = pk.max();
        switch (g) {
            case DBM, DB, INSERTION_LOSS -> {              // multiples of 5 dB
                min = roundDownToMultiple(min - 0.5, 5);
                max = roundUpToMultiple(max + 0.5, 5);
            }
            case ANGLE, RAW_ANGLE -> {                    // multiples of 15 degrees, or the full circle
                min = Math.max(-180, roundDownToMultiple(min - 5, 15));
                max = Math.min(180, roundUpToMultiple(max + 5, 15));
                if (max - min > 120) { min = -180; max = 180; }
            }
            case WATTS, VOLTS, RATIO -> {
                if (max > 1) max = 1;
                min = roundDownToPower(min, 10);
                max = roundUpToPower(max, 10);
                if (min > 0 && max / min >= 2) min = 0;
            }
            default -> {                                   // group delay: wide range of either sign
                if (max > 1000000) max = 1;
                min = min < 0 ? min * 1.1 : min * 0.9;
                max = max > 0 ? max * 1.1 : max * 0.9;
                min = roundDownToPower(min, 10);
                max = roundUpToPower(max, 10);
            }
        }
        boolean db = g == GraphData.DBM || g == GraphData.DB || g == GraphData.INSERTION_LOSS;
        if (!(max > min)) max = min + (db ? 5 : Math.max(Math.abs(min), 1e-12));
        return new AxisRange(min, max);
    }

    /**
     * StartingLimits: dBm and dB -100..0, insertion loss 0..60, angles -180..180, watts 0..1 mW, volts and
     * ratio 0..1; group delay keeps the current range.
     */
    public static AxisRange startingLimits(GraphData g, AxisRange current) {
        return switch (g) {
            case DBM, DB -> new AxisRange(-100, 0);
            case INSERTION_LOSS -> new AxisRange(0, 60);
            case ANGLE, RAW_ANGLE -> new AxisRange(-180, 180);
            case WATTS -> new AxisRange(0, 0.001);
            case VOLTS, RATIO -> new AxisRange(0, 1);
            case GROUP_DELAY, NONE -> current;
        };
    }

    /** uRoundDownToMultiple: 3.2 -> 0, -3.2 -> -5 for mult 5. */
    static double roundDownToMultiple(double x, double mult) { return Math.floor(x / mult) * mult; }

    /** uRoundUpToMultiple: 3.2 -> 5, -3.2 -> 0 for mult 5. */
    static double roundUpToMultiple(double x, double mult) { return Math.ceil(x / mult) * mult; }

    /** uRoundDownToPower: the largest power of base not above x (mirrored for negative x). */
    static double roundDownToPower(double x, double base) {
        if (x == 0 || base <= 0) return base <= 0 ? x : 0;
        if (x < 0) return -roundUpToPower(-x, base);
        double p = Math.pow(base, Math.floor(Math.log(x) / Math.log(base)));
        if (p > x) p /= base;
        else if (p * base <= x) p *= base;
        return p;
    }

    /** uRoundUpToPower: the smallest power of base not below x (mirrored for negative x). */
    static double roundUpToPower(double x, double base) {
        if (x == 0 || base <= 0) return base <= 0 ? x : 0;
        if (x < 0) return -roundDownToPower(-x, base);
        double p = Math.pow(base, Math.ceil(Math.log(x) / Math.log(base)));
        if (p < x) p *= base;
        else if (p / base >= x) p /= base;
        return p;
    }
}
