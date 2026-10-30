package msa.autotest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import msa.autotest.AutotestModel.Limits;
import msa.autotest.AutotestModel.Result;
import msa.autotest.AutotestModel.Setup;
import msa.autotest.AutotestModel.Status;
import msa.autotest.AutotestModel.Trace;
import msa.config.MsaConfig;
import msa.hw.SlimCommands.VideoFilter;
import msa.sweep.AutoSamples;
import msa.sweep.AutoSteps;
import msa.sweep.AutoWait;
import msa.sweep.MsaMode;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;
import msa.trace.SweepData;

/**
 * The self tests. Expected values come from the MSA documentation ("Testing the Integrated MSA": the "Zero"
 * Frequency Sweep health test, the MSA Frequency Range Test, the Log Detector signal test) where it gives
 * them, otherwise from what a working MSA does; every unit differs, so each value is also compared with the
 * reference saved from this MSA when it was known to be good.
 */
public final class Tests {

    private Tests() {}

    /** All tests in run order: those without wiring first, then the ones with the TG looped to the input. */
    public static List<AutoTest> all() {
        return List.of(new ZeroFrequency(), new AdcAlive(), new NoiseFloor(), new SweepSpeed(), new SpurScan(),
                new TgRange(), new PathConsistency(), new VideoFilters(), new Stability(), new PhaseLinearity(),
                new PdmInversion(), new Isolation(true), new ReflectionPath(), new Isolation(false),
                new PhaseLinearity("phaseR", "Phase linearity of the R path", Setup.TG_REFLECT, MsaMode.REFLECTION));
    }

    /** Tests that run by default (the spur scan is long). */
    public static boolean defaultSelected(AutoTest t) { return !(t instanceof SpurScan); }

    // ================================================================ helpers

    abstract static class Base implements AutoTest {
        private final String id, title, description;
        private final Setup setup;
        private final int seconds;

        Base(String id, String title, Setup setup, int seconds, String description) {
            this.id = id;
            this.title = title;
            this.setup = setup;
            this.seconds = seconds;
            this.description = description;
        }

        @Override public String id() { return id; }
        @Override public String title() { return title; }
        @Override public String description() { return description; }
        @Override public Setup setup() { return setup; }
        @Override public int seconds() { return seconds; }

        @Override
        public String notApplicable(MsaConfig cfg) {
            if (setup != Setup.NONE && cfg.tgTop() <= 0) return "needs the tracking generator";
            return null;
        }

        ResultBuilder builder(AutotestContext ctx) { return new ResultBuilder(id, title, ctx.reference()); }
    }

    /** Plain spectrum analyser sweep (TG as a signal generator at 10 MHz, as the BASIC default), automatic wait. */
    static SweepSettings sa(MsaConfig cfg, int path, double start, double stop, int steps, VideoFilter v, int samples) {
        SweepSettings s = SweepSettings.defaults().withRange(start, stop).withSteps(steps).withPath(path).withVideo(v)
                .withBand(1).withMode(MsaMode.SA).withGenerator(new SynthPlanner.SignalGen(10));
        s = s.withSamples(samples > 0 ? samples : AutoSamples.of(s, cfg));
        return s.withWait(AutoWait.of(s, cfg));
    }

    /** Transmission sweep (vector with the VNA hardware, scalar without), TG tracking at no offset. */
    static SweepSettings trans(MsaConfig cfg, int path, double start, double stop, int steps, VideoFilter v, int samples) {
        MsaMode mode = cfg.hasVna() ? MsaMode.VECTOR_TRANS : MsaMode.SCALAR_TRANS;
        SweepSettings s = SweepSettings.defaults().withRange(start, stop).withSteps(steps).withPath(path).withVideo(v)
                .withBand(1).withMode(mode).withGenerator(new SynthPlanner.Tracking(0, false));
        s = s.withSamples(samples > 0 ? samples : AutoSamples.of(s, cfg));
        return s.withWait(AutoWait.of(s, cfg));
    }

    /** A VNA sweep in the given mode (transmission: T/R line 0, reflection: 1), TG tracking at no offset. */
    static SweepSettings vna(MsaConfig cfg, MsaMode mode, int path, double start, double stop, int steps, VideoFilter v) {
        SweepSettings s = SweepSettings.defaults().withRange(start, stop).withSteps(steps).withPath(path).withVideo(v)
                .withBand(1).withMode(mode).withGenerator(new SynthPlanner.Tracking(0, false));
        s = s.withSamples(AutoSamples.of(s, cfg));
        return s.withWait(AutoWait.of(s, cfg));
    }

    /** The path whose filter is closest to 4 kHz (low noise floor, still quick to settle). */
    static int quietPath(MsaConfig cfg) {
        int best = 1;
        for (int p = 1; p <= cfg.filters().size(); p++)
            if (Math.abs(Math.log(cfg.filter(p).bwKHz() / 4)) < Math.abs(Math.log(cfg.filter(best).bwKHz() / 4))) best = p;
        return best;
    }

    /** Tests of the SA/VNA input switch: need the VNA and the switch. */
    abstract static class SwitchTest extends Base {
        SwitchTest(String id, String title, Setup setup, int seconds, String description) {
            super(id, title, setup, seconds, description);
        }

        @Override
        public String notApplicable(MsaConfig cfg) {
            String s = super.notApplicable(cfg);
            return s != null ? s : cfg.hasVna() ? null : "needs the VNA";
        }

        @Override
        public String notApplicable(MsaConfig cfg, boolean inputSwitch) {
            String s = notApplicable(cfg);
            return s != null ? s : inputSwitch ? null : "needs the SA/VNA input switch";
        }
    }

    /** The path with the widest filter (most robust for level checks), and the narrowest. */
    static int widestPath(MsaConfig cfg) {
        int best = 1;
        for (int p = 1; p <= cfg.filters().size(); p++) if (cfg.filter(p).bwKHz() > cfg.filter(best).bwKHz()) best = p;
        return best;
    }

