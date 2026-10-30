package msa.trace;

import msa.model.DisplaySettings.AxisRange;

/**
 * Y-axis range rules of the BASIC program for the spectrum-analyser, transmission and reflection data types:
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
            case DBM, DB, INSERTION_LOSS, S11_DB, RETURN_LOSS -> {   // multiples of 5 dB
                min = roundDownToMultiple(min - 0.5, 5);
                max = roundUpToMultiple(max + 0.5, 5);
            }
            case ANGLE, RAW_ANGLE, S11_ANGLE, THETA, Z_ANGLE, ADMIT_ANGLE -> {   // multiples of 15 degrees, or the full circle
                min = Math.max(-180, roundDownToMultiple(min - 5, 15));
                max = Math.min(180, roundUpToMultiple(max + 5, 15));
                if (max - min > 120) { min = -180; max = 180; }
            }
            case RS, RP, Z_MAG -> {                        // non-negative impedances: multiples of 25
                if (max > 1000000) max = 1000;
                min = Math.max(0, roundDownToMultiple(min - 5, 25));
                max = roundUpToMultiple(max + 5, 25);
                if (min > 0 && max / min >= 2) min = 0;
            }
            case XS, XP -> {                               // reactances: multiples of 25, either sign
                if (max > 1000000) max = 1000;
                double tMin = roundDownToMultiple(min - 5, 25), tMax = roundUpToMultiple(max + 5, 25);
                if (tMin < 0 && min > 0) tMin = 0;
                if (tMax > 0 && max < 0) tMax = 0;
                min = tMin;
                max = tMax;
                if (min * max > 0 && (max / min >= 2 || min / max >= 2)) { if (max < 0) max = 0; else min = 0; }
            }
            case WATTS, VOLTS, RATIO, RHO -> {
                if (max > 1) max = 1;
                min = roundDownToPower(min, 10);
                max = roundUpToPower(max, 10);
                if (min > 0 && max / min >= 2) min = 0;
            }
            case CS, LS, CP, LP -> {
                min = min < 0 ? min * 1.1 : min * 0.9;
                max = max > 0 ? max * 1.1 : max * 0.9;
                min = roundDownToPower(min, 10);
                max = roundUpToPower(max, 10);
                if (max > 1) max = 1;
                if (min < -1) min = -1;
                if (min * max > 0 && (max / min >= 2 || min / max >= 2)) { if (max < 0) max = 0; else min = 0; }
            }
            case SWR -> {
                min = 1;
                max = max < 2 ? 2 : Math.min(50, Math.floor(max) + 2);
            }
            case COMPONENT_Q -> {
                min = 0;
                max = max < 10 ? 10 : Math.min(10000, Math.floor(max) + 5);
            }
            case REFLECT_POWER -> { min = 0; max = 100; }
            case ADMIT_MAG -> { min = 0; max = roundUpToPower(max, 10); }
            case CONDUCTANCE, SUSCEPTANCE -> {
                min = min > 0 ? roundDownToPower(min, 10) : roundUpToPower(min, 10);
                max = max > 0 ? roundUpToPower(max, 10) : roundDownToPower(max, 10);
            }
            default -> {                                   // group delay: wide range of either sign
                if (max > 1000000) max = 1;
                min = min < 0 ? min * 1.1 : min * 0.9;
                max = max > 0 ? max * 1.1 : max * 0.9;
                min = roundDownToPower(min, 10);
                max = roundUpToPower(max, 10);
            }
        }
        boolean db = g == GraphData.DBM || g == GraphData.DB || g == GraphData.INSERTION_LOSS || g == GraphData.S11_DB
                || g == GraphData.RETURN_LOSS;
        if (!(max > min)) max = min + (db ? 5 : Math.max(Math.abs(min), 1e-12));
        return new AxisRange(min, max);
    }

    /**
     * StartingLimits: dBm and dB -100..0, insertion and return loss 0..60, angles -180..180, watts 0..1 mW,
     * volts, ratio and rho 0..1, resistances 0..200, reactances -200..200, C and L 0..1u, SWR 1..11, Q 0..100,
     * reflected power 0..100 %; group delay keeps the current range.
     */
    public static AxisRange startingLimits(GraphData g, AxisRange current) { return startingLimits(g, GraphData.NONE, current); }

    /**
     * StartingLimits with the data type shown before: changing between related types (S11 angle and theta,
     * the resistances, the reactances, the capacitances, the inductances) keeps a sensible current range.
     */
    public static AxisRange startingLimits(GraphData g, GraphData old, AxisRange current) {
        double lo = current.min(), hi = current.max();
        return switch (g) {
            case DBM, DB, S11_DB -> new AxisRange(-100, 0);
            case INSERTION_LOSS, RETURN_LOSS -> new AxisRange(0, 60);
            case ANGLE, RAW_ANGLE, S11_ANGLE, THETA, Z_ANGLE, ADMIT_ANGLE -> new AxisRange(-180, 180);
            case WATTS -> new AxisRange(0, 0.001);
            case VOLTS, RATIO, RHO -> new AxisRange(0, 1);
            case ADMIT_MAG -> new AxisRange(0, 1);
            case CONDUCTANCE, SUSCEPTANCE -> new AxisRange(-1, 1);
            case RS, RP, Z_MAG -> (old == GraphData.RS || old == GraphData.RP || old == GraphData.Z_MAG) && lo >= 0 && hi > lo
                    ? current : new AxisRange(0, 200);
            case XS, XP -> (old == GraphData.XS || old == GraphData.XP) && hi > lo ? current : new AxisRange(-200, 200);
            case CS, CP -> (old == GraphData.CS || old == GraphData.CP) && hi > lo ? current : new AxisRange(0, 1e-6);
            case LS, LP -> (old == GraphData.LS || old == GraphData.LP) && hi > lo ? current : new AxisRange(0, 1e-6);
            case SWR -> new AxisRange(1, 11);
            case COMPONENT_Q -> new AxisRange(0, 100);
            case REFLECT_POWER -> new AxisRange(0, 100);
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
