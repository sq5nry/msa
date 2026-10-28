package msa.synth;

import msa.config.MsaConfig;
import msa.hw.SlimCommands;

/**
 * Frequency planning for the SLIM MSA, ported from spectrumanalyzer.bas v118:
 * [InitializeHardware] steps 7-9 (R counters, PLL2), [CalculateAllStepsForLO1Synth],
 * [CalculateAllStepsForLO3Synth], [CreateRcounter], [CreateIntegerNcounter], [CreateBaseForDDSarray],
 * Equiv1GFreq / ActualSignalFrequency.
 *
 * <p>Signal path: LO1 = f + LO2 - finalfreq (1st IF = LO2 - finalfreq, ~1013 MHz), LO2 fixed (~1024 MHz),
 * final IF = finalfreq of the selected path (10.7 MHz). PLL1 and PLL3 use DDS1 / DDS3 as their
 * reference, so the fine tuning is done by the DDS: dds1 = pdf * rcounter1.
 *
 * <p>Only what this MSA's config uses is ported: LMX2326 PLLs in integer-N mode, TGtop 1 or 2,
 * spur test off, baseFrequency 0. Anything else raises an exception rather than guessing.
 */
public final class SynthPlanner {

    /** Tracking generator / signal generator setting (gentrk, normrev, offset, sgout). */
    public sealed interface Generator permits Tracking, SignalGen {}
    /**
     * gentrk = 1: TG follows the analyser frequency plus an offset in MHz.
     * {@code reverse} (normrev = 1): the TG runs through the sweep backwards, i.e. at step i it outputs the
     * frequency of step (steps - i); the caller supplies that frequency to {@link #plan(double, int, Generator, double)}.
     */
    public record Tracking(double offsetMHz, boolean reverse) implements Generator {
        public Tracking(double offsetMHz) { this(offsetMHz, false); }
    }
    /** gentrk = 0: fixed output frequency sgout in MHz (BASIC default 10 MHz). */
    public record SignalGen(double sgoutMHz) implements Generator {}

    /** BASIC defaults: bandEnd1G = 1000, bandEnd2G = 2000. */
    public static final double BAND_END_1G = 1000, BAND_END_2G = 2000;

    private final MsaConfig cfg;
    private final int path;
    private final double finalFreq;
    private final double masterClock;

    // PLL2 (fixed LO2)
    private final int r2, n2;
    private final double pdf2, lo2;
    private final Lmx2326.NWord n2Word;
    // PLL1 / PLL3 references
    private final int r1, r3;

    public SynthPlanner(MsaConfig cfg, int path) {
        this.cfg = cfg;
        this.path = path;
        this.finalFreq = cfg.filter(path).freqMHz();
        this.masterClock = cfg.masterClock();
        requireSupported(cfg.pll1(), "PLL1");
        requireSupported(cfg.pll2(), "PLL2");
        if (cfg.tgTop() > 0) requireSupported(cfg.pll3(), "PLL3");

        // 8. PLL2: R from masterclock, integer N for appxLO2
        r2 = rcounter(masterClock, cfg.pll2().phaseFreqMHz());
        pdf2 = masterClock / r2;
        n2 = roundHalfUp(cfg.appxLo2() / pdf2);
        n2Word = Lmx2326.nWord(n2);
        lo2 = (n2Word.bcounter() * Lmx2326.PRESCALER + n2Word.acounter()) * pdf2;

        // 9. PLL1: rcounter1 = round(appxdds1 / PLL1phasefreq) (spurcheck = 0)
        r1 = roundHalfUp(cfg.appxDds1() / cfg.pll1().phaseFreqMHz());
        Lmx2326.rWord(r1); // range check

        // 7. PLL3: reference is DDS3 (TGtop 2) or the master clock (TGtop 1)
        if (cfg.tgTop() > 0) {
            double ref = cfg.tgTop() == 2 ? cfg.appxDds3() : masterClock;
            r3 = rcounter(ref, cfg.pll3().phaseFreqMHz());
        } else {
            r3 = 0;
        }
    }