    static int narrowestPath(MsaConfig cfg) {
        int best = 1;
        for (int p = 1; p <= cfg.filters().size(); p++) if (cfg.filter(p).bwKHz() < cfg.filter(best).bwKHz()) best = p;
        return best;
    }

    static double[] dBm(SweepData d) {
        double[] y = new double[d.size()];
        for (int i = 0; i < y.length; i++) y[i] = d.dBm(i);
        return y;
    }

    static double[] phase(SweepData d) {
        double[] y = new double[d.size()];
        for (int i = 0; i < y.length; i++) y[i] = d.phase(i);
        return y;
    }

    static double[] freqs(SweepData d, double scale) {
        double[] x = new double[d.size()];
        for (int i = 0; i < x.length; i++) x[i] = d.frequency(i) * scale;
        return x;
    }

    static double[] adc(SweepData d, boolean phase) {
        double[] y = new double[d.size()];
        for (int i = 0; i < y.length; i++) y[i] = d.isMeasured(i) ? (phase ? d.phaseAdc(i) : d.adc(i)) : Double.NaN;
        return y;
    }

    static String kHz(double v) { return AutotestModel.num(v) + " kHz"; }

    static final String HARD_FAILURE_HINT = "Documentation, hard failure: a flat trace at -120 or 0 dBm points to the"
            + " computer/USB, control board, A-to-D converter or log detector (or their cables); a flat trace with a"
            + " little noise in between points to PLO 2, DDS 1, PLO 1, master oscillator, IF amplifier, final crystal"
            + " filter or coaxial cavity filter, in that order of testing.";

    // ================================================================ no wiring

    /**
     * "Zero" Frequency Sweep: centred at 0 Hz, 10 x the filter bandwidth; the signal is PLO 1 leaking through
     * mixer 1 into mixer 2, so the trace is the final filter's own response, centred at 0 Hz.
     */
    static final class ZeroFrequency extends Base {
        ZeroFrequency() {
            super("zero", "Zero-frequency sweep, every filter path", Setup.NONE, 20,
                    "The documentation's health test: centred at 0 Hz with a span of 10 x the filter bandwidth, the trace"
                    + " is the resolution filter's response to PLO 1 leaking through mixer 1. Checks for each path the"
                    + " peak level (repeatable on a healthy MSA; the verification unit read -22.6 dBm with a 50 ohm load,"
                    + " -26.3 dBm open), the centre (wrong config.txt filter frequency), the -3 dB bandwidth against"
                    + " config.txt, how far the skirts fall, and the shape: inside a filter mask, and against this MSA's reference.");
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            List<Double> peaks = new ArrayList<>();
            int measured = 0;
            for (int p = 1; p <= cfg.filters().size(); p++) {
                ctx.checkCancelled();
                double bw = cfg.filter(p).bwKHz();
                if (!ctx.hasPathCalibration(p)) {
                    r.hint("Path " + p + " has no path calibration (MSA_CalPath" + p + ".txt): not tested.");
                    continue;
                }
                ctx.progress("Zero-frequency sweep, path " + p + " (" + AutotestModel.num(bw) + " kHz)");
                double span = 10 * bw / 1000;
                SweepData d = ctx.sweep(sa(cfg, p, -span / 2, span / 2, 200, VideoFilter.WIDE, 0)).data();
                double[] x = freqs(d, 1000), y = dBm(d);
                String k = "zero.p" + p + ".";
                double pk = Analysis.max(y), lo = Analysis.min(y);
                double[] adc = adc(d, false);
                if (Analysis.max(adc) - Analysis.min(adc) == 0 || Analysis.max(adc) >= 65534 || Analysis.max(adc) <= 0
                        || pk - lo < 3) {
                    r.measure(k + "peak", "Path " + p + " peak level", pk, "dBm", new Limits(-45, -5, -60, 0), 2,
                            "no response: flat trace from " + AutotestModel.num(lo) + " to " + AutotestModel.num(pk) + " dBm");
                    r.hint(HARD_FAILURE_HINT);
                    r.trace(new Trace(null, "Path " + p + ": response at 0 Hz", "offset (kHz)", "dBm", x, y, null, null, null));
                    measured++;
                    continue;
                }
                peaks.add(pk);
                measured++;
                double[] edges = Analysis.crossings(x, y, pk - 3);
                double centre = Double.isNaN(edges[0]) || Double.isNaN(edges[1]) ? x[Analysis.peak(y)] : (edges[0] + edges[1]) / 2;
                double bw3 = edges[1] - edges[0];
                int n = y.length, e = Math.max(2, n / 10);
                double skirt = pk - Math.max(Analysis.max(java.util.Arrays.copyOfRange(y, 0, e)),
                        Analysis.max(java.util.Arrays.copyOfRange(y, n - e, n)));
                var mp = r.measure(k + "peak", "Path " + p + " peak level", pk, "dBm", new Limits(-45, -5, -60, 0), 2, null);
                var mc = r.measure(k + "centre", "Path " + p + " centre offset", centre, "kHz",
                        Limits.within(0, 0.1 * bw, 0.25 * bw), 0.05 * bw, null);
                var mb = r.measure(k + "bw3", "Path " + p + " -3 dB bandwidth", bw3, "kHz",
                        new Limits(0.6 * bw, 1.6 * bw, 0.4 * bw, 2.5 * bw), 0.1 * bw, "config.txt: " + kHz(bw));
                r.measure(k + "skirt", "Path " + p + " skirt rejection", skirt, "dB", Limits.atLeast(30, 20), 5,
                        "edges of the span (+-5 bandwidths) below the peak");
                if (mp.status() == Status.FAIL || mp.status() == Status.WARN) r.hint("A peak level far from the reference or"
                        + " from -45..-5 dBm: compare paths; if all are low, look at PLO 1/PLO 2 power and mixers 1 and 2"
                        + " (documentation: a starved mixer 2 LO gives a low level).");
                if (mc.status() != Status.PASS && !Double.isNaN(centre)) r.hint("Path " + p + " is centred " + kHz(centre)
                        + " from 0 Hz: its centre frequency in config.txt (Filter=) is probably off by that much.");
                if (mb.status() != Status.PASS && !Double.isNaN(bw3)) r.hint("Path " + p + " measures " + kHz(bw3)
                        + " wide at -3 dB, config.txt says " + kHz(bw) + ".");
                // shape: normalised to the peak, inside a design mask (at least -3 dB within +-0.3 bandwidths of
                // the centre, at most -20 dB beyond +-2.5 bandwidths) and, once saved, within 3 dB of this MSA's reference
                double[] norm = new double[n];
                for (int i = 0; i < n; i++) norm[i] = y[i] - pk;
                double[] low = new double[n], high = new double[n];
                int outside = 0;
                for (int i = 0; i < n; i++) {
                    double off = Math.abs(x[i] - centre);
                    low[i] = off <= 0.3 * bw ? -3 : Double.NaN;
                    high[i] = off >= 2.5 * bw ? -20 : Double.NaN;
                    if ((!Double.isNaN(low[i]) && norm[i] < low[i]) || (!Double.isNaN(high[i]) && norm[i] > high[i])) outside++;
                }
                var mm = r.measure(null, "Path " + p + " points outside the filter mask", outside, "", Limits.atMost(0, 4), Double.NaN,
                        ">= -3 dB within +-0.3 bandwidths, <= -20 dB beyond +-2.5 bandwidths");
                if (mm.status() != Status.PASS) r.hint("Path " + p + ": the filter response leaves the mask - a filter out of"
                        + " tune or damaged, a wrong bandwidth or centre in config.txt, or spurs near 0 Hz.");
                double[][] refCurve = r.reference().trace("zero.p" + p);
                double[] ref = null;
                if (refCurve != null) {
                    ref = Analysis.resample(refCurve, x);
                    double worst = 0;
                    for (int i = 0; i < n; i++) {
                        boolean judged = !Double.isNaN(ref[i]) && ref[i] > -40;
                        if (judged) {
                            low[i] = Double.isNaN(low[i]) ? ref[i] - 3 : Math.max(low[i], ref[i] - 3);
                            high[i] = Double.isNaN(high[i]) ? ref[i] + 3 : Math.min(high[i], ref[i] + 3);
                            if (!Double.isNaN(norm[i])) worst = Math.max(worst, Math.abs(norm[i] - ref[i]));
                        }
                    }
                    r.measure(null, "Path " + p + " shape vs reference", worst, "dB", Limits.atMost(3, 6), Double.NaN,
                            "largest difference where the reference is above -40 dB");
                }
                r.trace(new Trace("zero.p" + p, "Path " + p + ": filter response at 0 Hz (dB below peak)",
                        "offset (kHz)", "dB", x, norm, ref, low, high));
            }
            if (measured == 0) return Result.skipped(id(), title(), "no path has a path calibration");
            if (peaks.size() > 1) {
                double spread = peaks.stream().mapToDouble(Double::doubleValue).max().orElse(0)
                        - peaks.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                var ms = r.measure("zero.spread", "Peak level spread between paths", spread, "dB", Limits.atMost(3, 6), 1.5,
                        "the path calibrations should make the paths read alike");
                if (ms.status() != Status.PASS) r.hint("The paths disagree: a path calibration may be old, or a filter's loss changed.");
            }
            return r.build(measured + " path(s) swept at 0 Hz");
        }
    }

