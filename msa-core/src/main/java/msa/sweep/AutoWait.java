package msa.sweep;

import java.util.List;

import msa.config.MsaConfig;
import msa.hw.SlimCommands.VideoFilter;

/**
 * Automatic "Wait (ms)": how long to let the signal settle after each step before the ADC is read.
 *
 * <p>Two things must settle:
 * <ul>
 *   <li><b>The video filter</b>, a single RC on the log detector output. Its time constant is the BASIC
 *       program's videoMagTC = 2.7 kOhm x the magnitude capacitor from config.txt "VideoFilters=" (this MSA:
 *       Wide 0.002 uF = 0.0054 ms, Mid 0.2 uF = 0.54 ms, Narrow 10 uF = 27 ms, XNarrow 100 uF = 270 ms).
 *       After {@value #TIME_CONSTANTS} time constants e^-5 = 0.7 % of a level change is left: 0.4 dB of a
 *       60 dB jump onto a carrier, less for the small changes between neighbouring steps.</li>
 *   <li><b>The resolution filter</b> (crystal or ceramic final IF filter), which needs about
 *       {@value #RBW_PERIODS} / RBW to build up: 5 ms for the 0.4 kHz filter, 65 us for 31 kHz.</li>
 * </ul>
 * In the VNA modes the phase detector's video filter must settle as well (videoPhaseTC = 10 kOhm x the
 * phase capacitor: Wide 0.11 ms, Mid 2 ms, Narrow 22 ms, XNarrow 100 ms here); the slower of the two
 * video filters counts.
 * The two are added (the video filter sees the IF filter's output) and rounded up to whole milliseconds,
 * at least {@value #MIN_MS} ms. The PLL/DDS "glitch" millisecond the BASIC program adds when a synthesiser
 * was commanded is still added separately by the sweep.
 *
 * <p>The BASIC program had an "Auto Wait" until v117c13 that instead re-read the ADC until two readings
 * agreed; it was removed there. This is a fixed calculation from the hardware values, so a sweep takes a
 * predictable time.
 */
public final class AutoWait {

    private AutoWait() {}

    /** Video filter time constants to wait (e^-5: 99.3 % settled). */
    public static final double TIME_CONSTANTS = 5;
    /** Resolution filter build-up: this many periods of its bandwidth. */
    public static final double RBW_PERIODS = 2;
    /** Never less than this. */
    public static final int MIN_MS = 1;

    /** The BASIC program's default capacitors (uF, magnitude), used if config.txt has no VideoFilters line. */
    private static final double[] DEFAULT_MAG_CAP_UF = {0.002, 0.2, 10, 100};

    /** Magnitude video filter time constant in ms (BASIC videoMagTC; the cap is at least 0.002 uF there too). */
    public static double videoTimeConstantMs(VideoFilter v, MsaConfig cfg) {
        List<MsaConfig.VideoFilterCaps> caps = cfg.videoFilters();
        double uF = v.ordinal() < caps.size() ? caps.get(v.ordinal()).magCapUF() : DEFAULT_MAG_CAP_UF[v.ordinal()];
        return 2.7 * Math.max(0.002, uF);
    }

    /** Wait in ms for a video filter and a resolution bandwidth. */
    public static int of(double videoTimeConstantMs, double rbwKHz) {
        double ms = TIME_CONSTANTS * videoTimeConstantMs + (rbwKHz > 0 ? RBW_PERIODS / rbwKHz : 0);
        return Math.max(MIN_MS, (int) Math.ceil(ms - 1e-9));
    }

    /** Phase video filter time constant in ms (BASIC videoPhaseTC); 0 if config.txt does not list the filter. */
    public static double phaseTimeConstantMs(VideoFilter v, MsaConfig cfg) {
        List<MsaConfig.VideoFilterCaps> caps = cfg.videoFilters();
        return v.ordinal() < caps.size() ? caps.get(v.ordinal()).phaseTimeConstantMs() : 0;
    }

    /** The video time constant that sets the wait: magnitude, or in the VNA modes the slower of magnitude and phase. */
    public static double settlingTimeConstantMs(SweepSettings s, MsaConfig cfg) {
        double tc = videoTimeConstantMs(s.video(), cfg);
        return s.mode().readsPhase() ? Math.max(tc, phaseTimeConstantMs(s.video(), cfg)) : tc;
    }

    /** Wait in ms for these settings' video filter(s) and filter path. */
    public static int of(SweepSettings s, MsaConfig cfg) {
        return of(settlingTimeConstantMs(s, cfg), cfg.filter(s.path()).bwKHz());
    }
}
