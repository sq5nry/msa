package msa.trace;

import java.util.Arrays;

import msa.sweep.PhaseDetector;
import msa.sweep.Sweeper;

/**
 * One sweep's data: frequencies, band, raw ADC readings, calibrated power and phase per point (the BASIC
 * datatable(), magarray() and phaarray()). Points not measured yet hold NaN. Mutable while the sweep is
 * running, then frozen with {@link #copy()}.
 *
 * <p>The transmission modes' operating calibration (the reference measured with a through connection) and
 * the reference-plane extension are not baked into the stored values: they are a {@link Correction} applied
 * when a value is asked for, so changing "Reference To" or the plane extension updates the data already on
 * the screen (the BASIC program needs a restart, or the Recalc button, for that).
 *
 * <p>Not thread-safe: fill and read it on one thread (the GUI thread, via the engine listener executor).
 */
public final class SweepData {

    /**
     * Transmission calibration and plane extension for this sweep's points ([ConvertMagPhaseData] subtracting
     * lineCalArray, uExtendCalPlane).
     *
     * @param db         reference magnitude per point, subtracted from the power; null = no reference
     * @param deg        reference phase per point, subtracted from the phase; null = no reference
     * @param planeExtNs reference plane extension in ns (Sweep window "Plane Extension"); phase is advanced
     *                   by 360 * f * t
     */
    public record Correction(double[] db, double[] deg, double planeExtNs) {
        public static final Correction NONE = new Correction(null, null, 0);
    }

    private final int number;
    private final double[] freq;
    private final double[] dBm;
    private final double[] phase;
    private final long[] adc;
    private final long[] adcPhase;
    private final int[] band;
    private final byte[] pdm;
    private int measured;
    private boolean complete;
    private Correction correction = Correction.NONE;

    public SweepData(int number, double[] frequencies) {
        this.number = number;
        this.freq = frequencies.clone();
        this.dBm = new double[freq.length];
        this.phase = new double[freq.length];
        this.adc = new long[freq.length];
        this.adcPhase = new long[freq.length];
        this.band = new int[freq.length];
        this.pdm = new byte[freq.length];
        Arrays.fill(dBm, Double.NaN);
        Arrays.fill(phase, Double.NaN);
        Arrays.fill(adc, -1);
    }

    private SweepData(SweepData o) {
        number = o.number;
        freq = o.freq.clone();
        dBm = o.dBm.clone();
        phase = o.phase.clone();
        adc = o.adc.clone();
        adcPhase = o.adcPhase.clone();
        band = o.band.clone();
        pdm = o.pdm.clone();
        measured = o.measured;
        complete = o.complete;
        correction = o.correction;
    }

    public SweepData copy() { return new SweepData(this); }

    /**
     * What an "erase" trace shows while a sweep runs: this sweep's measured points, and the previous sweep's
     * values where this one has not got to yet. {@code older} is ignored if it is null or has other frequencies.
     */
    public SweepData mergedOver(SweepData older) {
        SweepData m = new SweepData(this);
        if (older == null || older.size() != size() || older == this) return m;
        for (int i = 0; i < size(); i++) {
            if (isMeasured(i) || !older.isMeasured(i) || Math.abs(older.freq[i] - freq[i]) > 1e-9) continue;
            m.dBm[i] = older.dBm[i];
            m.phase[i] = older.phase[i];
            m.adc[i] = older.adc[i];
            m.adcPhase[i] = older.adcPhase[i];
            m.band[i] = older.band[i];
            m.pdm[i] = older.pdm[i];
            m.measured++;
        }
        return m;
    }

    /** [ConvertMagPhaseData] / [ConvertPhadata] result for one step. */
    public void set(int index, Sweeper.Point p) {
        if (adc[index] < 0) measured++;
        dBm[index] = p.dBm();
        phase[index] = p.phaseDeg();
        adc[index] = p.adc().adc1();
        adcPhase[index] = p.adc().adc2();
        pdm[index] = (byte) p.pdm();
        band[index] = p.plan().band();
        freq[index] = p.plan().trueFreq();
    }

    /** Test helper and data-file loading: set a value directly. */
    public void set(int index, double powerDbm) {
        if (adc[index] < 0) measured++;
        dBm[index] = powerDbm;
        adc[index] = 0;
    }

    /** Test helper and data-file loading: set power and phase directly. */
    public void set(int index, double powerDbm, double phaseDeg) {
        set(index, powerDbm);
        phase[index] = phaseDeg;
    }

    void markComplete() { complete = true; }

    /** Uses this calibration reference and plane extension from now on (the arrays must match the points). */
    public void setCorrection(Correction c) {
        if (c == null) c = Correction.NONE;
        if ((c.db() != null && c.db().length != size()) || (c.deg() != null && c.deg().length != size()))
            throw new IllegalArgumentException("correction has " + (c.db() != null ? c.db().length : c.deg().length)
                    + " points, the sweep " + size());
        correction = c;
    }

    public Correction correction() { return correction; }

