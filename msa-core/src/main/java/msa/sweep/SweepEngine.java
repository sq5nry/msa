package msa.sweep;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import msa.config.MsaConfig;
import msa.config.PathCalibration;
import msa.hw.AdcConfig;
import msa.hw.AdcResult;
import msa.hw.MsaException;
import msa.hw.UsbMsa;
import msa.synth.HardwareInit;
import msa.synth.SynthException;
import msa.synth.SynthPlanner;

/**
 * The sweep loop of spectrumanalyzer.bas ([Restart] .. [StartSweep] .. [IncrementOneStep] .. [Halted])
 * as a state machine on its own thread.
 *
 * <p>Threading: every public method only enqueues a command and returns immediately; all hardware access
 * happens on the single "msa-sweep" thread. Listeners are called through the {@link Executor} given when
 * they were added (pass {@code Platform::runLater} or {@code SwingUtilities::invokeLater} from a GUI,
 * {@code Runnable::run} to be called directly on the sweep thread).
 *
 * <p>BASIC behaviour kept:
 * <ul>
 *   <li>Halt stops after a step; Continue resumes with the next step, or starts a new sweep if the halt
 *       was at the end ([StartSweep] "scanResumed").</li>
 *   <li>HaltAtEnd finishes the current sweep, then halts.</li>
 *   <li>Step right / Step left while halted take one step with 100 ms extra settling (glitchhlt = 100);
 *       stepping left turns the sweep direction to R-L, stepping right to L-R ([OneStep], [StepLeft]).</li>
 *   <li>Changing frequencies, steps, band, path or generator restarts the sweep; while halted this is a
 *       "partial restart" that stays halted ([PartialRestart]). Wait, samples, video filter and direction
 *       apply at the next step without a restart.</li>
 *   <li>Restart (button) re-initialises the hardware.</li>
 * </ul>
 * Changed on purpose: a Halt/Restart that arrives during a long settle wait aborts that step instead of
 * waiting it out (the step is redone on Continue); alternating sweeps re-measure the end point; a USB
 * failure puts the engine in ERROR with the device closed, and it reopens the device by itself every
 * second until it is back ({@link #setAutoReconnect}); {@link #reconnect()} forces an attempt.
 */
public final class SweepEngine implements AutoCloseable {

    public enum State { IDLE, RUNNING, HALTED, ERROR, CLOSED }

    /** Callbacks; all default to doing nothing. */
    public interface Listener {
        default void stateChanged(State state, String reason) {}
        /** A new sweep begins; {@code direction} is +1 (L-R) or -1 (R-L). */
        default void sweepStarted(int sweepNumber, SweepSettings settings, int direction) {}
        default void point(int sweepNumber, int index, Sweeper.Point point) {}
        default void sweepCompleted(int sweepNumber, long durationNanos) {}
        default void error(Throwable error) {}
    }

    /** Copy of the current trace; entries not yet measured in this sweep are null. */
    public record Trace(int sweepNumber, SweepSettings settings, Sweeper.Point[] points, int lastIndex) {
        public long measuredCount() { return Arrays.stream(points).filter(Objects::nonNull).count(); }
    }

    /** Extra settling for single steps (BASIC glitchhlt = 100). */
    static final int ONE_STEP_EXTRA_MS = 100;
    /** Pause after hardware initialisation so PLL2 locks. */
    static final int INIT_SETTLE_MS = 100;

    // ---------------------------------------------------------------- commands

    private sealed interface Cmd {}
    private record Start(SweepSettings s) implements Cmd {}
    private record Apply(SweepSettings s) implements Cmd {}
    private record Update(java.util.function.UnaryOperator<SweepSettings> f) implements Cmd {}
    private record Halt() implements Cmd {}
    private record HaltAtEnd() implements Cmd {}
    private record Resume() implements Cmd {}
    private record Step(int dir) implements Cmd {}
    private record Restart() implements Cmd {}
    private record Reconnect() implements Cmd {}
    private record Close() implements Cmd {}
    private record PdmCal(java.util.concurrent.CompletableFuture<PdmCalibration> result) implements Cmd {}
    private record SetInvDeg(double deg) implements Cmd {}

