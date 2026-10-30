package msa.autotest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import msa.config.MsaConfig;
import msa.sweep.SweepEngine;
import msa.sweep.SweepSettings;
import msa.sweep.SweepTime;
import msa.sweep.Sweeper;
import msa.trace.SweepData;

/**
 * Runs the tests on the {@link SweepEngine} the GUI uses: each sweep is applied and restarted, and the
 * first complete sweep with exactly those settings is returned. The engine keeps sweeping afterwards, so
 * the main window shows what the test measures. The caller restores the user's settings when done.
 */
public final class EngineContext implements AutotestContext, SweepEngine.Listener {

    private final SweepEngine engine;
    private final MsaConfig cfg;
    private final IntPredicate pathCal;
    private final ReferenceStore reference;
    private final Consumer<String> progress;
    private volatile boolean cancelled;
    private volatile boolean inputSwitch;

    private final Object lock = new Object();
    private SweepSettings target;
    private int collecting = -1;
    private SweepData data;
    private CompletableFuture<Sweep> pending;
    private long startedAt;

    public EngineContext(SweepEngine engine, MsaConfig cfg, IntPredicate pathCal, ReferenceStore reference,
                         Consumer<String> progress) {
        this.engine = engine;
        this.cfg = cfg;
        this.pathCal = pathCal;
        this.reference = reference;
        this.progress = progress;
        engine.addListener(this, Runnable::run);
    }

    /** Stop listening to the engine. */
    public void detach() { engine.removeListener(this); }

    /** Stops the run: the current wait ends with {@link Cancelled}. */
    public void cancel() {
        cancelled = true;
        synchronized (lock) {
            if (pending != null) pending.completeExceptionally(new Cancelled());
        }
    }

    @Override public MsaConfig config() { return cfg; }
    @Override public boolean inputSwitch() { return inputSwitch; }

    /** The MSA has an SA/VNA input switch with T and R inputs. */
    public void setInputSwitch(boolean on) { inputSwitch = on; }
    @Override public boolean hasPathCalibration(int path) { return pathCal.test(path); }
    @Override public ReferenceStore reference() { return reference; }
    @Override public void progress(String text) { progress.accept(text); }
    @Override public void checkCancelled() { if (cancelled) throw new Cancelled(); }

    @Override
    public Sweep sweep(SweepSettings s) {
        checkCancelled();
        CompletableFuture<Sweep> f = new CompletableFuture<>();
        synchronized (lock) {
            target = s;
            collecting = -1;
            data = null;
            pending = f;
        }
        engine.apply(s);
        engine.restart();
        // generous: the estimated sweep time three times over, plus hardware initialisation
        long timeoutMs = (long) (3000 * SweepTime.seconds(s, 5)) + 15000;
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Cancelled();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Cancelled c) throw c;
            throw new IllegalStateException(e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("no complete sweep within " + timeoutMs / 1000 + " s");
        } finally {
            synchronized (lock) {
                pending = null;
                target = null;
            }
        }
    }

    @Override
    public SweepEngine.PdmCalibration calibratePdm() {
        checkCancelled();
        try {
            return engine.calibratePdm().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Cancelled();
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("PDM measurement failed: " + (e.getCause() != null ? e.getCause().getMessage() : e));
        }
    }

    // ---------------------------------------------------------------- engine events (engine thread)

    @Override
    public void sweepStarted(int n, SweepSettings s, int direction) {
        synchronized (lock) {
            if (pending == null || !s.equals(target)) return;
            collecting = n;                                  // always the latest sweep with these settings
            data = new SweepData(n, s.frequencies());
            startedAt = System.nanoTime();
        }
    }

    @Override
    public void point(int n, int index, Sweeper.Point p) {
        synchronized (lock) {
            if (n == collecting && data != null) data.set(index, p);
        }
    }

    @Override
    public void sweepCompleted(int n, long ns) {
        synchronized (lock) {
            if (n != collecting || pending == null || data == null) return;
            pending.complete(new Sweep(data, target, (System.nanoTime() - startedAt) / 1e9));
            collecting = -1;
        }
    }

    @Override
    public void stateChanged(SweepEngine.State s, String reason) {
        if (s != SweepEngine.State.ERROR) return;
        synchronized (lock) {
            if (pending != null) pending.completeExceptionally(new IllegalStateException("MSA: " + reason));
        }
    }

    @Override
    public void error(Throwable t) {
        synchronized (lock) {
            if (pending != null) pending.completeExceptionally(new IllegalStateException(t.getMessage(), t));
        }
    }
}
