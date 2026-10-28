package msa.sweep;

/**
 * Automatic "Steps/Sweep" (not in the BASIC program, which only warns "Frequency step size exceeds RBW;
 * signals may be missed").
 *
 * <p>Spectrum analyser with the signal generator: the step is made at most half the resolution bandwidth,
 * so a carrier anywhere between two steps is still caught within about 1 dB of its peak (for a roughly
 * Gaussian filter, half a step off-centre = RBW/4 costs 3 dB * (1/2)^2 = 0.75 dB). In a log sweep the
 * steps grow with frequency, so the largest one, at the stop frequency, sets the count.
 *
 * <p>With the tracking generator the signal is always at the tuned frequency, so the RBW does not limit the
 * step and the count is {@link #TRACKING_STEPS}.
 *
 * <p>The result is rounded up to a multiple of 10 and is at least {@link #MIN_STEPS}. It is not capped: a
 * wide span with a narrow filter needs many steps (0-1000 MHz at 31 kHz: 64,520). Counts above
 * {@link #RECOMMENDED_MAX} are shown in red in the GUI as a hint that the sweep will be slow. The only
 * limit is the technical one, {@link SweepSettings#MAX_STEPS}.
 */
public final class AutoSteps {

    private AutoSteps() {}

    /** Fewer steps than this make a poor picture even of a narrow span. */
    public static final int MIN_STEPS = 100;
    /** Above this many steps a sweep gets slow; the GUI marks such counts red but does not limit them. */
    public static final int RECOMMENDED_MAX = 100000;
    /** Tracking generator mode: the BASIC program's usual 400 steps. */
    public static final int TRACKING_STEPS = 400;
    /** Steps per resolution bandwidth. */
    public static final double POINTS_PER_RBW = 2;

    /**
     * @param rbwKHz   final filter bandwidth of the selected path (config.txt)
     * @param tracking true with the tracking generator
     */
    public static int of(FrequencyRange range, SweepSettings.Scale scale, double rbwKHz, boolean tracking) {
        if (tracking) return TRACKING_STEPS;
        double stepMHz = rbwKHz / 1000 / POINTS_PER_RBW;
        double n;
        if (range.spanMHz() <= 0 || stepMHz <= 0) {
            n = MIN_STEPS;
        } else if (scale == SweepSettings.Scale.LOG && range.startMHz() > 0) {
            // the last step, f(n-1) -> stop, is the largest: stop * (1 - 1/ratio) <= stepMHz
            double top = Math.min(stepMHz / range.stopMHz(), 0.5);
            n = Math.log(range.stopMHz() / range.startMHz()) / -Math.log1p(-top);
        } else {
            n = range.spanMHz() / stepMHz;
        }
        long r = (long) Math.ceil(n / 10 - 1e-9) * 10;
        return (int) Math.max(MIN_STEPS, Math.min(SweepSettings.MAX_STEPS, r));
    }

    /** True if {@code steps} is above {@link #RECOMMENDED_MAX} (marked red in the GUI). */
    public static boolean aboveRecommended(int steps) { return steps > RECOMMENDED_MAX; }
}
