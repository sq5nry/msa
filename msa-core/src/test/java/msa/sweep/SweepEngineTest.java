package msa.sweep;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import msa.config.MsaConfig;
import msa.hw.MsaException;
import msa.hw.MsaTransport;
import msa.hw.UsbMsa;
import msa.hw.sim.SimulatedTransport;

class SweepEngineTest {

    private static final Duration T = Duration.ofSeconds(10);

    /** Simulated board that can be told to fail, as if the USB cable were pulled. */
    static final class FlakyTransport implements MsaTransport {
        final SimulatedTransport sim = new SimulatedTransport();
        final AtomicBoolean broken;

        FlakyTransport(AtomicBoolean broken) {
            this.broken = broken;
            sim.setTrailingStatusFrames(1);
        }

        public void write(byte[] d, int t) { check(); sim.write(d, t); }
        public int read(byte[] b, int t) { check(); return sim.read(b, t); }
        public int bcdDevice() { return sim.bcdDevice(); }
        public String description() { return "flaky sim"; }
        public void close() { sim.close(); }

        private void check() { if (broken.get()) throw new MsaException("LIBUSB_ERROR_NO_DEVICE"); }
    }

    /** Records events in arrival order. */
    static final class Recorder implements SweepEngine.Listener {
        record P(int sweep, int index, double freq) {}
        final List<P> points = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> started = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> completed = Collections.synchronizedList(new ArrayList<>());
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        public void point(int sweep, int index, Sweeper.Point p) { points.add(new P(sweep, index, p.plan().trueFreq())); }
        public void sweepStarted(int n, SweepSettings s, int dir) { started.add(n); }
        public void sweepCompleted(int n, long ns) { completed.add(n); }
        public void error(Throwable t) { errors.add(t); }

        List<P> of(int sweep) {
            synchronized (points) {
                return points.stream().filter(p -> p.sweep == sweep).toList();
            }
        }

        P last() {
            synchronized (points) {
                return points.get(points.size() - 1);
            }
        }