    /** The A-to-D converters deliver live readings (Log Detector / A to D signal tests). */
    static final class AdcAlive extends Base {
        AdcAlive() {
            super("adc", "A-to-D converters and detector alive", Setup.NONE, 3,
                    "100 readings at 0 Hz on the widest path, without averaging: the magnitude ADC must not sit at 0 or"
                    + " full scale and must show some noise; with the VNA the phase ADC as well. The magnitude reading in"
                    + " bits is compared with the reference (the verification unit read about 24000-25000 bits here).");
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            int p = widestPath(cfg);
            ctx.progress("ADC readings at 0 Hz, path " + p);
            SweepData d = ctx.sweep(sa(cfg, p, 0, 0, 99, VideoFilter.WIDE, 1)).data();
            double[] mag = adc(d, false);
            double mn = Analysis.min(mag), mx = Analysis.max(mag);
            boolean stuck = mx <= 0 || mn >= 65534;
            r.measure("adc.mag", "Magnitude ADC mean", Analysis.mean(mag), "bits", new Limits(1000, 64000, 1, 65534), 1500,
                    stuck ? "at a rail: the ADC, log detector or control board" : null);
            r.measure(null, "Magnitude ADC noise (std)", Analysis.std(mag), "bits", Limits.atLeast(0.5, 0.01), Double.NaN,
                    "a converter that never changes is probably stuck");
            if (stuck) r.hint(HARD_FAILURE_HINT);
            if (cfg.hasVna()) {
                double[] ph = adc(d, true);
                double pmn = Analysis.min(ph), pmx = Analysis.max(ph);
                boolean pstuck = pmx <= 0 || pmn >= 65534 || pmx - pmn == 0;
                r.measure(null, "Phase ADC range seen", pmx - pmn, "bits", Limits.atLeast(1, 1), Double.NaN,
                        pstuck ? "the phase reading never changes: phase detector module or second ADC" : "random here; only alive is checked");
            }
            r.info("adc.dbm", "Level at 0 Hz", Analysis.mean(dBm(d)), "dBm", 2, null);
            return r.build("magnitude " + AutotestModel.num(mn) + ".." + AutotestModel.num(mx) + " bits");
        }
    }