    private static void requireSupported(MsaConfig.Pll p, String name) {
        if (p.type() != 2326)
            throw new UnsupportedOperationException(name + " is a " + p.type() + "; only the LMX2326 is ported so far");
        if (p.fractional())
            throw new UnsupportedOperationException(name + " fractional-N mode is not supported with the LMX2326");
    }

    /** BASIC int(x) + "if fraction >= .5 then +1" for positive x. */
    static int roundHalfUp(double x) {
        int i = (int) Math.floor(x);
        return x - i >= 0.5 ? i + 1 : i;
    }

    /** [CreateRcounter]. */
    static int rcounter(double reference, double appxPdf) {
        return roundHalfUp(reference / appxPdf);
    }

    // ------------------------------------------------------------------ getters for init and display

    public MsaConfig config() { return cfg; }
    public int path() { return path; }
    public double finalFreq() { return finalFreq; }
    public double lo2() { return lo2; }
    public double pdf2() { return pdf2; }
    public int rcounter1() { return r1; }
    public int rcounter2() { return r2; }
    public int rcounter3() { return r3; }
    public int ncounter2() { return n2; }
    public Lmx2326.NWord pll2N() { return n2Word; }
    /** 1st IF = LO2 - finalfreq. */
    public double firstIf() { return lo2 - finalFreq; }

    // ------------------------------------------------------------------ bands

    /** Band for a true frequency in auto mode (freqBand = 0). */
    public static int autoBand(double trueFreq) {
        if (trueFreq > BAND_END_2G) return 3;
        if (trueFreq > BAND_END_1G) return 2;
        return 1;
    }

    /** Equiv1GFreq: the frequency the 1G hardware is tuned to. */
    public double equiv1G(double trueFreq, int band) {
        return switch (band) {
            case 1 -> trueFreq;
            case 2 -> trueFreq - lo2;
            case 3 -> trueFreq - 2 * firstIf();
            default -> throw new IllegalArgumentException("band 1..3");
        };
    }

    /** ActualSignalFrequency: inverse of {@link #equiv1G}. */
    public double actualSignal(double equivFreq, int band) {
        return switch (band) {
            case 1 -> equivFreq;
            case 2 -> equivFreq + lo2;
            case 3 -> equivFreq + 2 * firstIf();
            default -> throw new IllegalArgumentException("band 1..3");
        };
    }

    // ------------------------------------------------------------------ one step

    /** Everything needed to command and describe one sweep step (the Variables Window values). */
    public record StepPlan(
            double trueFreq, int band, double equivFreq,
            double lo1, double pdf1, int ncounter1, int bcounter1, int acounter1, int pll1Bits,
            long dds1Base, double dds1Output,
            double lo3, double pdf3, int ncounter3, int bcounter3, int acounter3, int pll3Bits,
            long dds3Base, double dds3Output, double generatorOut,
            double lo2) {

        /** "Real Final I.F." in the Variables Window: LO2 - (LO1 - thisfreq). */
        public double realFinalIf() { return lo2 - (lo1 - equivFreq); }

        /** DDS serial word: tuning word in W0..W31, control/phase bits W32..W39 = 0. */
        public long dds1Word() { return dds1Base; }
        public long dds3Word() { return dds3Base; }

        /** The A7 command for this step ([CommandAllSlimsUSB]). */
        public SlimCommands.Op command(int filtbank, int pdm) {
            return SlimCommands.stepCommand(dds1Base, dds3Base, pll1Bits, pll3Bits, filtbank, pdm);
        }
    }

    /**
     * Plans one step.
     *
     * @param trueFreq signal frequency in MHz
     * @param band     1..3, or 0 for automatic band selection
     */
    public StepPlan plan(double trueFreq, int band, Generator gen) {
        return plan(trueFreq, band, gen, trueFreq);
    }