    /**
     * Result of {@link #calibratePdm}: phase readings with the PDM normal and inverted, and the inversion in
     * degrees normalised to 10.7 MHz (the new config.txt "invdeg").
     */
    public record PdmCalibration(long adcNormal, long adcInverted, double phaseNormalDeg, double phaseInvertedDeg,
                                 double invDeg) {}

    private record Registration(Listener listener, Executor executor) {}

    // ---------------------------------------------------------------- state (engine thread only, unless volatile)

    private final MsaConfig config;
    private final IntFunction<PathCalibration> calibrationLoader;
    private final Supplier<UsbMsa> deviceFactory;
    private final LinkedBlockingDeque<Cmd> queue = new LinkedBlockingDeque<>();
    private final List<Registration> listeners = new CopyOnWriteArrayList<>();
    private final Thread thread;
    private final Object stateLock = new Object();

    private volatile State state = State.IDLE;
    /** Retry interval in ERROR; 0 = only on {@link #reconnect()}. */
    private volatile int autoReconnectMs = 1000;
    private volatile SweepSettings settings;
    private Sweeper sweeper;
    private PathCalibration calibration;
    private int loadedCalPath = -1;
    private double[] freqs;
    private Sweeper.Point[] trace = new Sweeper.Point[0];
    /** PDM state per step (phaarray(step,0)); kept from sweep to sweep, cleared when the sweep is configured. */
    private int[] pdmStates = new int[0];
    private Sweeper.PhaseContext phaseContext;
    private volatile double invDegOverride = Double.NaN;
    private volatile int sweepNumber;
    private int sweepDir = 1;
    private int nextIndex;
    private volatile int lastIndex = -1;
    private boolean haltAtEnd;
    private boolean freshSweepPending;
    private boolean directionChanged;   // next step begins a new sweep (after restart / end / partial restart)
    private long sweepStartNanos;