    /** Displayed noise floor on the narrowest and the widest path, input open. */
    static final class NoiseFloor extends Base {
        NoiseFloor() {
            super("noise", "Noise floor", Setup.NONE, 8,
                    "Median level over 100-110 MHz with nothing connected, on the narrowest and the widest path. The log"
                    + " detector's own floor (about -90 dBm at its input, about -103 dBm at the MSA input with the"
                    + " +13 dB of gain before it) limits the wide paths; narrow paths go lower (the documentation's coarse"
                    + " calibration uses -116 dBm).");
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            int narrow = narrowestPath(cfg), wide = widestPath(cfg);
            int[] paths = narrow == wide ? new int[] {narrow} : new int[] {narrow, wide};
            for (int p : paths) {
                ctx.checkCancelled();
                if (!ctx.hasPathCalibration(p)) {
                    r.hint("Path " + p + " has no path calibration: not tested.");
                    continue;
                }
                ctx.progress("Noise floor, path " + p);
                SweepData d = ctx.sweep(sa(cfg, p, 100, 110, 200, VideoFilter.WIDE, 0)).data();
                double floor = Analysis.median(dBm(d));
                boolean isNarrow = p == narrow && paths.length > 1;
                var m = r.measure("noise.p" + p, "Path " + p + " (" + AutotestModel.num(cfg.filter(p).bwKHz()) + " kHz) floor",
                        floor, "dBm", isNarrow ? Limits.atMost(-100, -90) : Limits.atMost(-90, -80), 3, null);
                if (m.status() != Status.PASS) r.hint("A high floor with nothing connected: an oscillating IF amplifier, a"
                        + " signal picked up at the input, or a wrong path calibration.");
                r.trace(new Trace(null, "Path " + p + ": 100-110 MHz, input open", "MHz", "dBm", freqs(d, 1), dBm(d), null, null, null));
            }
            return r.build("median level, input open");
        }
    }

    /** USB and ADC time per point. */
    static final class SweepSpeed extends Base {
        SweepSpeed() {
            super("speed", "USB and ADC speed", Setup.NONE, 4,
                    "200 points at one frequency with no wait: the time per point is the USB round trip and the ADC"
                    + " conversion (with 1 and with 16 samples averaged). A slow value means a slow USB connection or"
                    + " repeated reads.");
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            int p = widestPath(cfg);
            for (int samples : new int[] {1, 16}) {
                ctx.progress("Sweep speed, " + samples + " sample(s)");
                SweepSettings s = sa(cfg, p, 10, 10, 199, VideoFilter.WIDE, samples).withWait(0);
                AutotestContext.Sweep sw = ctx.sweep(s);
                double ms = 1000 * sw.seconds() / sw.data().size();
                r.measure("speed.s" + samples, "Time per point, " + samples + " sample(s)", ms, "ms",
                        samples == 1 ? Limits.atMost(5, 15) : Limits.atMost(10, 30), samples == 1 ? 1 : 2, null);
            }
            return r.build("time per point at zero span");
        }
    }

    /** Spurs and pick-up over the 1G band, input open (long). */
    static final class SpurScan extends Base {
        SpurScan() {
            super("spurs", "Spur scan 1-1000 MHz (long)", Setup.NONE, 60,
                    "The whole 1G band on the widest path with steps of half its bandwidth, nothing connected: lists"
                    + " the strongest responses above the floor. Some internal spurs are normal (the documentation"
                    + " describes PLO 3 - PLO 1 and PLO 3 - PLO 2 products); compare with the reference to see new ones.");
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            int p = widestPath(cfg);
            if (!ctx.hasPathCalibration(p)) return Result.skipped(id(), title(), "path " + p + " has no path calibration");
            SweepSettings s = sa(cfg, p, 1, 1000, 100, VideoFilter.WIDE, 0);
            s = s.withSteps(AutoSteps.of(s.range(), s.scale(), cfg.filter(p).bwKHz(), false));
            ctx.progress("Spur scan 1-1000 MHz, " + s.steps() + " steps");
            SweepData d = ctx.sweep(s).data();
            double[] x = freqs(d, 1), y = dBm(d);
            double floor = Analysis.median(y);
            List<double[]> spurs = new ArrayList<>();
            for (int i = 1; i < y.length - 1; i++) {
                if (Double.isNaN(y[i]) || y[i] < floor + 15 || y[i] < y[i - 1] || y[i] < y[i + 1]) continue;
                if (!spurs.isEmpty() && x[i] - spurs.get(spurs.size() - 1)[0] < 1) {
                    if (y[i] > spurs.get(spurs.size() - 1)[1]) spurs.set(spurs.size() - 1, new double[] {x[i], y[i]});
                    continue;
                }
                spurs.add(new double[] {x[i], y[i]});
            }
            spurs.sort((a, b) -> Double.compare(b[1], a[1]));
            r.info("spurs.floor", "Median floor", floor, "dBm", 3, null);
            r.info("spurs.count", "Responses 15 dB above the floor", spurs.size(), "", Double.NaN, null);
            for (int k = 0; k < Math.min(5, spurs.size()); k++) {
                double[] sp = spurs.get(k);
                r.measure(null, String.format(Locale.ROOT, "Spur at %.3f MHz", sp[0]), sp[1], "dBm", Limits.atMost(-70, -50),
                        Double.NaN, null);
            }
            double[][] refCurve = r.reference().trace("spurs");
            r.trace(new Trace("spurs", "1-1000 MHz, input open", "MHz", "dBm", x, y,
                    refCurve == null ? null : Analysis.resample(refCurve, x), null, null));
            return r.build(spurs.size() + " response(s) above the floor");
        }
    }

    // ================================================================ TG looped to the input

