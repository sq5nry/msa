package msa.sweep;

import java.util.Objects;

import msa.hw.SlimCommands.VideoFilter;
import msa.synth.SynthPlanner;

/**
 * Everything that defines a spectrum-analyser sweep: what to sweep (range, steps, spacing, direction),
 * how to measure each point (wait, samples) and the hardware setup. Immutable; change it with the
 * with... methods and hand the new value to {@link SweepEngine#apply}.
 *
 * @param range     start/stop in MHz
 * @param steps     number of intervals; the sweep has steps + 1 points (BASIC "Steps/Sweep", Prefs "NumSteps")
 * @param scale     linear or logarithmic frequency spacing (Prefs "IsLinear", first value)
 * @param direction left-to-right, right-to-left, or alternating (Prefs "SweepDir" holds 1 / -1)
 * @param waitMs    BASIC "Wait (ms)" before each ADC read (Prefs "Wait")
 * @param samples   FX2 averaging 1..255 ("Samples"; not stored in Prefs.txt by the BASIC program)
 * @param hardware  path, video filter, band, generator
 */
public record SweepSettings(FrequencyRange range, int steps, Scale scale, Direction direction,
                            int waitMs, int samples, HardwareSetup hardware) {

    public enum Scale { LINEAR, LOG }

    /**
     * Technical limit only (memory: every step keeps its synthesiser plan and data). There is no
     * usability limit; {@link AutoSteps#RECOMMENDED_MAX} is where a sweep starts getting slow.
     */
    public static final int MAX_STEPS = 1_000_000;

    /** L-R, R-L, or alternate each sweep (BASIC alternateSweep). */
    public enum Direction { LEFT_TO_RIGHT, RIGHT_TO_LEFT, ALTERNATE }

    public SweepSettings {
        Objects.requireNonNull(range);
        Objects.requireNonNull(scale);
        Objects.requireNonNull(direction);
        Objects.requireNonNull(hardware);
        if (steps < 1 || steps > MAX_STEPS) throw new IllegalArgumentException("steps must be 1.." + MAX_STEPS);
        if (waitMs < 0) throw new IllegalArgumentException("wait must be >= 0");
        if (samples < 1 || samples > 255) throw new IllegalArgumentException("samples must be 1..255");
        if (scale == Scale.LOG && range.startMHz() <= 0)
            throw new IllegalArgumentException("log sweep needs start > 0");
    }

    /** A neutral starting point: 0-100 MHz, 100 steps, linear L-R, 5 ms, 1 sample, path 1, Wide, band 1, TG tracking. */
    public static SweepSettings defaults() {
        return new SweepSettings(new FrequencyRange(0, 100), 100, Scale.LINEAR, Direction.LEFT_TO_RIGHT, 5, 1,
                HardwareSetup.defaults());
    }

    // ---- convenience accessors (flat view used by the engine and tools)
    public double startMHz() { return range.startMHz(); }
    public double stopMHz() { return range.stopMHz(); }
    public int band() { return hardware.band(); }
    public int path() { return hardware.path(); }
    public VideoFilter video() { return hardware.video(); }
    public SynthPlanner.Generator generator() { return hardware.generator(); }
    public MsaMode mode() { return hardware.mode(); }

    public int points() { return steps + 1; }

    /** Frequency of step i (0..steps). */
    public double frequency(int i) {
        if (i < 0 || i > steps) throw new IndexOutOfBoundsException(i);
        double t = (double) i / steps;
        double a = range.startMHz(), b = range.stopMHz();
        return scale == Scale.LINEAR ? a + (b - a) * t : a * Math.pow(b / a, t);
    }

    public double[] frequencies() {
        double[] f = new double[points()];
        for (int i = 0; i < f.length; i++) f[i] = frequency(i);
        return f;
    }

    /** True if moving from {@code o} to this needs the hardware re-initialised (path change). */
    boolean needsHardwareInit(SweepSettings o) {
        return o == null || path() != o.path() || mode() != o.mode();   // mode: PDM and transmit/reflect switch
    }

    /** True if moving from {@code o} to this invalidates the current trace (restart). */
    boolean needsRestart(SweepSettings o) {
        return o == null || !range.equals(o.range) || steps != o.steps || band() != o.band()
                || path() != o.path() || !generator().equals(o.generator()) || scale != o.scale || mode() != o.mode();
    }

    // ---- withers
    /** A new range; a log sweep becomes linear if the range reaches down to 0 Hz (as BASIC does). */
    public SweepSettings withRange(FrequencyRange r) {
        Scale sc = scale == Scale.LOG && r.startMHz() <= 0 ? Scale.LINEAR : scale;
        return new SweepSettings(r, steps, sc, direction, waitMs, samples, hardware);
    }
    public SweepSettings withRange(double start, double stop) { return withRange(new FrequencyRange(start, stop)); }
    public SweepSettings withCenterSpan(double center, double span) { return withRange(FrequencyRange.ofCenterSpan(center, span)); }
    public SweepSettings withSteps(int s) { return new SweepSettings(range, s, scale, direction, waitMs, samples, hardware); }
    public SweepSettings withScale(Scale s) { return new SweepSettings(range, steps, s, direction, waitMs, samples, hardware); }
    public SweepSettings withDirection(Direction d) { return new SweepSettings(range, steps, scale, d, waitMs, samples, hardware); }
    public SweepSettings withWait(int ms) { return new SweepSettings(range, steps, scale, direction, ms, samples, hardware); }
    public SweepSettings withSamples(int n) { return new SweepSettings(range, steps, scale, direction, waitMs, n, hardware); }
    public SweepSettings withHardware(HardwareSetup h) { return new SweepSettings(range, steps, scale, direction, waitMs, samples, h); }
    public SweepSettings withBand(int b) { return withHardware(hardware.withBand(b)); }
    public SweepSettings withPath(int p) { return withHardware(hardware.withPath(p)); }
    public SweepSettings withVideo(VideoFilter v) { return withHardware(hardware.withVideo(v)); }
    public SweepSettings withGenerator(SynthPlanner.Generator g) { return withHardware(hardware.withGenerator(g)); }
    public SweepSettings withMode(MsaMode m) { return withHardware(hardware.withMode(m)); }
}
