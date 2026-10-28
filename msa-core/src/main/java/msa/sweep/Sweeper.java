package msa.sweep;

import java.util.function.Consumer;

import msa.config.PathCalibration;
import msa.hw.AdcConfig;
import msa.hw.AdcResult;
import msa.hw.MsaException;
import msa.hw.SlimCommands;
import msa.hw.SlimCommands.VideoFilter;
import msa.hw.UsbMsa;
import msa.synth.HardwareInit;
import msa.synth.SynthException;
import msa.synth.SynthPlanner;
import msa.synth.SynthPlanner.Generator;
import msa.synth.SynthPlanner.StepPlan;

/**
 * One step of the BASIC sweep loop, without timing policy or GUI:
 * {@link #command} = [CommandCurrentStep] (band relay if the band changes, A7 if any module changed,
 * [DetermineModule]); the caller waits; {@link #read} = [ReadStep] + calibration.
 * {@link #measure} and {@link #sweep} combine them with a plain sleep for command-line use;
 * {@link SweepEngine} uses the parts with an interruptible wait.
 */
public final class Sweeper {

    /**
     * One measured step. {@code dBm} and {@code freqCorrDb} are NaN when no path calibration is loaded.
     * dBm = magnitude-table value + freqCorrDb.
     *
     * @param pdm      PDM state the reading was taken with (0 normal, 1 inverted; always 0 outside the VNA modes)
     * @param phaseDeg phase from [ConvertPhadata] before any operating calibration, -180..180; NaN when the
     *                 mode does not read phase
     */
    public record Point(StepPlan plan, AdcResult adc, double dBm, double freqCorrDb, int pdm, double phaseDeg) {
        /** A magnitude-only point (spectrum analyser, scalar transmission). */
        public Point(StepPlan plan, AdcResult adc, double dBm, double freqCorrDb) {
            this(plan, adc, dBm, freqCorrDb, 0, Double.NaN);
        }
    }

    /** Result of commanding a step: the plan and whether any module or the PDM had to be commanded. */
    public record Commanded(StepPlan plan, boolean modulesChanged, boolean bandChanged, boolean pdmChanged) {
        public Commanded(StepPlan plan, boolean modulesChanged, boolean bandChanged) {
            this(plan, modulesChanged, bandChanged, false);
        }

        /** BASIC [WaitStatement]: wate + 1 ms "glitch" when a PLL or DDS was commanded. */
        public int settleMs(int waitMs) { return waitMs + (modulesChanged ? 1 : 0); }

        /**
         * With the PDM: wate + the largest glitch, 1 ms for a synthesiser or {@code pdmGlitchMs}
         * (glitchpdm, ten phase video time constants) when the PDM state changed.
         */
        public int settleMs(int waitMs, int pdmGlitchMs) {
            return waitMs + Math.max(modulesChanged ? 1 : 0, pdmChanged ? pdmGlitchMs : 0);
        }
    }

    /** What the VNA modes need to turn the phase ADC into degrees ([ConvertPhadata]). */
    public record PhaseContext(PhaseDetector detector, double finalFreqMHz) {}

    private final UsbMsa msa;
    private SynthPlanner planner;
    private Generator generator;
    private int filtbank;
    private VideoFilter video = VideoFilter.WIDE;
    private int currentBand = -1;
    private StepPlan last;
    /** PDM state last commanded (lastpdmstate); the init sequence sets it normal. */
    private int lastPdm;
    /** Transmission/reflection and DUT forward/reverse switches (latch 4, switchTR / switchFR). */
    private boolean reflection, reverseDut;

    public Sweeper(UsbMsa msa, SynthPlanner planner, Generator generator) {
        this.msa = msa;
        this.planner = planner;
        this.generator = generator;
        this.filtbank = SlimCommands.filterBank(planner.path());
    }

    public UsbMsa device() { return msa; }
    public SynthPlanner planner() { return planner; }

    /** Changes planner (path) and generator without touching the hardware; call {@link #initialise} after a path change. */
    public void reconfigure(SynthPlanner planner, Generator generator) {
        this.planner = planner;
        this.generator = generator;
        this.filtbank = SlimCommands.filterBank(planner.path());
        this.last = null;
    }

    /** [InitializeHardware], then a pause so PLL2 can lock. */
    public void initialise(HardwareInit.Switches sw, int settleMs) {
        SlimCommands.run(msa, HardwareInit.sequence(planner, sw));
        video = sw.video();
        currentBand = sw.band();
        reflection = sw.reflection();
        reverseDut = sw.reverse();
        last = null;
        lastPdm = 0;
        sleepMs(settleMs);
    }

    /** Latch 4 only (video filter and band), as sub SelectVideoFilter does. */
    public void selectSwitches(VideoFilter v, int band) {
        SlimCommands.run(msa, SlimCommands.commandSwitches(SlimCommands.switchData(v, band, reverseDut, reflection)));
        video = v;
        currentBand = band;
    }

    /** PDM state last commanded. */
    public int pdm() { return lastPdm; }

