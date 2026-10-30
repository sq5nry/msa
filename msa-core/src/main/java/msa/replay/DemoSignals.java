package msa.replay;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import msa.config.MsaConfig;
import msa.config.PathCalibration;
import msa.hw.sim.SimulatedTransport;
import msa.sweep.MsaMode;
import msa.sweep.PhaseDetector;
import msa.synth.SynthPlanner;

/**
 * The demo source: a simulated MSA that behaves plausibly enough to try the program (and the self test)
 * without hardware. Levels are turned into ADC readings through the real path calibration, so they read
 * back right.
 * <ul>
 *   <li>Spectrum analyser: PLO 1 leaking into the IF around 0 Hz, shaped by the selected filter (the
 *       "zero-frequency" response, -25 dBm), a carrier drifting through 100-500 MHz with two harmonics,
 *       and a noise floor that falls with the resolution bandwidth down to the detector's limit.</li>
 *   <li>Transmission: the tracking generator (-10 dBm) through 4 ns of cable with a little loss and the
 *       selected test component, read by a phase detector that honours the PDM state.</li>
 *   <li>Reflection: the TG into a reflection bridge with finite directivity, source match and cable delay,
 *       the selected one-port (a calibration standard, or a component) at its test port.</li>
 * </ul>
 */
public final class DemoSignals {

    private DemoSignals() {}

    /** What the simulated test fixture holds: two-ports for transmission, one-ports for reflection. */
    public enum Dut {
        THROUGH("Through", false), THROUGH_R("TG to the R input (input switch)", false), BANDPASS("Band-pass (series RLC, 200 MHz)", false), LOWPASS("Low-pass (shunt 30 pF)", false),
        OPEN("Open", true), SHORT("Short", true), LOAD("Load 48.68 ohms", true),
        SERIES_RLC("Series RLC to ground (5 ohms, 400 nH, 1.583 pF)", true), R75("75 ohms with 10 nH", true);

        public final String title;
        /** True for the reflection mode's one-ports. */
        public final boolean onePort;

        Dut(String title, boolean onePort) {
            this.title = title;
            this.onePort = onePort;
        }
    }

    public static SimulatedTransport create(MsaConfig cfg, Supplier<MsaMode> mode, Supplier<Dut> dut,
                                            IntFunction<PathCalibration> cals) {
        Random rnd = new Random(1);
        Map<Integer, SynthPlanner> planners = new HashMap<>();
        AtomicLong reads = new AtomicLong();
        PhaseDetector pd = PhaseDetector.of(cfg);
        double widest = 0;
        for (MsaConfig.Filter f : cfg.filters()) widest = Math.max(widest, f.bwKHz());
        double widestKHz = widest;
        SimulatedTransport.AdcModel model = st -> {
            double f = ReplayTransport.tunedFrequency(st, cfg, planners);
            int path = ReplayTransport.pathOf(st, cfg);
            double bw = cfg.filter(path).bwKHz();
            PathCalibration cal = cals.apply(path);
            double floor = Math.max(-118, -103 + 10 * Math.log10(bw / widestKHz));
            double dbm, deg;
            boolean tOnR = dut.get() == Dut.THROUGH_R;
            boolean switchPath = mode.get().networkAnalyser() && !Double.isNaN(f)
                    && (tOnR || (mode.get() == MsaMode.REFLECTION && dut.get() == Dut.THROUGH));
            if (switchPath) {
                // an SA/VNA input switch: the TG on T or R, the T/R latch bit selecting T (transmission) or R
                boolean selected = tOnR == (mode.get() == MsaMode.REFLECTION);
                double sig = tOnR ? -10 - 8 - 0.003 * f : -10 - 0.5 - 0.002 * f;
                if (!selected) sig -= 105;                                       // switch isolation
                dbm = 10 * Math.log10(Math.pow(10, sig / 10) + Math.pow(10, floor / 10));
                double finalFreq = cfg.filter(path).freqMHz();
                double sigDeg = -360 * f * 1e6 * (tOnR ? 5e-9 : 4e-9) + 30;
                deg = (selected ? sigDeg + rnd.nextGaussian() * 0.05 : rnd.nextDouble() * 360)
                        + (st.pdmInverted ? 180 + pd.inversionDeviation(finalFreq) : 0);
                dbm += rnd.nextGaussian() * (selected ? 0.03 : 1.5);
            } else if (mode.get() == MsaMode.REFLECTION && !Double.isNaN(f)) {
                Dut d = dut.get().onePort ? dut.get() : Dut.SERIES_RLC;
                msa.vna.Cx m = bridge(f, msa.vna.Impedance.toRefco(50, impedance(f, d)));
                dbm = -10 + m.db() + rnd.nextGaussian() * 0.03;
                double finalFreq = cfg.filter(path).freqMHz();
                deg = m.deg() + 30 + rnd.nextGaussian() * 0.05 + (st.pdmInverted ? 180 + pd.inversionDeviation(finalFreq) : 0);
            } else if (mode.get().networkAnalyser() && !Double.isNaN(f)) {
                double[] h = transmission(f, dut.get().onePort ? Dut.THROUGH : dut.get());
                dbm = -10 + h[0] + rnd.nextGaussian() * 0.03;
                double finalFreq = cfg.filter(path).freqMHz();
                deg = h[1] + 30 + rnd.nextGaussian() * 0.05 + (st.pdmInverted ? 180 + pd.inversionDeviation(finalFreq) : 0);
            } else {
                double carrier = 300 + 200 * Math.sin(reads.incrementAndGet() / 20000.0);
                dbm = floor + rnd.nextGaussian() * 1.5;
                if (!Double.isNaN(f)) {
                    double off = f * 1000 / bw;                                   // 0 Hz response in bandwidths
                    dbm = Math.max(dbm, -25 - 12 * off * off + rnd.nextGaussian() * 0.05);
                    for (int k = 1; k <= 3; k++) {
                        double d = (f - k * carrier) / 1.5;
                        dbm = Math.max(dbm, -15 - 20 * (k - 1) - 6 * d * d);
                    }
                }
                deg = rnd.nextDouble() * 360;
            }
            long adc = cal != null ? adcFor(cal, dbm - cal.freqCorrection(Double.isNaN(f) ? 0 : f))
                    : Math.round(10000 + 328 * (Math.max(dbm, -110) + 100));
            long ph = Math.round(Math.floorMod(Math.round(deg * 1000), 360000) / 360000.0 * pd.maxPdmOut());
            return new long[] {Math.max(0, Math.min(65535, adc)), ph};
        };
        SimulatedTransport t = new SimulatedTransport(model, 0x1024, 33, 0);
        t.setDescription("demo signal");
        return t;
    }