    /** MSA Frequency Range Test: TG level over the 1G band and where the range ends. */
    static final class TgRange extends Base {
        TgRange() {
            super("tgrange", "TG output and frequency range", Setup.TG_LOOP, 6,
                    "The documentation's MSA Frequency Range Test: with the TG looped to the input, 0-1000 MHz should read"
                    + " about -11 dBm (+-2 dB; the verification unit's TG gives -10.5 dBm at 100 MHz), and the 1G band"
                    + " ends a little above 1000 MHz where PLO 1 or PLO 3 runs out (1035 MHz on the verification unit).");
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            int p = widestPath(cfg);
            if (!ctx.hasPathCalibration(p)) return Result.skipped(id(), title(), "path " + p + " has no path calibration");
            ctx.progress("TG loop, 0-1000 MHz");
            SweepData d = ctx.sweep(trans(cfg, p, 0, 1000, 200, VideoFilter.WIDE, 0)).data();
            double[] x = freqs(d, 1), y = dBm(d);
            double[] band = Analysis.within(x, y, 1, 1000);
            double mean = Analysis.mean(band), lo = Analysis.min(band), hi = Analysis.max(band);
            var mm = r.measure("tg.mean", "Mean level 1-1000 MHz", mean, "dBm", Limits.within(-11, 3, 6), 1, null);
            r.info(null, "Lowest", lo, "dBm", Double.NaN, String.format(Locale.ROOT, "at %.1f MHz", x[indexOf(y, lo)]));
            r.info(null, "Highest", hi, "dBm", Double.NaN, String.format(Locale.ROOT, "at %.1f MHz", x[indexOf(y, hi)]));
            var mr = r.measure("tg.ripple", "Ripple (highest - lowest)", hi - lo, "dB", Limits.atMost(4, 8), 1.5, null);
            if (mm.status() == Status.FAIL && mean < -40) r.hint("Almost no TG signal: is the cable connected? Otherwise PLO 3,"
                    + " DDS 3, mixer 3 or the TG output amplifier.");
            else if (mm.status() != Status.PASS) r.hint("The TG level is off: the TG output stage, the cable, or the path calibration.");
            if (mr.status() != Status.PASS) r.hint("Dips or slopes across the band: the cable or connectors, mixer 1/3, or the"
                    + " coaxial cavity filter tuning.");
            double[][] refCurve = r.reference().trace("tg.range");
            double[] ref = null, low = null, high = null;
            if (refCurve != null) {
                ref = Analysis.resample(refCurve, x);
                low = new double[x.length];
                high = new double[x.length];
                double worst = 0;
                for (int i = 0; i < x.length; i++) {
                    boolean judged = x[i] >= 1 && !Double.isNaN(ref[i]);
                    low[i] = judged ? ref[i] - 1.5 : Double.NaN;
                    high[i] = judged ? ref[i] + 1.5 : Double.NaN;
                    if (judged && !Double.isNaN(y[i])) worst = Math.max(worst, Math.abs(y[i] - ref[i]));
                }
                r.measure(null, "Largest change from the reference", worst, "dB", Limits.atMost(1.5, 3), Double.NaN, null);
            }
            r.trace(new Trace("tg.range", "TG looped to the input, 0-1000 MHz", "MHz", "dBm", x, y, ref, low, high));
            // where the band ends
            ctx.checkCancelled();
            ctx.progress("TG loop, 950-1150 MHz (end of the 1G band)");
            try {
                SweepData top = ctx.sweep(trans(cfg, p, 950, 1150, 100, VideoFilter.WIDE, 0)).data();
                double[] tx = freqs(top, 1), ty = dBm(top);
                double limit = Double.NaN;
                for (int i = 0; i < ty.length; i++) {
                    if (Double.isNaN(ty[i]) || ty[i] < mean - 3) break;
                    limit = tx[i];
                }
                r.measure("tg.limit", "Highest frequency within 3 dB", limit, "MHz", Limits.atLeast(1000, 990), 10,
                        Double.isNaN(limit) ? "already more than 3 dB down at 950 MHz" : null);
                r.trace(new Trace(null, "End of the 1G band", "MHz", "dBm", tx, ty, null, null, null));
            } catch (IllegalStateException e) {
                r.info(null, "Highest frequency", Double.NaN, "MHz", Double.NaN, "not measured: " + e.getMessage());
            }
            return r.build(String.format(Locale.ROOT, "%.1f dBm mean, %.1f dB ripple", mean, hi - lo));
        }

        private static int indexOf(double[] y, double v) {
            for (int i = 0; i < y.length; i++) if (y[i] == v) return i;
            return 0;
        }
    }

    /** The same TG signal read through every path: the path calibrations must agree. */
    static final class PathConsistency extends Base {
        PathConsistency() {
            super("paths", "Path calibrations agree", Setup.TG_LOOP, 8,
                    "The TG at 100 MHz read through every filter path at zero span: with correct path calibrations all"
                    + " paths give the same level. A narrow path reading low can also mean the TG is not centred in it"
                    + " (master oscillator or final filter frequency in config.txt).");
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            List<Double> levels = new ArrayList<>();
            for (int p = 1; p <= cfg.filters().size(); p++) {
                ctx.checkCancelled();
                if (!ctx.hasPathCalibration(p)) {
                    r.hint("Path " + p + " has no path calibration: not tested.");
                    continue;
                }
                ctx.progress("TG at 100 MHz through path " + p);
                double level = Analysis.mean(dBm(ctx.sweep(trans(cfg, p, 100, 100, 19, VideoFilter.WIDE, 0)).data()));
                levels.add(level);
                r.info("paths.p" + p, "Path " + p + " (" + AutotestModel.num(cfg.filter(p).bwKHz()) + " kHz)", level, "dBm", 0.5, null);
            }
            if (levels.isEmpty()) return Result.skipped(id(), title(), "no path has a path calibration");
            double spread = levels.stream().mapToDouble(Double::doubleValue).max().orElse(0)
                    - levels.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            var m = r.measure("paths.spread", "Spread between paths", spread, "dB", Limits.atMost(1, 3), 0.5, null);
            if (m.status() != Status.PASS) r.hint("Repeat the path calibration of the path that differs (Initial Cal"
                    + " Manager in the BASIC program); if it is the narrowest path, check the master oscillator frequency.");
            return r.build(levels.size() + " path(s) read the same signal");
        }
    }