    public int number() { return number; }
    public int size() { return freq.length; }
    public boolean complete() { return complete; }
    public int measuredCount() { return measured; }
    public boolean isMeasured(int i) { return adc[i] >= 0; }
    public double frequency(int i) { return freq[i]; }
    public double dBm(int i) { return dBm[i]; }
    /** Phase before the calibration reference and plane extension (NaN when the mode reads no phase). */
    public double phase(int i) { return phase[i]; }
    public long adc(int i) { return adc[i]; }
    /** Raw phase ADC reading. */
    public long phaseAdc(int i) { return adcPhase[i]; }
    /** PDM state of the reading (1 = inverted). */
    public int pdm(int i) { return pdm[i]; }
    public int band(int i) { return band[i]; }

    /** Transmission in dB: power minus the reference magnitude (CalcTransmitGraphData constMagDB). */
    public double s21Db(int i) {
        double[] ref = correction.db();
        return ref == null ? dBm[i] : dBm[i] - ref[i];
    }

    /** S21 phase: phase minus the reference phase, plus the plane extension, -180..180. */
    public double s21Deg(int i) {
        double[] ref = correction.deg();
        double p = ref == null ? phase[i] : phase[i] - ref[i];
        return PhaseDetector.normalise(p + extension(i));
    }

    /** constRawAngle: the phase without the reference (but with the plane extension, as BASIC shows it). */
    public double rawDeg(int i) { return PhaseDetector.normalise(phase[i] + extension(i)); }

    /** uExtendCalPlane for transmission: 360 degrees per (ns x GHz). */
    private double extension(int i) {
        double t = correction.planeExtNs();
        return t == 0 ? 0 : 360 * 0.001 * t * freq[i];
    }

    /**
     * Group delay in seconds from the S21 phase of this step and its neighbour (the previous one, or the
     * next one for the first step): -(delta phase in cycles, wrapped to +-0.5) / (delta frequency in Hz).
     */
    public double groupDelay(int i) {
        if (size() < 2) return Double.NaN;
        int j = i > 0 ? i - 1 : 1;
        double cycles = (s21Deg(i) - s21Deg(j)) / 360;
        if (Double.isNaN(cycles)) return Double.NaN;
        if (cycles > 0.5) cycles -= 1;
        else if (cycles < -0.5) cycles += 1;
        double df = 1e6 * (freq[i] - freq[j]);
        return df == 0 ? Double.NaN : -cycles / df;
    }

    /** Graph value at a whole step. */
    public double value(GraphData g, int i) {
        return switch (g) {
            case DBM, WATTS, VOLTS, NONE -> g.fromDbm(dBm[i]);
            case DB -> s21Db(i);
            case RATIO -> Math.pow(10, s21Db(i) / 20);
            case INSERTION_LOSS -> -s21Db(i);
            case ANGLE -> s21Deg(i);
            case RAW_ANGLE -> rawDeg(i);
            case GROUP_DELAY -> groupDelay(i);
        };
    }

    /**
     * Graph value at a fractional step (markers), interpolated linearly in graph units as CalcGraphData
     * does: the two whole steps are converted first, then interpolated; angles the short way round
     * (intLinearInterpolateDegrees).
     */
    public double value(GraphData g, double index) {
        int i = (int) Math.floor(index);
        double f = index - i;
        if (i >= size() - 1 || f < 1e-7) return value(g, Math.min(i, size() - 1));
        double a = value(g, i), b = value(g, i + 1);
        if (g.isAngle()) return interpolateDegrees(f, a, b);
        return a + f * (b - a);
    }

    /** intLinearInterpolateDegrees for the range -180..180: across the wrap if that is the shorter way. */
    static double interpolateDegrees(double fract, double v1, double v2) {
        double dif = v2 - v1;
        if (Math.abs(dif) > 180) dif += dif > 0 ? -360 : 360;
        return PhaseDetector.normalise(v1 + fract * dif);
    }

    /** Frequency at a fractional step. */
    public double frequency(double index) {
        int i = (int) Math.floor(index);
        double f = index - i;
        if (i >= size() - 1 || f < 1e-7) return freq[Math.min(i, size() - 1)];
        return freq[i] + f * (freq[i + 1] - freq[i]);
    }

    /** Fractional step for a frequency (inverse of {@link #frequency(double)}), clamped to the sweep. */
    public double indexOf(double frequencyMHz) {
        int n = size();
        if (n == 1) return 0;
        boolean up = freq[n - 1] >= freq[0];
        if (up ? frequencyMHz <= freq[0] : frequencyMHz >= freq[0]) return 0;
        if (up ? frequencyMHz >= freq[n - 1] : frequencyMHz <= freq[n - 1]) return n - 1;
        for (int i = 0; i < n - 1; i++) {
            double a = freq[i], b = freq[i + 1];
            if ((frequencyMHz - a) * (frequencyMHz - b) <= 0) return b == a ? i : i + (frequencyMHz - a) / (b - a);
        }
        return n - 1;
    }

    /** All graph values (NaN where not measured). */
    public double[] values(GraphData g) {
        double[] y = new double[size()];
        for (int i = 0; i < y.length; i++) y[i] = value(g, i);
        return y;
    }

    public double[] frequencies() { return freq.clone(); }
}