    /**
     * @param config            config.txt
     * @param calibrationLoader path number -> calibration, or null if none (dBm then NaN)
     * @param deviceFactory     opens the MSA; called on the sweep thread at the first start and on reconnect
     */
    public SweepEngine(MsaConfig config, IntFunction<PathCalibration> calibrationLoader, Supplier<UsbMsa> deviceFactory) {
        this.config = Objects.requireNonNull(config);
        this.calibrationLoader = Objects.requireNonNull(calibrationLoader);
        this.deviceFactory = Objects.requireNonNull(deviceFactory);
        this.thread = new Thread(this::loop, "msa-sweep");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    // ---------------------------------------------------------------- public API (any thread)

    public void addListener(Listener l, Executor executor) { listeners.add(new Registration(l, executor)); }
    public void removeListener(Listener l) { listeners.removeIf(r -> r.listener == l); }

    /** Opens the device if needed, initialises the hardware and starts sweeping. */
    public void start(SweepSettings s) { queue.add(new Start(Objects.requireNonNull(s))); }
    /**
     * Replaces the settings: restart if they invalidate the trace, otherwise applied at the next step.
     * To change one field, prefer {@link #update}: {@code apply(settings().withX(..))} can lose a change
     * that is still queued, because {@link #settings()} only reflects commands already processed.
     */
    public void apply(SweepSettings s) { queue.add(new Apply(Objects.requireNonNull(s))); }

    /**
     * Changes the settings relative to their value at the moment the command is processed, so several
     * quick edits (e.g. from a GUI form) never overwrite each other. Ignored before the first {@link #start}.
     */
    public void update(java.util.function.UnaryOperator<SweepSettings> change) {
        queue.add(new Update(Objects.requireNonNull(change)));
    }
    public void halt() { queue.add(new Halt()); }
    public void haltAtEnd() { queue.add(new HaltAtEnd()); }
    /** BASIC "Continue". */
    public void resume() { queue.add(new Resume()); }
    /** BASIC "Step Right" (One Step) while halted. */
    public void stepRight() { queue.add(new Step(1)); }
    /** BASIC "Step Left" while halted. */
    public void stepLeft() { queue.add(new Step(-1)); }
    /** BASIC "Restart" button: re-initialise the hardware and start a new sweep. */
    public void restart() { queue.add(new Restart()); }
    /** After an ERROR: reopen the device, re-initialise and start a new sweep with the last settings. */
    public void reconnect() { queue.add(new Reconnect()); }

    /**
     * Setup &gt; PDM Calibration ([CalPDMinvdeg]): at the frequency of the last step, reads the phase with the
     * PDM normal and then inverted, each after {@link PhaseDetector#CAL_SETTLE_MS} of settling, with one ADC
     * sample, and puts the PDM back. Meant to be used while halted at zero span on a VNA mode. Completes
     * exceptionally if the MSA is not open.
     */
    public java.util.concurrent.CompletableFuture<PdmCalibration> calibratePdm() {
        var f = new java.util.concurrent.CompletableFuture<PdmCalibration>();
        queue.add(new PdmCal(f));
        return f;
    }

    /** Uses this PDM inversion (degrees at 10.7 MHz) instead of config.txt "invdeg" from now on. */
    public void setPdmInversion(double invDeg) { queue.add(new SetInvDeg(invDeg)); }

    /**
     * After a USB failure, retry opening the device every {@code interval} and resume sweeping when it
     * is back (default 1 s). {@link Duration#ZERO} turns this off; {@link #reconnect()} still works.
     */
    public void setAutoReconnect(Duration interval) { autoReconnectMs = (int) Math.max(0, interval.toMillis()); }

    public State state() { return state; }
    /** Settings as of the last processed command (queued changes are not yet included). */
    public SweepSettings settings() { return settings; }
    public int sweepNumber() { return sweepNumber; }

    /** Copy of the trace being built. */
    public Trace snapshot() {
        synchronized (stateLock) {
            return new Trace(sweepNumber, settings, trace.clone(), lastIndex);
        }
    }

    /** Waits until the engine is in {@code wanted}; returns false on timeout. */
    public boolean awaitState(State wanted, Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        synchronized (stateLock) {
            while (state != wanted) {
                long left = end - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(stateLock, left);
            }
            return true;
        }
    }

    /** Stops the thread and closes the device. */
    @Override
    public void close() {
        queue.addFirst(new Close());
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- engine thread

    private void loop() {
        try {
            while (state != State.CLOSED) {
                Cmd c;
                if (state == State.RUNNING) {
                    c = queue.poll();
                    if (c == null) {
                        runOneStep(false);
                        continue;
                    }
                } else if (state == State.ERROR && autoReconnectMs > 0 && settings != null) {
                    c = queue.poll(autoReconnectMs, TimeUnit.MILLISECONDS);
                    if (c == null) {
                        tryAutoReconnect();
                        continue;
                    }
                } else {
                    c = queue.take();
                }
                handle(c);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeDevice();
            if (state != State.CLOSED) setState(State.CLOSED, "closed");
        }
    }

    private void handle(Cmd c) {
        try {
            switch (c) {
                case Start s -> startWith(s.s(), true);
                case Apply a -> applySettings(a.s());
                case Update u -> { if (settings != null) applySettings(u.f().apply(settings)); }
                case Halt h -> { if (state == State.RUNNING) setState(State.HALTED, "halted"); }
                case HaltAtEnd h -> { if (state == State.RUNNING) haltAtEnd = true; }
                case Resume r -> { if (state == State.HALTED) resumeSweep(); }
                case Step s -> { if (state == State.HALTED) singleStep(s.dir()); }
                case Restart r -> { if (settings != null && state != State.ERROR) startWith(settings, true); }
                case Reconnect r -> { if (settings != null) { closeDevice(); startWith(settings, true, "reconnected"); } }
                case Close cl -> setState(State.CLOSED, "closed");   // loop ends; device closed in finally
                case PdmCal p -> pdmCalibration(p.result());
                case SetInvDeg d -> {
                    invDegOverride = d.deg();
                    if (phaseContext != null)
                        phaseContext = new Sweeper.PhaseContext(phaseContext.detector().withInvDeg(d.deg()), phaseContext.finalFreqMHz());
                }
            }
        } catch (SynthException e) {
            fire(l -> l.error(e));
            setState(State.HALTED, e.getMessage());
        } catch (MsaException e) {
            fail(e);
        }
    }

    /** [Restart]: (re)configure, optionally initialise the hardware, begin a new sweep, RUNNING. */
    private void startWith(SweepSettings s, boolean initHardware) { startWith(s, initHardware, "running"); }

    private void startWith(SweepSettings s, boolean initHardware, String reason) {
        configure(s);
        if (sweeper == null || initHardware) {
            if (sweeper == null) openDevice(s);
            int firstBand = s.band() == 0 ? SynthPlanner.autoBand(s.frequency(0)) : s.band();
            sweeper.initialise(switches(s, firstBand), INIT_SETTLE_MS);
        }
        beginNewSweepNext();
        setState(State.RUNNING, reason);
    }

    private void applySettings(SweepSettings s) {
        SweepSettings old = settings;
        if (old == null || sweeper == null) {          // never started: just remember them
            settings = s;
            return;
        }
        if (s.needsRestart(old)) {
            boolean init = s.needsHardwareInit(old);
            configure(s);
            if (init) {
                int firstBand = s.band() == 0 ? SynthPlanner.autoBand(s.frequency(0)) : s.band();
                sweeper.initialise(switches(s, firstBand), INIT_SETTLE_MS);
            } else if (s.video() != old.video()) {
                sweeper.selectSwitches(s.video(), currentBandFor(s));
            }
            beginNewSweepNext();
            if (state == State.RUNNING) setState(State.RUNNING, "restarted with new settings");
            // HALTED stays halted: partial restart, Continue starts the new sweep
        } else {
            settings = s;
            if (s.video() != old.video()) sweeper.selectSwitches(s.video(), currentBandFor(s));
            if (s.direction() != old.direction()) directionChanged = true;   // takes effect at the next sweep
        }
    }

    /** Latch 4 at initialisation: the transmission/reflection switch follows the mode. */
    private static HardwareInit.Switches switches(SweepSettings s, int band) {
        return new HardwareInit.Switches(s.video(), band, false, s.mode() == MsaMode.REFLECTION);
    }

    private int currentBandFor(SweepSettings s) {
        double f = s.frequency(Math.max(0, Math.min(s.steps(), lastIndex < 0 ? 0 : lastIndex)));
        return s.band() == 0 ? SynthPlanner.autoBand(f) : s.band();
    }

    /** New planner, calibration, frequency list and empty trace for these settings. */
    private void configure(SweepSettings s) {
        SynthPlanner planner = new SynthPlanner(config, s.path());   // may throw for unsupported hardware
        if (sweeper != null) sweeper.reconfigure(planner, s.generator());
        if (s.path() != loadedCalPath) {
            calibration = calibrationLoader.apply(s.path());
            loadedCalPath = s.path();
        }
        PhaseDetector det = PhaseDetector.of(config);
        if (!Double.isNaN(invDegOverride)) det = det.withInvDeg(invDegOverride);
        phaseContext = new Sweeper.PhaseContext(det, config.filter(s.path()).freqMHz());
        synchronized (stateLock) {
            settings = s;
            freqs = s.frequencies();
            trace = new Sweeper.Point[s.points()];
            pdmStates = new int[s.points()];          // all normal, as [CalculateAllStepsForLO3Synth] leaves them
            lastIndex = -1;
        }
        sweepDir = s.direction() == SweepSettings.Direction.RIGHT_TO_LEFT ? -1 : 1;
        directionChanged = false;
        haltAtEnd = false;
    }

    private void openDevice(SweepSettings s) {
        UsbMsa msa = deviceFactory.get();
        sweeper = new Sweeper(msa, new SynthPlanner(config, s.path()), s.generator());
    }

    private void closeDevice() {
        if (sweeper != null) {
            try {
                sweeper.device().close();
            } catch (RuntimeException ignored) {
                // the device may already be gone
            }
            sweeper = null;
        }
    }

    private int startIndex() { return sweepDir > 0 ? 0 : settings.steps(); }
    private int endIndex() { return sweepDir > 0 ? settings.steps() : 0; }

    private void beginNewSweepNext() {
        if (directionChanged) {
            directionChanged = false;
            sweepDir = settings.direction() == SweepSettings.Direction.RIGHT_TO_LEFT ? -1 : 1;
        }
        freshSweepPending = true;
        nextIndex = startIndex();
    }

    /** [Continue] / [StartSweep] with scanResumed. */
    private void resumeSweep() {
        if (!freshSweepPending) {
            if (lastIndex == endIndex()) {
                if (settings.direction() == SweepSettings.Direction.ALTERNATE) sweepDir = -sweepDir;
                beginNewSweepNext();
            } else {
                nextIndex = lastIndex + sweepDir;
            }
        }
        haltAtEnd = false;
        setState(State.RUNNING, "running");
    }

    /** [OneStep] / [StepLeft]: one step, stay halted. */
    private void singleStep(int dir) {
        if (dir != sweepDir) sweepDir = dir;                 // [ReverseSweepDirection]
        if (!freshSweepPending) {
            int i = lastIndex + sweepDir;
            if (lastIndex < 0 || i < 0 || i > settings.steps()) beginNewSweepNext();
            else nextIndex = i;
        } else {
            nextIndex = startIndex();
        }
        runOneStep(true);
    }

    /**
     * Commands nextIndex, waits (interruptibly), reads, records, advances.
     * A Halt/Restart/Start/Apply/Reconnect/Close arriving during the wait aborts the step.
     */
    private void runOneStep(boolean single) {
        if (freshSweepPending) {
            freshSweepPending = false;
            sweepNumber++;
            sweepStartNanos = System.nanoTime();
            synchronized (stateLock) {
                Arrays.fill(trace, null);
            }
            int n = sweepNumber;
            SweepSettings s = settings;
            int dir = sweepDir;
            fire(l -> l.sweepStarted(n, s, dir));
        }
        int index = nextIndex;
        try {
            boolean reverseTg = settings.generator() instanceof SynthPlanner.Tracking t && t.reverse();
            boolean vna = settings.mode().readsPhase();
            int glitch = vna ? PhaseDetector.glitchMs(settings.video(), config) : 0;
            Sweeper.Commanded c = sweeper.command(freqs[index], settings.band(),
                    reverseTg ? freqs[freqs.length - 1 - index] : freqs[index], vna ? pdmStates[index] : 0, vna);
            int wait = c.settleMs(settings.waitMs(), glitch) + (single ? ONE_STEP_EXTRA_MS : 0);
            if (!waitInterruptibly(wait)) {
                sweeper.forgetLastStep();
                nextIndex = index;                            // redo this step later
                return;
            }
            AdcConfig adc = AdcConfig.defaults().withAverage(settings.samples());
            AdcResult r = sweeper.readRaw(adc);
            if (vna && phaseContext.detector().inDeadZone(r.adc2())) {
                // [InvertPDmodule]: invert for this step and the rest of the sweep, settle, read again (once)
                int inverted = 1 - pdmStates[index];
                sweeper.setPdm(inverted);
                for (int j = index; j >= 0 && j < pdmStates.length; j += sweepDir) pdmStates[j] = inverted;
                if (!waitInterruptibly(settings.waitMs() + glitch)) {
                    sweeper.forgetLastStep();
                    nextIndex = index;
                    return;
                }
                r = sweeper.readRaw(adc);
            }
            Sweeper.Point p = sweeper.toPoint(c.plan(), r, calibration, vna ? phaseContext : null);
            synchronized (stateLock) {
                trace[index] = p;
                lastIndex = index;
            }
            int n = sweepNumber;
            fire(l -> l.point(n, index, p));
        } catch (SynthException e) {
            fire(l -> l.error(e));
            setState(State.HALTED, e.getMessage());
            return;
        } catch (MsaException e) {
            fail(e);
            return;
        }
        if (single) {                                         // stays HALTED; tell listeners where it landed
            setState(State.HALTED, "one step");
            return;
        }

        if (index == endIndex()) {
            int n = sweepNumber;
            long dt = System.nanoTime() - sweepStartNanos;
            fire(l -> l.sweepCompleted(n, dt));
            if (haltAtEnd) {
                haltAtEnd = false;
                setState(State.HALTED, "halted at end of sweep");
                return;
            }
            if (settings.direction() == SweepSettings.Direction.ALTERNATE) sweepDir = -sweepDir;
            beginNewSweepNext();
        } else {
            nextIndex = index + sweepDir;
        }
    }

    /**
     * Sleeps up to {@code ms}, handling cheap commands that arrive meanwhile.
     *
     * @return false if a command arrived that must abort the current step (it is put back first in the queue)
     */
    private boolean waitInterruptibly(int ms) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        try {
            while (true) {
                long left = end - System.nanoTime();
                Cmd c = left > 0 ? queue.poll(left, TimeUnit.NANOSECONDS) : queue.poll();
                if (c == null) {
                    if (System.nanoTime() >= end) return true;
                    continue;
                }
                if (c instanceof HaltAtEnd) {
                    haltAtEnd = true;
                } else if (c instanceof Apply a && settings != null && !a.s().needsRestart(settings)) {
                    applySettings(a.s());
                } else if (c instanceof Update u && settings != null) {
                    SweepSettings next = u.f().apply(settings);
                    if (next.needsRestart(settings)) {
                        queue.addFirst(new Apply(next));      // computed now, from the current settings
                        return false;
                    }
                    applySettings(next);
                } else if (c instanceof Resume || c instanceof Step) {
                    // meaningless while running: dropped, like the BASIC buttons that are disabled while sweeping
                } else {
                    queue.addFirst(c);
                    return false;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queue.addFirst(new Close());
            return false;
        }
    }

    /** [CalPDMinvdeg] on the engine thread. */
    private void pdmCalibration(java.util.concurrent.CompletableFuture<PdmCalibration> result) {
        if (sweeper == null || phaseContext == null) {
            result.completeExceptionally(new MsaException("the MSA is not open"));
            return;
        }
        int keep = sweeper.pdm();
        AdcConfig one = AdcConfig.defaults();
        try {
            sweeper.setPdm(0);
            sleepUninterrupted(PhaseDetector.CAL_SETTLE_MS);
            long a0 = sweeper.readRaw(one).adc2();
            sweeper.setPdm(1);
            sleepUninterrupted(PhaseDetector.CAL_SETTLE_MS);
            long a1 = sweeper.readRaw(one).adc2();
            sweeper.setPdm(keep);
            PhaseDetector d = phaseContext.detector();
            result.complete(new PdmCalibration(a0, a1, d.rawDegrees(a0), d.rawDegrees(a1),
                    d.inversionFrom(a0, a1, phaseContext.finalFreqMHz())));
        } catch (MsaException e) {
            result.completeExceptionally(e);
            throw e;
        }
    }

    private static void sleepUninterrupted(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MsaException("interrupted", e);
        }
    }

    private void fail(MsaException e) {
        closeDevice();
        fire(l -> l.error(e));
        setState(State.ERROR, e.getMessage()
                + (autoReconnectMs > 0 ? " - waiting for the MSA to come back" : ""));
    }

    /**
     * In ERROR: try to open and re-initialise the device; on success start a new sweep with the last
     * settings. Failures are silent (the device is simply not back yet), so listeners see one ERROR,
     * then RUNNING "reconnected".
     */
    private void tryAutoReconnect() {
        try {
            closeDevice();
            startWith(settings, true, "reconnected");
        } catch (MsaException e) {
            closeDevice();                               // still gone; stay in ERROR and try again later
        } catch (SynthException e) {
            fire(l -> l.error(e));
            setState(State.HALTED, e.getMessage());
        } catch (RuntimeException e) {
            closeDevice();                               // e.g. libusb refusing a half-enumerated device
        }
    }

    private void setState(State s, String reason) {
        synchronized (stateLock) {
            state = s;
            stateLock.notifyAll();
        }
        fire(l -> l.stateChanged(s, reason));
    }

    private void fire(java.util.function.Consumer<Listener> event) {
        for (Registration r : listeners) {
            try {
                r.executor.execute(() -> event.accept(r.listener));
            } catch (RuntimeException ignored) {
                // a broken listener must not stop the sweep
            }
        }
    }
}