    /** The video filters change the smoothing, not the level. */
    static final class VideoFilters extends Base {
        VideoFilters() {
            super("video", "Video filters", Setup.TG_LOOP, 15,
                    "The TG at 100 MHz with each video filter: the level (and phase) must stay the same while the noise"
                    + " goes down. A different level means a faulty video filter switch or capacitor.");
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            int p = widestPath(cfg);
            if (!ctx.hasPathCalibration(p)) return Result.skipped(id(), title(), "path " + p + " has no path calibration");
            double wideLevel = Double.NaN, widePhase = Double.NaN;
            int[] points = {20, 20, 10, 5};
            for (VideoFilter v : VideoFilter.values()) {
                ctx.checkCancelled();
                ctx.progress("TG at 100 MHz, video filter " + v.name().toLowerCase(java.util.Locale.ROOT));
                SweepData d = ctx.sweep(trans(cfg, p, 100, 100, points[v.ordinal()] - 1, v, 0)).data();
                double level = Analysis.mean(dBm(d)), noise = Analysis.std(dBm(d));
                double ph = cfg.hasVna() ? circularMean(phase(d)) : Double.NaN;
                String name = v.name().charAt(0) + v.name().substring(1).toLowerCase(java.util.Locale.ROOT);
                if (v == VideoFilter.WIDE) {
                    wideLevel = level;
                    widePhase = ph;
                    r.info("video.wide", "Wide: level", level, "dBm", 1, null);
                } else {
                    r.measure(null, name + ": level - Wide", level - wideLevel, "dB", Limits.within(0, 0.5, 1.5), Double.NaN, null);
                    if (cfg.hasVna()) r.measure(null, name + ": phase - Wide", wrap(ph - widePhase), "deg", Limits.within(0, 2, 5),
                            Double.NaN, null);
                }
                r.info(null, name + ": noise (std)", noise, "dB", Double.NaN, null);
            }
            return r.build("level with each video filter");
        }
    }

    /** Short-term stability of magnitude and phase at one frequency. */
    static final class Stability extends Base {
        Stability() {
            super("stability", "Short-term stability", Setup.TG_LOOP, 4,
                    "100 readings of the TG at 100 MHz: the spread of magnitude and phase. Large values mean noise, a PLO"
                    + " that does not stay locked, or a loose connection.");
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            int p = widestPath(cfg);
            ctx.progress("TG at 100 MHz, 100 readings");
            SweepData d = ctx.sweep(trans(cfg, p, 100, 100, 99, VideoFilter.WIDE, 0)).data();
            double[] m = dBm(d);
            r.measure("stab.mag", "Magnitude std", Analysis.std(m), "dB", Limits.atMost(0.15, 0.5), 0.1, null);
            r.info(null, "Magnitude peak-to-peak", Analysis.max(m) - Analysis.min(m), "dB", Double.NaN, null);
            if (cfg.hasVna()) {
                double[] ph = Analysis.unwrap(phase(d));
                r.measure("stab.phase", "Phase std", Analysis.std(ph), "deg", Limits.atMost(1, 3), 0.5, null);
            }
            r.trace(new Trace(null, "TG at 100 MHz, 100 readings", "reading", "dBm", index(d.size()), m, null, null, null));
            return r.build("spread of 100 readings");
        }
    }

    /** Through-cable phase must be a straight line: no jumps where the PDM inverts. */
    static final class PhaseLinearity extends Base {
        private final MsaMode mode;
        private final String key;

        PhaseLinearity() { this("phase", "Phase linearity and PDM inversion steps", Setup.TG_LOOP, MsaMode.VECTOR_TRANS); }

        PhaseLinearity(String id, String title, Setup setup, MsaMode mode) {
            super(id, title, setup, 6,
                    "VNA: the phase of a cable is a straight line over frequency (its slope is the delay of the cable and"
                    + " the MSA's internal cables). Measured 10-950 MHz without any operating calibration; the deviation"
                    + " from the best straight line, and any step where the PDM was inverted (a wrong invdeg shows as a"
                    + " step there). Anything else in the loop (an input switch with its buffer amplifier, a filter) adds"
                    + " its own phase curve, so the deviation is judged loosely; the curve is compared with this MSA's"
                    + " reference once one is saved." + (mode == MsaMode.REFLECTION ? " This one runs in reflection"
                    + " mode, so the input switch selects the R input." : ""));
            this.mode = mode;
            this.key = id;
        }

        @Override
        public String notApplicable(MsaConfig cfg) {
            String s = super.notApplicable(cfg);
            return s != null ? s : cfg.hasVna() ? null : "needs the VNA phase detector";
        }

