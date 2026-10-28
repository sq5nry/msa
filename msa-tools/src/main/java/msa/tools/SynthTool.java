package msa.tools;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import msa.config.MsaConfig;
import msa.config.PathCalibration;
import msa.hw.AdcConfig;
import msa.hw.LoggingTransport;
import msa.hw.MsaException;
import msa.hw.MsaProtocol;
import msa.hw.MsaTransport;
import msa.hw.SlimCommands;
import msa.hw.SlimCommands.VideoFilter;
import msa.hw.UsbMsa;
import msa.hw.sim.SimulatedTransport;
import msa.hw.usb.LibUsbTransport;
import msa.synth.HardwareInit;
import msa.synth.SynthException;
import msa.synth.SynthPlanner;
import msa.synth.SynthPlanner.StepPlan;
import msa.sweep.Sweeper;

/**
 * Synthesiser commands for msa-probe:
 * <pre>
 *   plan FREQ            print the Variables Window values and the bytes for one frequency (no hardware)
 *   init                 run [InitializeHardware]
 *   tune FREQ            init, command FREQ, read the ADCs (--count N, default 10)
 *   sweep START STOP STEPS   init, sweep STEPS intervals, print a table (and --csv FILE)
 * options:
 *   --msa-info DIR   folder with config.txt and MSA_Cal (default: the project's msa-info folder,
 *                    see msa.config.MsaInfo)
 *   --path P         resolution filter path 1..4 (default 1)
 *   --band B         1, 2, 3, or 0 = auto by frequency (default 1, like the BASIC program's freqBand=1)
 *   --video V        wide|mid|narrow|xnarrow (default wide)
 *   --wait MS        wait before each ADC read, BASIC "Wait" box (default 5)
 *   --samples N      FX2 averaging 1..255 (default 1)
 *   --tg-offset MHZ  tracking generator offset (default 0)
 *   --sg MHZ         signal generator mode at MHZ instead of tracking generator
 *   --csv FILE       write every point with all synthesiser values
 *   --replay FILE    use a recording (see 'record') instead of the MSA
 * </pre>
 * Frequencies are in MHz.
 */
final class SynthTool {
    private SynthTool() {}

    static final List<String> COMMANDS = List.of("plan", "init", "tune", "sweep");

