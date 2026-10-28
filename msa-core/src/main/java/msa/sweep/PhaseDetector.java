package msa.sweep;

import msa.config.MsaConfig;
import msa.hw.SlimCommands.VideoFilter;

/**
 * The VNA phase detector module (PDM) as spectrumanalyzer.bas reads it: phase ADC to degrees
 * ([ConvertPhadata]), the "dead zone" that makes the program invert the PDM ([ReadPhase]), the settling
 * time after an inversion, and the inversion calibration ([CalPDMinvdeg]).
 *
 * <p>The PDM output covers 0..360 degrees of phase lead over 0..maxpdmout. Near 0 and 360 degrees the
 * detector is unreliable, so a reading in the lowest or highest 20 % makes the program invert the PDM's
 * reference by 180 degrees; the inversion is not exactly 180 degrees, and config.txt "invdeg" holds the
 * measured value, normalised to a 10.7 MHz final IF.
 *
 * @param maxPdmOut ADC reading for 360 degrees (config.txt "maxpdmout", 65535 for the 16-bit ADC)
 * @param invDeg    phase change caused by an inversion at 10.7 MHz (config.txt "invdeg")
 */
public record PhaseDetector(int maxPdmOut, double invDeg) {

    /** Lower dead-zone limit as a fraction of maxpdmout (pdmlowlim = .2 * maxpdmout). */
    public static final double LOW_LIMIT = 0.2;
    /** Upper dead-zone limit (pdmhighlim = .8 * maxpdmout). */
    public static final double HIGH_LIMIT = 0.8;
    /** Final IF at which invdeg is stored. */
    public static final double INVDEG_REFERENCE_MHZ = 10.7;
    /** Settling time for each reading of the inversion calibration (glitchpdm = 5000). */
    public static final int CAL_SETTLE_MS = 5000;

    public PhaseDetector {
        if (maxPdmOut < 1) throw new IllegalArgumentException("maxpdmout must be positive");
    }

    /** From config.txt; defaults 65535 and 180 degrees when the keys are missing. */
    public static PhaseDetector of(MsaConfig cfg) {
        return new PhaseDetector(cfg.integer("maxpdmout", 65535), cfg.number("invdeg", 180));
    }

    public PhaseDetector withInvDeg(double deg) { return new PhaseDetector(maxPdmOut, deg); }

    /** True if the reading is in the dead zone, so the PDM must be inverted and the step read again. */
    public boolean inDeadZone(double adc) {
        return adc < LOW_LIMIT * maxPdmOut || adc > HIGH_LIMIT * maxPdmOut;
    }

    /** Raw detector phase in degrees, 0..360 (phaarray(,5), shown in the Variables window). */
    public double rawDegrees(double adc) { return 360 * adc / maxPdmOut; }

    /**
     * [ConvertPhadata] without the operating-calibration reference: raw phase, minus the inversion when the
     * PDM was inverted (scaled to the final IF), minus the path-calibration phase correction for this signal
     * level, negated in the 3G band (LO1 mixes on the low side there); rounded to 0.01 degree and put into
     * -180 &lt; phase &lt;= 180.
     *
     * @param pdm          PDM state when the reading was taken (0 normal, 1 inverted)
     * @param finalFreqMHz centre frequency of the final filter path (config.txt "Filter=" first value)
     * @param difPhase     path calibration phase correction at this magnitude reading (MSA_CalPathN third column)
     * @param band         1..3
     */
    public double degrees(double adc, int pdm, double finalFreqMHz, double difPhase, int band) {
        double phase = rawDegrees(adc);
        if (pdm == 1) phase -= 180 + inversionDeviation(finalFreqMHz);
        phase -= difPhase;
        if (band == 3) phase = -phase;
        return normalise(round2(phase));
    }

    /** Deviation of the inversion from 180 degrees at this final IF: (invdeg - 180) * finalfreq / 10.7. */
    public double inversionDeviation(double finalFreqMHz) {
        return (invDeg - 180) * finalFreqMHz / INVDEG_REFERENCE_MHZ;
    }

    /**
     * [CalPDMinvdeg]: invdeg from one reading with the PDM normal and one inverted, at a phase near
     * +-90 degrees; normalised to 10.7 MHz and rounded to 0.01 degree.
     */
    public double inversionFrom(double adcNormal, double adcInverted, double finalFreqMHz) {
        double d = rawDegrees(adcInverted) - rawDegrees(adcNormal);
        if (d < 0) d += 360;
        d = 180 + (d - 180) * INVDEG_REFERENCE_MHZ / finalFreqMHz;
        return round2(d);
    }

    /**
     * Settling after the PDM changed state: glitchpdm = int(videoPhaseTC * 10) ms, ten time constants of
     * the phase video filter (videoPhaseTC = 10 kohm x the filter's phase capacitor).
     */
    public static int glitchMs(VideoFilter v, MsaConfig cfg) {
        var vf = cfg.videoFilters();
        if (v.ordinal() >= vf.size()) return 0;
        return (int) (vf.get(v.ordinal()).phaseTimeConstantMs() * 10);
    }

    /** Into -180 &lt; deg &lt;= 180 (gNormalizePhase). */
    public static double normalise(double deg) {
        if (Double.isNaN(deg) || Double.isInfinite(deg)) return deg;
        deg = deg % 360;
        if (deg > 180) deg -= 360;
        else if (deg <= -180) deg += 360;
        return deg;
    }

    /** Rounded to 0.01, half away from zero (using("####.##")). */
    static double round2(double v) {
        return Math.signum(v) * Math.floor(Math.abs(v) * 100 + 0.5) / 100;
    }
}