        @Override
        public String notApplicable(MsaConfig cfg, boolean inputSwitch) {
            String s = notApplicable(cfg);
            return s == null && mode == MsaMode.REFLECTION && !inputSwitch ? "needs the SA/VNA input switch" : s;
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            int p = widestPath(cfg);
            ctx.progress("Phase of the TG loop, 10-950 MHz" + (mode == MsaMode.REFLECTION ? ", R input" : ""));
            SweepData d = ctx.sweep(vna(cfg, mode, p, 10, 950, 400, VideoFilter.WIDE)).data();
            double[] x = freqs(d, 1), ph = phase(d);
            if (Analysis.peak(ph) < 0) return r.error("no phase readings");
            double[] u = Analysis.unwrap(ph);
            double[] line = Analysis.fitLine(x, u);
            double[] res = Analysis.residuals(x, u, line);
            double delayNs = -line[1] / 360 * 1000;
            r.info(key + ".delay", "Delay of the loop (slope)", delayNs, "ns", 0.3, "cable plus internal cables");
            var mr = r.measure(key + ".rms", "Deviation from a straight line (rms)", Analysis.rms(res), "deg",
                    Limits.atMost(10, 30), 2, "an input switch or amplifier in the loop adds its own curve");
            if (mr.status() != Status.PASS) r.hint("A large deviation from a straight line: with an input switch or other"
                    + " parts in the loop that can be normal (check the curve and keep it as the reference). Otherwise look"
                    + " for a step or a wrap that the PDM did not handle, or a bad phase reading at the ends of the band.");
            double[][] refCurve = r.reference().trace(key + ".dev");
            double[] ref = null, low = null, high = null;
            if (refCurve != null) {
                ref = Analysis.resample(refCurve, x);
                low = new double[x.length];
                high = new double[x.length];
                double sum = 0;
                int n = 0;
                for (int i = 0; i < x.length; i++) {
                    low[i] = ref[i] - 5;
                    high[i] = ref[i] + 5;
                    if (Double.isNaN(ref[i]) || Double.isNaN(res[i])) continue;
                    sum += (res[i] - ref[i]) * (res[i] - ref[i]);
                    n++;
                }
                if (n > 0) r.measure(null, "Change of the curve since the reference (rms)", Math.sqrt(sum / n), "deg",
                        Limits.atMost(2, 5), Double.NaN, "the deviation curve compared with the saved one");
            }
            double step = 0;
            int changes = 0;
            for (int i = 1; i < res.length; i++) {
                if (d.pdm(i) == d.pdm(i - 1) || Double.isNaN(res[i]) || Double.isNaN(res[i - 1])) continue;
                changes++;
                double expected = (res.length > i + 1 && i > 1) ? ((res[i + 1] - res[i]) + (res[i - 1] - res[i - 2])) / 2 : 0;
                step = Math.max(step, Math.abs(res[i] - res[i - 1] - expected));
            }
            var ms = r.measure(key + ".step", "Largest step at a PDM inversion", step, "deg", Limits.atMost(5, 15), Double.NaN,
                    changes + " inversion(s) in the sweep");
            if (ms.status() != Status.PASS) r.hint("Steps where the PDM inverts mean the inversion angle (config.txt invdeg) is"
                    + " wrong: run Setup > PDM Calibration.");
            r.trace(new Trace(key + ".dev", "Phase deviation from a straight line", "MHz", "deg", x, res, ref, low, high));
            return r.build(String.format(Locale.ROOT, "%.2f ns, %.1f deg rms", delayNs, Analysis.rms(res)));
        }
    }

    /**
     * Isolation of the input switch's T/R selection: the TG on one input, read with that input selected and then
     * with the other one (transmission mode drives the T/R latch bit to 0 = T, reflection to 1 = R).
     */
    static final class Isolation extends SwitchTest {
        private final boolean fromT;

        Isolation(boolean fromT) {
            super(fromT ? "isoT" : "isoR", fromT ? "Input switch isolation, T to R" : "Input switch isolation, R to T",
                    fromT ? Setup.TG_LOOP : Setup.TG_REFLECT, 12,
                    "SA/VNA input switch: with the TG on the " + (fromT ? "T" : "R") + " input, 1-1000 MHz is read with the"
                    + " transmit/reflect latch bit selecting that input (" + (fromT ? "transmission" : "reflection")
                    + " mode) and then the other (" + (fromT ? "reflection" : "transmission") + " mode). The difference"
                    + " is the isolation; it also shows that the latch bit reaches the switch. When the unselected"
                    + " reading is the noise floor the value is a lower bound. The Wetterlin design aims at about 100 dB.");
            this.fromT = fromT;
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            int p = quietPath(cfg);
            if (!ctx.hasPathCalibration(p)) return Result.skipped(id(), title(), "path " + p + " has no path calibration");
            MsaMode sel = fromT ? MsaMode.VECTOR_TRANS : MsaMode.REFLECTION, other = fromT ? MsaMode.REFLECTION : MsaMode.VECTOR_TRANS;
            String in = fromT ? "T" : "R", out = fromT ? "R" : "T";
            ctx.progress("TG on " + in + ", " + in + " selected");
            SweepData a = ctx.sweep(vna(cfg, sel, p, 1, 1000, 50, VideoFilter.WIDE)).data();
            ctx.checkCancelled();
            ctx.progress("TG on " + in + ", " + out + " selected");
            SweepData b = ctx.sweep(vna(cfg, other, p, 1, 1000, 50, VideoFilter.WIDE)).data();
            double[] x = freqs(a, 1), ya = dBm(a), yb = dBm(b), iso = new double[x.length];
            for (int i = 0; i < x.length; i++) iso[i] = ya[i] - yb[i];
            double worst = Analysis.min(iso);
            int at = 0;
            for (int i = 0; i < iso.length; i++) if (iso[i] == worst) at = i;
            String k = id() + ".";
            r.info(k + "level", in + " selected: mean level", Analysis.mean(ya), "dBm", 2, null);
            r.info(null, out + " selected: mean level", Analysis.mean(yb), "dBm", Double.NaN, "usually the noise floor");
            var m = r.measure(k + "worst", "Isolation " + in + " to " + out + " (worst)", worst, "dB", Limits.atLeast(70, 50),
                    10, String.format(Locale.ROOT, "at %.0f MHz", x[at]));
            if (Analysis.mean(ya) < -60) {
                r.hint("Almost no signal with " + in + " selected: is the TG cable on the " + in + " input and the switch in the"
                        + " VNA position? If the signal appears with " + out + " selected instead, the T/R line is inverted"
                        + " or not connected.");
                r.atLeast(Status.FAIL);
            } else if (m.status() != Status.PASS) {
                r.hint("Little isolation: if both readings are almost equal the T/R select line does not reach the switch"
                        + " (control board latch, cable); otherwise check the switch's terminations and shielding.");
            }
            double[] floor = new double[x.length];
            java.util.Arrays.fill(floor, 70);
            r.trace(new Trace(null, "Isolation " + in + " to " + out, "MHz", "dB", x, iso, null, floor, null));
            r.trace(new Trace(null, "TG on " + in + ", " + in + " selected", "MHz", "dBm", x, ya, null, null, null));
            r.trace(new Trace(null, "TG on " + in + ", " + out + " selected", "MHz", "dBm", x, yb, null, null, null));
            return r.build(String.format(Locale.ROOT, "at least %.0f dB", Math.floor(worst)));
        }
    }