    static int run(List<String> args, boolean sim, Path log, int settleMs) {
        Opts o = new Opts(args);
        MsaConfig cfg;
        Path info = o.msaInfo();
        try {
            cfg = MsaConfig.read(info.resolve("config.txt"));
        } catch (IOException e) {
            throw new MsaException("cannot read " + info.resolve("config.txt") + " (use --msa-info DIR)", e);
        }
        int path = o.integer("--path", 1);
        SynthPlanner planner = new SynthPlanner(cfg, path);
        SynthPlanner.Generator gen = o.has("--sg")
                ? new SynthPlanner.SignalGen(o.number("--sg", 10))
                : new SynthPlanner.Tracking(o.number("--tg-offset", 0));
        int band = o.integer("--band", 1);
        String cmd = o.positional(0);

        if (cmd.equals("plan")) {
            StepPlan p = planner.plan(o.positionalNumber(1), band, gen);
            printPlan(planner, p);
            return 0;
        }

        PathCalibration cal = null;
        try {
            cal = PathCalibration.load(info, path);
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("note: no usable path calibration for path " + path + " (" + e.getMessage() + "); showing ADC values only");
        }

        MsaTransport t = transport(o, sim, cfg, planner);
        if (log != null) t = LoggingTransport.toFile(t, log);
        VideoFilter video = VideoFilter.valueOf(o.text("--video", "wide").toUpperCase(Locale.ROOT));
        int wait = o.integer("--wait", 5);
        AdcConfig adc = AdcConfig.defaults().withAverage(o.integer("--samples", 1));

        try (UsbMsa msa = new UsbMsa(t)) {
            System.out.printf("opened %s; path %d (final IF %.5f MHz, %s kHz), LO2 %.6f MHz%n",
                    t.description(), path, planner.finalFreq(), fmt(cfg.filter(path).bwKHz()), planner.lo2());
            Sweeper sw = new Sweeper(msa, planner, gen);
            int initBand = band == 0 ? 1 : band;
            if (cmd.equals("tune") || cmd.equals("sweep")) {
                double f0 = o.positionalNumber(1);
                initBand = band == 0 ? SynthPlanner.autoBand(f0) : band;
            }
            sw.initialise(new HardwareInit.Switches(video, initBand, false, false), settleMs);
            System.out.println("hardware initialised");
            switch (cmd) {
                case "init" -> { }
                case "tune" -> tune(sw, o, band, wait, adc, cal);
                case "sweep" -> sweep(sw, o, band, wait, adc, cal);
                default -> throw new IllegalStateException(cmd);
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ commands

    private static void printPlan(SynthPlanner pl, StepPlan p) {
        System.out.printf(Locale.ROOT, """
                thisfreq = %.6f MHz (true %.6f MHz, band %d)
                dds1output = %.10f MHz   (tuning word %d)
                LO 1 = %.7f MHz
                pdf1 = %.9f MHz
                ncounter1 = %d   Bcounter1 = %d   Acounter1 = %d   fcounter1 = 0   rcounter1 = %d
                LO2 = %.7f MHz
                pdf2 = %.9f MHz
                ncounter2 = %d   Bcounter2 = %d   Acounter2 = %d   rcounter2 = %d
                LO3 = %.7f MHz
                pdf3 = %.9f MHz
                ncounter3 = %d   Bcounter3 = %d   Acounter3 = %d   fcounter3 = 0   rcounter3 = %d
                dds3output = %.10f MHz   (tuning word %d)
                SG/TG Out = %.7f MHz
                Real Final I.F. = %.7f MHz
                PLL1 N word 0x%06X, PLL3 N word 0x%06X
                A7 step command: %s
                """,
                p.equivFreq(), p.trueFreq(), p.band(), p.dds1Output(), p.dds1Base(), p.lo1(), p.pdf1(),
                p.ncounter1(), p.bcounter1(), p.acounter1(), pl.rcounter1(),
                pl.lo2(), pl.pdf2(), pl.ncounter2(), pl.pll2N().bcounter(), pl.pll2N().acounter(), pl.rcounter2(),
                p.lo3(), p.pdf3(), p.ncounter3(), p.bcounter3(), p.acounter3(), pl.rcounter3(),
                p.dds3Output(), p.dds3Base(), p.generatorOut(), p.realFinalIf(),
                p.pll1Bits(), p.pll3Bits(),
                p.command(SlimCommands.filterBank(pl.path()), 0).hex());
        System.out.println("init sequence (" + HardwareInit.sequence(pl, HardwareInit.Switches.defaults()).size() + " commands):");
        for (SlimCommands.Op op : HardwareInit.sequence(pl, HardwareInit.Switches.defaults()))
            System.out.println("  " + op.hex() + (op.sleepAfterMs() > 0 ? "   (then " + op.sleepAfterMs() + " ms)" : ""));
    }

    private static void tune(Sweeper sw, Opts o, int band, int wait, AdcConfig adc, PathCalibration cal) {
        double f = o.positionalNumber(1);
        int count = o.integer("--count", 10);
        for (int i = 0; i < count; i++) {
            Sweeper.Point pt = sw.measure(f, band, wait, adc, cal);
            if (i == 0)
                System.out.printf("%.6f MHz band %d: LO1 %.6f, LO3 %.6f, TG/SG out %.6f MHz%n",
                        f, pt.plan().band(), pt.plan().lo1(), pt.plan().lo3(), pt.plan().generatorOut());
            System.out.printf("  %3d  mag %5d  phase %5d%s%n", i, pt.adc().adc1(), pt.adc().adc2(), dbm(pt));
        }
    }

    private static void sweep(Sweeper sw, Opts o, int band, int wait, AdcConfig adc, PathCalibration cal) {
        double start = o.positionalNumber(1), stop = o.positionalNumber(2);
        int steps = (int) o.positionalNumber(3);
        List<Sweeper.Point> pts = new ArrayList<>();
        long t0 = System.nanoTime();
        System.out.println("  step      MHz  band    mag  phase      dBm");
        try {
            sw.sweep(start, stop, steps, band, wait, adc, cal, pt -> {
                pts.add(pt);
                System.out.printf("  %4d %10.4f  %d   %5d  %5d %s%n", pts.size() - 1, pt.plan().trueFreq(), pt.plan().band(),
                        pt.adc().adc1(), pt.adc().adc2(), Double.isNaN(pt.dBm()) ? "" : String.format("%8.2f", pt.dBm()));
            });
        } catch (SynthException e) {
            System.out.println("stopped: " + e.getMessage());
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("%d points in %.2f s%n", pts.size(), secs);
        if (cal != null && band == 1 && stop > 1050)
            System.out.println("note: above 1050 MHz your MSA_CalFreq.txt holds 2G corrections; in a forced band-1 sweep"
                    + " the dBm there is off by the step in that table (see the freqCorrDb column)");
        if (!pts.isEmpty() && cal != null) {
            Sweeper.Point max = pts.stream().max((a, b) -> Double.compare(a.dBm(), b.dBm())).get();
            Sweeper.Point min = pts.stream().min((a, b) -> Double.compare(a.dBm(), b.dBm())).get();
            System.out.printf("max %.2f dBm at %.4f MHz, min %.2f dBm at %.4f MHz%n",
                    max.dBm(), max.plan().trueFreq(), min.dBm(), min.plan().trueFreq());
        }
        String csv = o.text("--csv", null);
        if (csv != null) writeCsv(Path.of(csv), pts);
    }

    private static void writeCsv(Path file, List<Sweeper.Point> pts) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("step,freqMHz,band,equivMHz,magADC,phaseADC,dBm,freqCorrDb,LO1,dds1,n1,LO3,dds3,n3,tgOut,realFinalIF");
            int i = 0;
            for (Sweeper.Point p : pts) {
                StepPlan s = p.plan();
                w.printf(Locale.ROOT, "%d,%.6f,%d,%.6f,%d,%d,%s,%s,%.7f,%.10f,%d,%.7f,%.10f,%d,%.7f,%.7f%n",
                        i++, s.trueFreq(), s.band(), s.equivFreq(), p.adc().adc1(), p.adc().adc2(),
                        Double.isNaN(p.dBm()) ? "" : String.format(Locale.ROOT, "%.3f", p.dBm()),
                        Double.isNaN(p.freqCorrDb()) ? "" : String.format(Locale.ROOT, "%.3f", p.freqCorrDb()),
                        s.lo1(), s.dds1Output(), s.ncounter1(), s.lo3(), s.dds3Output(), s.ncounter3(),
                        s.generatorOut(), s.realFinalIf());
            }
            System.out.println("wrote " + file.toAbsolutePath());
        } catch (IOException e) {
            throw new MsaException("cannot write " + file, e);
        }
    }

    private static String dbm(Sweeper.Point p) {
        return Double.isNaN(p.dBm()) ? "" : String.format("  %8.2f dBm", p.dBm());
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------------ simulator with a signal

    /** --replay FILE, --sim, or the real MSA. */
    static MsaTransport transport(Opts o, boolean sim, MsaConfig cfg, SynthPlanner planner) {
        if (o.has("--replay")) {
            try {
                return msa.replay.ReplayTransport.transport(Path.of(o.text("--replay", "")), cfg);
            } catch (IOException e) {
                throw new MsaException("cannot read recording: " + e.getMessage(), e);
            }
        }
        return sim ? simulated(planner) : LibUsbTransport.open();
    }

    /**
     * Simulated MSA whose ADC follows the tracking generator through an invented 1 GHz low-pass
     * (band 1), so 'sweep --sim' shows something filter-like. Not a model of the real hardware.
     */
    static MsaTransport simulated(SynthPlanner pl) {
        Random rnd = new Random(7);
        double clk = pl.config().masterClock();
        SimulatedTransport.AdcModel model = s -> {
            double lo1 = nOf(s.pll1Bits) * (s.dds1Word * clk / 4294967296.0) / pl.rcounter1();
            double tuned = lo1 - pl.lo2() + pl.finalFreq();
            double lo3 = nOf(s.pll3Bits) * (s.dds3Word * clk / 4294967296.0) / Math.max(1, pl.rcounter3());
            double tg = lo3 - pl.lo2();
            double level = Math.abs(tg - tuned) < 0.05 ? -10 - lowPass(tuned) : -100; // TG looped to input
            long adc = Math.round(10000 + 328 * (level + 100) + rnd.nextGaussian() * 30);
            return new long[] {Math.max(0, Math.min(65535, adc)), 32768};
        };
        SimulatedTransport st = new SimulatedTransport(model, 0x1024, 33, 0);
        st.setTrailingStatusFrames(1);
        return st;
    }

    private static int nOf(int bits) { return ((bits >> 7) & 0x1FFF) * 32 + ((bits >> 2) & 31); }

    /** Invented 7-pole-ish low-pass at 1000 MHz, attenuation in dB. */
    private static double lowPass(double f) {
        double x = f / 1000.0;
        return 10 * Math.log10(1 + Math.pow(x, 14));
    }

    // ------------------------------------------------------------------ options

    static final class Opts {
        private final List<String> pos = new ArrayList<>();
        private final java.util.Map<String, String> opt = new java.util.HashMap<>();

        Opts(List<String> args) {
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i);
                if (a.startsWith("--")) {
                    if (i + 1 >= args.size()) throw new MsaException(a + " needs a value");
                    opt.put(a, args.get(++i));
                } else {
                    pos.add(a);
                }
            }
        }

        boolean has(String k) { return opt.containsKey(k); }
        String text(String k, String d) { return opt.getOrDefault(k, d); }
        int integer(String k, int d) { return has(k) ? Integer.parseInt(opt.get(k)) : d; }
        double number(String k, double d) { return has(k) ? Double.parseDouble(opt.get(k)) : d; }

        String positional(int i) { return pos.get(i); }

        int positionalCount() { return pos.size(); }

        double positionalNumber(int i) {
            if (i >= pos.size()) throw new MsaException(pos.get(0) + ": missing argument " + i);
            return Double.parseDouble(pos.get(i));
        }

        Path msaInfo() {
            try {
                return msa.config.MsaInfo.locate(has("--msa-info") ? opt.get("--msa-info") : null);
            } catch (IllegalArgumentException e) {
                throw new MsaException(e.getMessage());
            }
        }
    }
}
