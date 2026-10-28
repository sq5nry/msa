package msa.sweep;

import msa.config.MsaConfig;

/**
 * Automatic "Samples": how many ADC readings the FX2 averages per point (AdcConfig average, BASIC
 * adcsamples).
 *
 * <p>The readings are taken back to back, a few microseconds apart, so averaging only helps when the video
 * filter is fast enough that consecutive readings carry different noise. That is the Wide filter (time
 * constant 0.0054 ms on this MSA): there {@value #WIDE_SAMPLES} readings act like a narrower video
 * bandwidth at a cost of microseconds per point, much less than switching to Mid (about 3 ms of wait per
 * point). Mid and slower filters have already smoothed far longer than the readings take, so averaging adds
 * nothing and the count is 1, the BASIC program's default.
 *
 * <p>"Wide" is decided by the time constant from config.txt (below {@value #FAST_VIDEO_MS} ms), not by the
 * name, so a differently fitted filter board is handled by its capacitor values.
 */
public final class AutoSamples {

    private AutoSamples() {}

    /** Readings averaged with a fast (Wide) video filter. */
    public static final int WIDE_SAMPLES = 16;
    /** Video time constants below this count as fast enough for averaging to help. */
    public static final double FAST_VIDEO_MS = 0.05;

    public static int of(double videoTimeConstantMs) {
        return videoTimeConstantMs < FAST_VIDEO_MS ? WIDE_SAMPLES : 1;
    }

    public static int of(SweepSettings s, MsaConfig cfg) {
        return of(AutoWait.videoTimeConstantMs(s.video(), cfg));
    }
}
