package msa.sweep;

import java.util.Locale;

/**
 * How long one sweep takes. Each point costs the wait, the 1 ms the sweep adds after commanding the
 * synthesisers (BASIC "glitch" time), and a per-point overhead for the USB command and the ADC read.
 * The overhead is not known in advance (it depends on the PC, USB and averaging, and on Windows a 1 ms
 * sleep can take longer); it starts at {@link #DEFAULT_OVERHEAD_MS} and is replaced by what the last
 * completed sweep actually took ({@link #overheadFrom}).
 */
public final class SweepTime {

    private SweepTime() {}

    /** First guess for USB command + ADC read per point, before a sweep has been timed. */
    public static final double DEFAULT_OVERHEAD_MS = 2.0;
    /** Added after a step that commanded a PLL or DDS (Sweeper.Commanded#settleMs). */
    public static final double GLITCH_MS = 1;

    /** Time per point in ms. */
    public static double perPointMs(SweepSettings s, double overheadMs) {
        return s.waitMs() + GLITCH_MS + overheadMs;
    }

    /** Time for one sweep (all points, left to right) in seconds. */
    public static double seconds(SweepSettings s, double overheadMs) {
        return s.points() * perPointMs(s, overheadMs) / 1000;
    }

    /** The per-point overhead a completed sweep shows: its time per point minus wait and glitch time. */
    public static double overheadFrom(SweepSettings s, long sweepNanos) {
        double perPoint = sweepNanos / 1e6 / s.points();
        return Math.max(0, perPoint - s.waitMs() - GLITCH_MS);
    }

    /** "0.45 s", "12.3 s", "2 min 05 s", "1 h 12 min". */
    public static String format(double seconds) {
        if (!(seconds >= 0) || Double.isInfinite(seconds)) return "-";
        if (seconds < 10) return String.format(Locale.ROOT, "%.2f s", seconds);
        if (seconds < 60) return String.format(Locale.ROOT, "%.1f s", seconds);
        long s = Math.round(seconds);
        if (s < 3600) return String.format(Locale.ROOT, "%d min %02d s", s / 60, s % 60);
        return String.format(Locale.ROOT, "%d h %02d min", s / 3600, (s % 3600) / 60);
    }
}