    /**
     * As {@link #plan(double, int, Generator)}, with the frequency the tracking generator follows given
     * separately (reverse tracking: the mirrored step's frequency). It is converted with this step's band,
     * as the BASIC program does (revfreq = Equiv1GFreq(TrueFreq, thisBand)).
     */
    public StepPlan plan(double trueFreq, int band, Generator gen, double tgTrueFreq) {
        int b = band == 0 ? autoBand(trueFreq) : band;
        double thisfreq = equiv1G(trueFreq, b);

        // [CalculateAllStepsForLO1Synth]
        double lo1Appx = thisfreq + lo2 - finalFreq;
        Pll p1 = integerN(lo1Appx, cfg.appxDds1(), r1);
        double dds1Appx = p1.pdf * r1;
        checkDdsFilter("DDS1", dds1Appx, cfg.appxDds1(), cfg.dds1FilterBw());
        long base1 = SlimCommands.ddsTuningWord(dds1Appx, masterClock);
        double dds1 = SlimCommands.ddsOutput(base1, masterClock);
        double pdf1 = dds1 / r1;
        double lo1 = p1.n * pdf1;

        if (cfg.tgTop() == 0) {
            return new StepPlan(trueFreq, b, thisfreq, lo1, pdf1, p1.n, p1.word.bcounter(), p1.word.acounter(), p1.word.bits(),
                    base1, dds1, 0, 0, 0, 0, 0, 0, 0, 0, 0, lo2);
        }

        // [CalculateAllStepsForLO3Synth]
        double lo3Appx;
        if (cfg.tgTop() == 1) {
            lo3Appx = switch (gen) {
                case Tracking t -> lo2 - finalFreq - t.offsetMHz();
                case SignalGen s -> lo2 - finalFreq - s.sgoutMHz() + thisfreq;
            };
        } else {
            lo3Appx = switch (gen) {
                case Tracking t -> b == 3 ? tgTrueFreq + t.offsetMHz() - lo2
                                          : lo2 + (tgTrueFreq == trueFreq ? thisfreq : equiv1G(tgTrueFreq, b)) + t.offsetMHz();
                case SignalGen s -> s.sgoutMHz() <= lo2 ? s.sgoutMHz() + lo2
                                  : s.sgoutMHz() > 2 * lo2 ? s.sgoutMHz() - lo2
                                  : s.sgoutMHz();
            };
        }
        double ref3 = cfg.tgTop() == 2 ? cfg.appxDds3() : masterClock;
        Pll p3 = integerN(lo3Appx, ref3, r3);
        long base3 = 0;
        double dds3 = 0, pdf3;
        if (cfg.tgTop() == 2) {
            double dds3Appx = p3.pdf * r3;
            checkDdsFilter("DDS3", dds3Appx, cfg.appxDds3(), cfg.dds3FilterBw());
            base3 = SlimCommands.ddsTuningWord(dds3Appx, masterClock);
            dds3 = SlimCommands.ddsOutput(base3, masterClock);
            pdf3 = dds3 / r3;
        } else {
            pdf3 = masterClock / r3;
        }
        double lo3 = p3.n * pdf3;
        double out = cfg.tgTop() == 1 ? lo1 - lo3 : lo3 - lo2; // SGTG
        return new StepPlan(trueFreq, b, thisfreq, lo1, pdf1, p1.n, p1.word.bcounter(), p1.word.acounter(), p1.word.bits(),
                base1, dds1, lo3, pdf3, p3.n, p3.word.bcounter(), p3.word.acounter(), p3.word.bits(), base3, dds3, out, lo2);
    }

    private record Pll(int n, double pdf, Lmx2326.NWord word) {}

    /** [CreateIntegerNcounter] + [Create2326N]. */
    private static Pll integerN(double appxVco, double reference, int rcounter) {
        int n = roundHalfUp(appxVco / (reference / rcounter));
        return new Pll(n, appxVco / n, Lmx2326.nWord(n));
    }

    private static void checkDdsFilter(String name, double out, double centre, double bw) {
        if (out - centre > bw / 2) throw new SynthException(name + " output too high for filter (" + fmt(out) + " MHz)");
        if (centre - out > bw / 2) throw new SynthException(name + " output too low for filter (" + fmt(out) + " MHz)");
    }

    private static String fmt(double v) { return String.format("%.6f", v); }
}