        void waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
            long end = System.nanoTime() + T.toNanos();
            while (!cond.getAsBoolean()) {
                if (System.nanoTime() > end) fail("timed out");
                Thread.sleep(5);
            }
        }
    }

    private final AtomicBoolean broken = new AtomicBoolean();
    private final AtomicInteger opened = new AtomicInteger();
    private SweepEngine engine;
    private final Recorder rec = new Recorder();

    private SweepEngine engine() throws Exception {
        Path cfg = Path.of(SweepEngineTest.class.getResource("/msa-info/config.txt").toURI());
        engine = new SweepEngine(MsaConfig.read(cfg), path -> null, () -> {
            opened.incrementAndGet();
            return new UsbMsa(new FlakyTransport(broken), 200, 200, 0);
        });
        engine.addListener(rec, Runnable::run);
        engine.setAutoReconnect(Duration.ofMillis(200));
        return engine;
    }

    private static SweepSettings settings(int steps) {
        return SweepSettings.defaults().withRange(100, 200).withSteps(steps).withWait(0);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
    }

    @Test
    void sweepsRepeatInOrder() throws Exception {
        SweepEngine e = engine();
        e.start(settings(10));
        rec.waitFor(() -> rec.completed.size() >= 3);
        e.halt();
        assertTrue(e.awaitState(SweepEngine.State.HALTED, T));
        for (int s = 1; s <= 3; s++) {
            List<Integer> idx = rec.of(s).stream().map(Recorder.P::index).toList();
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), idx, "sweep " + s);
        }
        assertEquals(100.0, rec.of(1).get(0).freq(), 1e-9);
        assertEquals(200.0, rec.of(1).get(10).freq(), 1e-9);
        assertEquals(1, opened.get());
    }

    @Test
    void haltThenContinueResumesAtNextStep() throws Exception {
        SweepEngine e = engine();
        e.start(settings(400));
        rec.waitFor(() -> rec.points.size() >= 20);
        e.halt();
        assertTrue(e.awaitState(SweepEngine.State.HALTED, T));
        Recorder.P atHalt = rec.last();
        assertEquals(atHalt.index(), e.snapshot().lastIndex());
        int before = rec.points.size();
        e.resume();
        rec.waitFor(() -> rec.points.size() > before);
        Recorder.P next = rec.points.get(before);
        assertEquals(atHalt.sweep(), next.sweep());
        assertEquals(atHalt.index() + 1, next.index());
    }

    @Test
    void haltAtEndStopsOnLastPoint() throws Exception {
        SweepEngine e = engine();
        e.start(settings(30));
        rec.waitFor(() -> rec.points.size() >= 5);
        e.haltAtEnd();
        assertTrue(e.awaitState(SweepEngine.State.HALTED, T));
        assertEquals(30, e.snapshot().lastIndex());
        // Continue after a halt at the end starts a fresh sweep at step 0
        int before = rec.points.size();
        int sweep = rec.last().sweep();
        e.resume();
        rec.waitFor(() -> rec.points.size() > before);
        assertEquals(new Recorder.P(sweep + 1, 0, 100.0), rec.points.get(before));
    }

    @Test
    void stepRightAndLeftWhileHalted() throws Exception {
        SweepEngine e = engine();
        e.start(settings(400));
        rec.waitFor(() -> rec.points.size() >= 10);
        e.halt();
        assertTrue(e.awaitState(SweepEngine.State.HALTED, T));
        int k = rec.last().index();
        int n0 = rec.points.size();
        e.stepRight();
        rec.waitFor(() -> rec.points.size() == n0 + 1);
        assertEquals(k + 1, rec.last().index());
        e.stepLeft();
        rec.waitFor(() -> rec.points.size() == n0 + 2);
        assertEquals(k, rec.last().index());
        Thread.sleep(150);
        assertEquals(n0 + 2, rec.points.size(), "single steps must not keep running");
        assertEquals(SweepEngine.State.HALTED, e.state());
        // Step Left turned the direction: Continue now runs downwards
        e.resume();
        rec.waitFor(() -> rec.points.size() >= n0 + 4);
        assertEquals(k - 1, rec.points.get(n0 + 2).index());
        assertEquals(k - 2, rec.points.get(n0 + 3).index());
    }

    @Test
    void rightToLeftAndAlternate() throws Exception {
        SweepEngine e = engine();
        e.start(settings(5).withDirection(SweepSettings.Direction.RIGHT_TO_LEFT));
        rec.waitFor(() -> rec.completed.size() >= 1);
        assertEquals(List.of(5, 4, 3, 2, 1, 0), rec.of(1).stream().map(Recorder.P::index).toList());

        e.halt();
        assertTrue(e.awaitState(SweepEngine.State.HALTED, T));
        int startedBefore = rec.started.size();
        e.start(settings(5).withDirection(SweepSettings.Direction.ALTERNATE));
        rec.waitFor(() -> rec.started.size() > startedBefore);
        int first = rec.started.get(startedBefore);
        rec.waitFor(() -> rec.completed.contains(first + 1));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), rec.of(first).stream().map(Recorder.P::index).toList());
        assertEquals(List.of(5, 4, 3, 2, 1, 0), rec.of(first + 1).stream().map(Recorder.P::index).toList());
    }

    @Test
    void newRangeRestartsButNewWaitDoesNot() throws Exception {
        SweepEngine e = engine();
        e.start(settings(300));
        rec.waitFor(() -> rec.points.size() >= 10);
        int sweepsBefore = rec.started.size();
        e.apply(e.settings().withWait(1).withSamples(4));
        Thread.sleep(100);
        assertEquals(sweepsBefore, rec.started.size(), "wait/samples apply without restart");

        e.apply(e.settings().withRange(500, 600));
        rec.waitFor(() -> rec.started.size() > sweepsBefore);
        int s = rec.started.get(rec.started.size() - 1);
        rec.waitFor(() -> !rec.of(s).isEmpty());
        assertEquals(new Recorder.P(s, 0, 500.0), rec.of(s).get(0));
        assertEquals(4, e.settings().samples());
    }

    @Test
    void haltDuringLongWaitIsImmediateAndStepIsRedone() throws Exception {
        SweepEngine e = engine();
        e.start(settings(10).withWait(3000));
        Thread.sleep(1500);                         // init (~1.1 s) done, now waiting 3 s on step 0
        long t0 = System.nanoTime();
        e.halt();
        assertTrue(e.awaitState(SweepEngine.State.HALTED, T));
        assertTrue(System.nanoTime() - t0 < 300_000_000L, "halt must not wait for the settle time");
        assertTrue(rec.points.isEmpty());
        e.apply(e.settings().withWait(0));
        e.resume();
        rec.waitFor(() -> !rec.points.isEmpty());
        assertEquals(0, rec.points.get(0).index(), "aborted step is measured on Continue");
    }

    @Test
    void usbFailureGoesToErrorAndRecoversByItselfWhenTheDeviceIsBack() throws Exception {
        SweepEngine e = engine();
        e.start(settings(50));
        rec.waitFor(() -> rec.points.size() >= 5);
        broken.set(true);                           // cable pulled
        assertTrue(e.awaitState(SweepEngine.State.ERROR, T));
        assertEquals(1, rec.errors.size());
        Thread.sleep(700);                          // several silent retries while the device is gone
        assertEquals(SweepEngine.State.ERROR, e.state());
        assertEquals(1, rec.errors.size(), "retries must not flood listeners with errors");
        assertTrue(opened.get() >= 3, "retries open the device again");

        int n = rec.points.size();
        broken.set(false);                          // cable back
        assertTrue(e.awaitState(SweepEngine.State.RUNNING, T));
        rec.waitFor(() -> rec.points.size() > n + 5);
    }

    @Test
    void manualReconnectWhenAutoReconnectIsOff() throws Exception {
        SweepEngine e = engine();
        e.setAutoReconnect(Duration.ZERO);
        e.start(settings(50));
        rec.waitFor(() -> rec.points.size() >= 5);
        broken.set(true);
        assertTrue(e.awaitState(SweepEngine.State.ERROR, T));
        broken.set(false);
        Thread.sleep(500);
        assertEquals(SweepEngine.State.ERROR, e.state(), "no automatic retry");
        e.reconnect();
        assertTrue(e.awaitState(SweepEngine.State.RUNNING, T));
        assertEquals(2, opened.get());
    }

    @Test
    void settingsChangeWhileHaltedIsAPartialRestart() throws Exception {
        SweepEngine e = engine();
        e.start(settings(400));
        rec.waitFor(() -> rec.points.size() >= 10);
        e.halt();
        assertTrue(e.awaitState(SweepEngine.State.HALTED, T));
        int n = rec.points.size();
        e.apply(e.settings().withRange(300, 400));
        Thread.sleep(150);
        assertEquals(SweepEngine.State.HALTED, e.state());
        assertEquals(n, rec.points.size());
        e.resume();
        rec.waitFor(() -> rec.points.size() > n);
        Recorder.P p = rec.points.get(n);
        assertEquals(0, p.index());
        assertEquals(300.0, p.freq(), 1e-9);
    }

    @Test
    void quickSuccessiveUpdatesAreAllKept() throws Exception {
        SweepEngine e = engine();
        e.start(settings(300));
        rec.waitFor(() -> rec.points.size() >= 5);
        e.update(x -> x.withRange(500, 600));
        e.update(x -> x.withSteps(20));
        e.update(x -> x.withSamples(8));
        e.update(x -> x.withWait(2));
        rec.waitFor(() -> e.settings().steps() == 20 && e.settings().samples() == 8 && e.settings().waitMs() == 2);
        assertEquals(new FrequencyRange(500, 600), e.settings().range());
    }

    @Test
    void logSweepSpacing() {
        SweepSettings s = SweepSettings.defaults().withRange(1, 1000).withSteps(3).withScale(SweepSettings.Scale.LOG);
        assertArrayEquals(new double[] {1, 10, 100, 1000}, s.frequencies(), 1e-9);
    }
}