    /** [CommandPDMonly] ([CommandPDMSlimUSB]): sets the PDM state without commanding a step. */
    public void setPdm(int state) {
        SlimCommands.run(msa, SlimCommands.commandPdm(state));
        lastPdm = state;
    }

    /** Forgets what was last commanded, so the next step sends a full A7 (after a re-init or error). */
    public void forgetLastStep() { last = null; }

    /**
     * Commands one step ([CommandCurrentStep]).
     *
     * @param band 1..3, or 0 for auto
     */
    public Commanded command(double freqMHz, int band) {
        return command(freqMHz, band, freqMHz);
    }

    /** As {@link #command(double, int)}; {@code tgFreqMHz} is what a reverse tracking generator follows. */
    public Commanded command(double freqMHz, int band, double tgFreqMHz) {
        return command(freqMHz, band, tgFreqMHz, 0, false);
    }

    /**
     * As {@link #command(double, int, double)} with the PDM state for this step. With {@code steerPdm} (VNA
     * modes, [DetermineModule] [PDM]) a PDM state different from the last one commanded is sent with the
     * step command even if no synthesiser changed, and is reported so the caller adds glitchpdm.
     */
    public Commanded command(double freqMHz, int band, double tgFreqMHz, int pdm, boolean steerPdm) {
        StepPlan p;
        try {
            p = planner.plan(freqMHz, band, generator, tgFreqMHz);
        } catch (SynthException e) {
            throw new SynthException(String.format("%.6f MHz: %s", freqMHz, e.getMessage()));
        }
        boolean bandChanged = false;
        if (p.band() != currentBand) {
            SlimCommands.run(msa, SlimCommands.commandSwitches(SlimCommands.switchData(video, p.band(), reverseDut, reflection)));
            currentBand = p.band();
            bandChanged = true;
        }
        boolean changed = last == null || p.dds1Base() != last.dds1Base() || p.ncounter1() != last.ncounter1()
                || p.ncounter3() != last.ncounter3() || p.dds3Base() != last.dds3Base();
        boolean pdmChanged = steerPdm && pdm != lastPdm;
        int pdmSent = steerPdm ? pdm : lastPdm;
        if (changed || pdmChanged) {
            msa.write(p.command(filtbank, pdmSent).command());   // the A7 command carries the PDM state
            lastPdm = pdmSent;
        }
        last = p;
        return new Commanded(p, changed, bandChanged, pdmChanged);
    }

    /** Reads the ADCs for a commanded step and applies the path calibration ([ReadStep], [ConvertMagPhaseData]). */
    public Point read(StepPlan p, AdcConfig adc, PathCalibration cal) {
        return toPoint(p, msa.readAdcs(adc), cal, null);
    }

    /** Reads the ADCs only (adc1 = magnitude, adc2 = phase with config.txt adconv 16 or 17). */
    public AdcResult readRaw(AdcConfig adc) { return msa.readAdcs(adc); }

    /**
     * [ConvertMagPhaseData] and, with a phase context, [ConvertPhadata] for a reading taken with the PDM in
     * its last commanded state. Without a path calibration dBm is NaN and the phase is not corrected for level.
     */
    public Point toPoint(StepPlan p, AdcResult r, PathCalibration cal, PhaseContext phase) {
        double dBm = Double.NaN, corr = Double.NaN, difPhase = 0;
        if (cal != null) {
            corr = cal.freqCorrection(p.trueFreq());
            dBm = round5(cal.magDb(r.adc1()) + corr);
            difPhase = cal.phaseCorrection(r.adc1());
        }
        if (phase == null) return new Point(p, r, dBm, corr, lastPdm, Double.NaN);
        double deg = phase.detector().degrees(r.adc2(), lastPdm, phase.finalFreqMHz(), difPhase, p.band());
        return new Point(p, r, dBm, corr, lastPdm, deg);
    }

    /** [ConvertMagPhaseData]: magpower rounded to five decimals, half away from zero. */
    static double round5(double v) {
        return Math.signum(v) * Math.floor(Math.abs(v) * 100000 + 0.5) / 100000;
    }

    /** command + plain sleep + read. */
    public Point measure(double freqMHz, int band, int waitMs, AdcConfig adc, PathCalibration cal) {
        Commanded c = command(freqMHz, band);
        sleepMs(c.settleMs(waitMs));
        return read(c.plan(), adc, cal);
    }

    /** Linear sweep of {@code steps + 1} points from start to stop (BASIC "steps" counts intervals). */
    public void sweep(double startMHz, double stopMHz, int steps, int band, int waitMs, AdcConfig adc,
                      PathCalibration cal, Consumer<Point> sink) {
        if (steps < 1) throw new IllegalArgumentException("steps must be >= 1");
        for (int i = 0; i <= steps; i++) {
            double f = startMHz + (stopMHz - startMHz) * i / steps;
            sink.accept(measure(f, band, waitMs, adc, cal));
        }
    }

    private static void sleepMs(int ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MsaException("interrupted", e);
        }
    }
}