    /** The R input's path through the input switch: level and flatness in reflection mode. */
    static final class ReflectionPath extends SwitchTest {
        ReflectionPath() {
            super("rpath", "Reflection (R) input path", Setup.TG_REFLECT, 5,
                    "SA/VNA input switch: the TG on the R input, read in reflection mode (latch bit = R), 1-1000 MHz:"
                    + " the level (the R path has a 6 dB attenuator and two switch stages) and its flatness, and the curve"
                    + " against this MSA's reference.");
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            int p = widestPath(cfg);
            if (!ctx.hasPathCalibration(p)) return Result.skipped(id(), title(), "path " + p + " has no path calibration");
            ctx.progress("TG on R, 1-1000 MHz");
            SweepData d = ctx.sweep(vna(cfg, MsaMode.REFLECTION, p, 1, 1000, 200, VideoFilter.WIDE)).data();
            double[] x = freqs(d, 1), y = dBm(d);
            double mean = Analysis.mean(y), lo = Analysis.min(y), hi = Analysis.max(y);
            var mm = r.measure("rpath.mean", "Mean level 1-1000 MHz", mean, "dBm", new Limits(-35, -5, -50, 0), 1, null);
            var mr = r.measure("rpath.ripple", "Ripple (highest - lowest)", hi - lo, "dB", Limits.atMost(5, 10), 1.5, null);
            if (mm.status() == Status.FAIL && mean < -50) r.hint("Almost no signal on the R input: the cable, the switch in the"
                    + " VNA position, or the T/R line (see the isolation tests).");
            if (mr.status() != Status.PASS) r.hint("Uneven R path: connectors, the R attenuator, or a switch stage.");
            double[][] refCurve = r.reference().trace("rpath.level");
            double[] ref = null, low = null, high = null;
            if (refCurve != null) {
                ref = Analysis.resample(refCurve, x);
                low = new double[x.length];
                high = new double[x.length];
                double worst = 0;
                for (int i = 0; i < x.length; i++) {
                    low[i] = ref[i] - 1.5;
                    high[i] = ref[i] + 1.5;
                    if (!Double.isNaN(ref[i]) && !Double.isNaN(y[i])) worst = Math.max(worst, Math.abs(y[i] - ref[i]));
                }
                r.measure(null, "Largest change from the reference", worst, "dB", Limits.atMost(1.5, 3), Double.NaN, null);
            }
            r.trace(new Trace("rpath.level", "TG to the R input, reflection mode", "MHz", "dBm", x, y, ref, low, high));
            return r.build(String.format(Locale.ROOT, "%.1f dBm mean, %.1f dB ripple", mean, hi - lo));
        }
    }

    /** Re-measures the PDM inversion and compares it with config.txt. */
    static final class PdmInversion extends Base {
        PdmInversion() {
            super("pdm", "PDM inversion angle", Setup.TG_LOOP, 15,
                    "VNA: at a frequency where the loop's phase is near +-90 degrees, reads the phase with the PDM normal"
                    + " and inverted (5 s settling each, as Setup > PDM Calibration) and compares the inversion with"
                    + " config.txt invdeg. Nothing is saved.");
        }

        @Override
        public String notApplicable(MsaConfig cfg) {
            String s = super.notApplicable(cfg);
            return s != null ? s : cfg.hasVna() ? null : "needs the VNA phase detector";
        }

        @Override
        public Result run(AutotestContext ctx) {
            MsaConfig cfg = ctx.config();
            ResultBuilder r = builder(ctx);
            int p = widestPath(cfg);
            ctx.progress("Looking for a frequency with the phase near +-90 degrees");
            SweepData d = ctx.sweep(trans(cfg, p, 20, 220, 100, VideoFilter.WIDE, 0)).data();
            int best = -1;
            for (int i = 0; i < d.size(); i++) {
                double ph = d.phase(i);
                if (Double.isNaN(ph)) continue;
                if (best < 0 || Math.abs(Math.abs(ph) - 90) < Math.abs(Math.abs(d.phase(best)) - 90)) best = i;
            }
            if (best < 0) return r.error("no phase readings");
            double f = d.frequency(best);
            ctx.progress(String.format(Locale.ROOT, "PDM inversion at %.3f MHz (about 10 s)", f));
            ctx.sweep(trans(cfg, p, f, f, 9, VideoFilter.WIDE, 1));
            var c = ctx.calibratePdm();
            double configured = msa.sweep.PhaseDetector.of(cfg).invDeg();
            var m = r.measure("pdm.invdeg", "Measured inversion", c.invDeg(), "deg", Limits.within(configured, 2, 5), 1,
                    "config.txt invdeg = " + AutotestModel.num(configured));
            boolean quadrature = Math.abs(Math.abs(c.phaseNormalDeg() - 180) - 90) <= 30;
            r.measure(null, "Phase reading (PDM normal)", c.phaseNormalDeg(), "deg", Limits.info(), Double.NaN,
                    String.format(Locale.ROOT, "at %.3f MHz%s", f, quadrature ? "" : "; not near 90/270, less accurate"));
            if (!quadrature) r.atLeast(Status.WARN);
            if (m.status() != Status.PASS) r.hint("Run Setup > PDM Calibration and save the new value.");
            return r.build(String.format(Locale.ROOT, "%.2f deg (config.txt %.2f)", c.invDeg(), configured));
        }
    }

    // ================================================================ small helpers

    static double circularMean(double[] deg) {
        double s = 0, c = 0;
        for (double v : deg) {
            if (Double.isNaN(v)) continue;
            s += Math.sin(Math.toRadians(v));
            c += Math.cos(Math.toRadians(v));
        }
        return s == 0 && c == 0 ? Double.NaN : Math.toDegrees(Math.atan2(s, c));
    }

    static double wrap(double deg) { return msa.sweep.PhaseDetector.normalise(deg); }

    static double[] index(int n) {
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = i;
        return x;
    }
}
