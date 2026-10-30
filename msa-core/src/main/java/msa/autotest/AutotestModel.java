package msa.autotest;

import java.util.List;
import java.util.Locale;

/**
 * The vocabulary of the self test: outcome, limits, measurements, traces and results. Plain records so
 * results can be shown, compared with a stored reference and written to a report without a GUI.
 */
public final class AutotestModel {

    private AutotestModel() {}

    /** Outcome, from best to worst; a test's status is the worst of its measurements. */
    public enum Status {
        PASS, INFO, WARN, FAIL, SKIP, ERROR;

        public Status worst(Status o) {
            if (this == ERROR || o == ERROR) return ERROR;
            if (this == SKIP) return o;
            if (o == SKIP) return this;
            return ordinal() >= o.ordinal() ? this : o;
        }
    }

    /** What has to be connected for a test. */
    public enum Setup {
        NONE("Nothing connected to the MSA input (open, or a 50 ohm load); with an input switch: SA position"),
        TG_LOOP("Tracking generator output connected to the MSA input with a short cable; with an input switch: TG to"
                + " the transmission (T) input, switch in the VNA position"),
        TG_REFLECT("Tracking generator output connected to the reflection (R) input of the input switch, switch in the"
                + " VNA position");

        public final String instruction;

        Setup(String instruction) { this.instruction = instruction; }
    }

    /**
     * Acceptance window: inside [passLow, passHigh] is PASS, inside [warnLow, warnHigh] WARN, outside FAIL.
     * NaN means unbounded on that side. {@link #info()} accepts anything (informative values).
     */
    public record Limits(double passLow, double passHigh, double warnLow, double warnHigh) {
        public static Limits info() { return new Limits(Double.NaN, Double.NaN, Double.NaN, Double.NaN); }
        public static Limits atMost(double pass, double warn) { return new Limits(Double.NaN, pass, Double.NaN, warn); }
        public static Limits atLeast(double pass, double warn) { return new Limits(pass, Double.NaN, warn, Double.NaN); }
        public static Limits within(double centre, double pass, double warn) {
            return new Limits(centre - pass, centre + pass, centre - warn, centre + warn);
        }

        public boolean informative() {
            return Double.isNaN(passLow) && Double.isNaN(passHigh) && Double.isNaN(warnLow) && Double.isNaN(warnHigh);
        }

        public Status judge(double v) {
            if (Double.isNaN(v)) return Status.FAIL;
            if (informative()) return Status.INFO;
            if (inside(v, passLow, passHigh)) return Status.PASS;
            if (inside(v, warnLow, warnHigh)) return Status.WARN;
            return Status.FAIL;
        }

        private static boolean inside(double v, double lo, double hi) {
            return (Double.isNaN(lo) || v >= lo - 1e-12) && (Double.isNaN(hi) || v <= hi + 1e-12);
        }

        /** "-45 .. -5" / "<= 3" / ">= 30" for the report. */
        public String text(String unit) {
            if (informative()) return "(information)";
            String u = unit.isEmpty() ? "" : " " + unit;
            if (Double.isNaN(passLow)) return "<= " + num(passHigh) + u;
            if (Double.isNaN(passHigh)) return ">= " + num(passLow) + u;
            return num(passLow) + " .. " + num(passHigh) + u;
        }
    }

    /**
     * One measured value with its verdict.
     *
     * @param key       stable name used to store and compare the value with the reference ("zero.p1.peak")
     * @param reference the stored reference value, or NaN
     * @param note      why it is not PASS, or what it means
     */
    public record Measurement(String key, String name, double value, String unit, Limits limits, double reference,
                              Status status, String note) {

        public String valueText() { return Double.isNaN(value) ? "-" : num(value) + (unit.isEmpty() ? "" : " " + unit); }
    }

    /**
     * A curve for the result window: x in MHz (or another axis named in {@code xLabel}), y in {@code yUnit},
     * optionally the stored reference curve and lower/upper limit curves (NaN where there is no limit).
     *
     * @param key name under which "Save as Reference" keeps the curve, or null
     */
    public record Trace(String key, String title, String xLabel, String yUnit, double[] x, double[] y, double[] reference,
                        double[] low, double[] high) {}

    /** The outcome of one test. */
    public record Result(String id, String title, Status status, String summary, List<Measurement> measurements,
                         List<Trace> traces, List<String> hints, double seconds) {

        public static Result skipped(String id, String title, String why) {
            return new Result(id, title, Status.SKIP, why, List.of(), List.of(), List.of(), 0);
        }
    }

    /** Numbers in reports: up to 4 significant digits after the point, no exponent for everyday values. */
    public static String num(double v) {
        if (Double.isNaN(v)) return "-";
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return String.valueOf((long) v);
        double a = Math.abs(v);
        if (a >= 100) return String.format(Locale.ROOT, "%.1f", v);
        if (a >= 1) return String.format(Locale.ROOT, "%.2f", v);
        if (a >= 0.01) return String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return String.format(Locale.ROOT, "%.3g", v);
    }
}
