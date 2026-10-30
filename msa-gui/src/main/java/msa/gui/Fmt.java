package msa.gui;

import java.util.Locale;

import msa.trace.GraphData;

/** Number formats of the BASIC program (uFormatted$ / using()), as far as the SA screens use them. */
public final class Fmt {

    private Fmt() {}

    /** using("####.######", f): frequencies in MHz everywhere in the UI. */
    public static String mhz(double f) { return String.format(Locale.ROOT, "%.6f", f); }

    /**
     * A graph value in its unit (DetermineGraphDataFormat): dBm and dB with 3 decimals, angles with 2,
     * watts, volts, ratio and group delay with a metric prefix.
     */
    public static String value(GraphData g, double v) {
        if (Double.isNaN(v)) return "-";
        return switch (g) {
            case DBM, DB, INSERTION_LOSS, S11_DB, RETURN_LOSS, RHO -> String.format(Locale.ROOT, "%.3f", v);
            case ANGLE, RAW_ANGLE, S11_ANGLE, THETA, Z_ANGLE, ADMIT_ANGLE, SWR, REFLECT_POWER -> String.format(Locale.ROOT, "%.2f", v);
            case COMPONENT_Q -> String.format(Locale.ROOT, "%.1f", v);
            case WATTS, VOLTS -> withPrefix(v, 4) + g.unit;
            case RATIO -> withPrefix(v, 4).strip();
            case GROUP_DELAY -> withPrefix(v, 4) + "s";
            case Z_MAG, RS, XS, RP, XP -> withPrefix(v, 4) + "\u03A9";
            case CS, CP -> withPrefix(v, 4) + "F";
            case LS, LP -> withPrefix(v, 4) + "H";
            case ADMIT_MAG, CONDUCTANCE, SUSCEPTANCE -> withPrefix(v, 4) + "S";
            case NONE -> "";
        };
    }

    /** Axis label: as short as the division step allows. */
    public static String axis(GraphData g, double v, double step) {
        switch (g) {
            case DBM, DB, INSERTION_LOSS, ANGLE, RAW_ANGLE, S11_DB, RETURN_LOSS, S11_ANGLE, THETA, Z_ANGLE, ADMIT_ANGLE,
                 SWR, REFLECT_POWER, COMPONENT_Q -> {
                int dec = step >= 1 ? 0 : step >= 0.1 ? 1 : 2;
                return String.format(Locale.ROOT, "%." + dec + "f", v);
            }
            case GROUP_DELAY -> { return withPrefix(v, 3) + "s"; }
            default -> { return withPrefix(v, 3); }
        }
    }

    /** Frequency label for the x axis with enough decimals for the division step (both in MHz). */
    public static String freqAxis(double f, double stepMHz) {
        int dec = stepMHz >= 1 ? 1 : stepMHz >= 0.1 ? 2 : stepMHz >= 0.01 ? 3 : stepMHz >= 0.001 ? 4 : 6;
        if (stepMHz >= 10) dec = 0;
        return String.format(Locale.ROOT, "%." + dec + "f", f);
    }

    /** "3,4,5//UseMultiplier//suffix=Hz": e.g. 12.5 kHz, 1.25 MHz. */
    public static String hz(double hz) { return withPrefix(hz, 4) + "Hz"; }

    /** Value with a metric prefix and about {@code digits} significant digits ("12.34m", "1.5k", "0"). */
    public static String withPrefix(double v, int digits) {
        if (v == 0 || Double.isNaN(v) || Double.isInfinite(v)) return Double.isNaN(v) ? "-" : v == 0 ? "0 " : String.valueOf(v);
        String[] pre = {"p", "n", "u", "m", "", "k", "M", "G"};
        int e = (int) Math.floor(Math.log10(Math.abs(v)) / 3);
        e = Math.max(-4, Math.min(3, e));
        double m = v / Math.pow(1000, e);
        int intDigits = Math.abs(m) >= 100 ? 3 : Math.abs(m) >= 10 ? 2 : 1;
        int dec = Math.max(0, digits - intDigits);
        String s = String.format(Locale.ROOT, "%." + dec + "f", m);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s + " " + pre[e + 4];
    }
}