    /** Inverse of the magnitude table: the ADC reading that the calibration turns into this level. */
    public static long adcFor(PathCalibration cal, double dbm) {
        PathCalibration.Table t = cal.magTable();
        double[] x = t.x(), y = t.y();
        if (dbm <= y[0]) return Math.round(x[0]);
        for (int i = 1; i < x.length; i++)
            if (dbm <= y[i]) return Math.round(x[i - 1] + (dbm - y[i - 1]) * (x[i] - x[i - 1]) / (y[i] - y[i - 1]));
        return Math.round(x[x.length - 1]);
    }

    /**
     * The demo reflection bridge at f MHz: what reaches the MSA input, relative to the TG, with a load of
     * reflection s on the test port: e00 + e01e10 s / (1 - e11 s), with -35 dB directivity, -16 dB through the
     * bridge and 3 ns of cable, and a 0.06 source match.
     */
    public static msa.vna.Cx bridge(double fMHz, msa.vna.Cx s) {
        msa.vna.Cx e00 = msa.vna.Cx.polar(0.018, 70 - 0.05 * fMHz);
        msa.vna.Cx e01e10 = msa.vna.Cx.polar(0.158 * (1 - 0.0001 * fMHz), -360 * fMHz * 1e6 * 3e-9);
        msa.vna.Cx e11 = msa.vna.Cx.polar(0.06, 100 - 0.2 * fMHz);
        return e00.plus(e01e10.times(s).div(msa.vna.Cx.ONE.minus(e11.times(s))));
    }

    /** Impedance of a one-port at f MHz. */
    public static msa.vna.Cx impedance(double fMHz, Dut d) {
        double w = 2 * Math.PI * fMHz * 1e6;
        return switch (d) {
            case OPEN -> new msa.vna.Cx(msa.vna.Impedance.MAX, 0);
            case SHORT -> msa.vna.Cx.ZERO;
            case LOAD -> new msa.vna.Cx(48.68, 0);
            case SERIES_RLC -> new msa.vna.Cx(5, w * 400e-9 - 1 / (w * 1.583e-12));
            case R75 -> new msa.vna.Cx(75, w * 10e-9);
            default -> new msa.vna.Cx(50, 0);
        };
    }

    /** Fixture response at f MHz: 4 ns of cable with 0.5 dB + 0.2 dB/100 MHz loss, then the component: {dB, deg}. */
    public static double[] transmission(double fMHz, Dut dut) {
        double w = 2 * Math.PI * fMHz * 1e6;
        double re = 1, im = 0;                                   // component S21 in a 50 ohm system
        switch (dut) {
            case THROUGH -> { }
            case BANDPASS -> {                                    // series R 5, L 400 nH, C 1.583 pF
                double x = w * 400e-9 - 1 / (w * 1.583e-12);
                double dr = 105, di = x;                          // S21 = 100 / (100 + Z)
                double den = dr * dr + di * di;
                re = 100 * dr / den;
                im = -100 * di / den;
            }
            case LOWPASS -> {                                     // shunt 30 pF: S21 = 1 / (1 + j w C 25)
                double b = w * 30e-12 * 25;
                double den = 1 + b * b;
                re = 1 / den;
                im = -b / den;
            }
        }
        double db = 10 * Math.log10(re * re + im * im) - 0.5 - 0.002 * fMHz;
        double deg = Math.toDegrees(Math.atan2(im, re)) - 360 * fMHz * 1e6 * 4e-9;
        return new double[] {db, deg};
    }
}
