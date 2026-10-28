package msa.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import msa.sweep.SweepEngine;
import msa.sweep.SweepSettings;
import msa.sweep.Sweeper;

/**
 * Collects sweep data from the {@link SweepEngine}: the sweep in progress, the last completed sweep, and
 * a short history of completed sweeps for "stick" display (Y DisplayMode 2/4 in the BASIC program: traces
 * are not erased between sweeps).
 *
 * <p>Register it with the GUI's executor, e.g. {@code engine.addListener(store, Platform::runLater)}, and
 * read it on that same thread; it is not thread-safe.
 */
public final class TraceStore implements SweepEngine.Listener {

    /** Trace display style per axis (Prefs "TraceModes=Y1,Y2": 0 off, 1 NormErase, 2 NormStick, 3 HistoErase, 4 HistoStick). */
    public enum DisplayMode {
        OFF, NORMAL_ERASE, NORMAL_STICK, HISTO_ERASE, HISTO_STICK;

        public boolean stick() { return this == NORMAL_STICK || this == HISTO_STICK; }
        public boolean histogram() { return this == HISTO_ERASE || this == HISTO_STICK; }

        public static DisplayMode ofCode(int c) { return c >= 0 && c < values().length ? values()[c] : NORMAL_ERASE; }
    }

    /** Called after the store changed (on the store's thread). */
    public interface ChangeListener {
        void changed(SweepData current, int index);
    }

    /** Stick history is also limited to this many points in total, so very long sweeps cannot fill the memory. */
    public static final long MAX_HISTORY_POINTS = 2_000_000;

    private final int historySize;
    private final Deque<SweepData> history = new ArrayDeque<>();
    private final List<ChangeListener> listeners = new ArrayList<>();
    private SweepData current;
    private SweepData lastComplete;
    private SweepSettings settings;
    private java.util.function.Function<SweepSettings, SweepData.Correction> corrections = s -> SweepData.Correction.NONE;

    /** @param historySize completed sweeps kept for stick display (1 = only the last one) */
    public TraceStore(int historySize) {
        if (historySize < 1) throw new IllegalArgumentException("historySize >= 1");
        this.historySize = historySize;
    }

    public void addChangeListener(ChangeListener l) { listeners.add(l); }
    public void removeChangeListener(ChangeListener l) { listeners.remove(l); }

    /** Gives each new sweep its calibration reference and plane extension. */
    public void setCorrectionProvider(java.util.function.Function<SweepSettings, SweepData.Correction> provider) {
        corrections = provider;
    }

    /**
     * The calibration or plane extension changed: recalculate the sweeps on the screen (the current one, the
     * last complete one and the stick history; they all share the current frequency axis).
     */
    public void refreshCorrections() {
        if (settings == null) return;
        SweepData.Correction c = corrections.apply(settings);
        for (SweepData d : history) apply(d, c);
        if (lastComplete != null) apply(lastComplete, c);
        if (current != null) apply(current, c);
        fire(-1);
    }

    private static void apply(SweepData d, SweepData.Correction c) {
        try {
            d.setCorrection(c);
        } catch (IllegalArgumentException e) {
            d.setCorrection(SweepData.Correction.NONE);    // another frequency axis
        }
    }

    @Override
    public void sweepStarted(int n, SweepSettings s, int direction) {
        boolean newAxis = settings != null && (!s.range().equals(settings.range())
                || s.points() != settings.points() || s.scale() != settings.scale());
        if (newAxis) {
            history.clear();                                    // a new frequency axis: old sweeps no longer line up
            lastComplete = null;
        }
        settings = s;
        current = new SweepData(n, s.frequencies());
        apply(current, corrections.apply(s));
        fire(-1);
    }

    @Override
    public void point(int n, int index, Sweeper.Point p) {
        if (current == null || current.number() != n) return;
        current.set(index, p);
        fire(index);
    }

    @Override
    public void sweepCompleted(int n, long ns) {
        if (current == null || current.number() != n) return;
        current.markComplete();
        lastComplete = current.copy();
        history.addFirst(lastComplete);
        long points = 0;
        for (SweepData d : history) points += d.size();
        while (history.size() > historySize || (history.size() > 1 && points > MAX_HISTORY_POINTS))
            points -= history.removeLast().size();
        fire(-1);
    }

    /** The sweep being measured (may be partly filled), or null before the first sweep. */
    public SweepData current() { return current; }

    /** The most recent completed sweep, or null. */
    public SweepData lastComplete() { return lastComplete; }

    /** Completed sweeps, newest first, at most historySize. */
    public List<SweepData> history() { return List.copyOf(history); }

    public SweepSettings settings() { return settings; }

    private void fire(int index) {
        for (ChangeListener l : List.copyOf(listeners)) l.changed(current, index);   // listeners may remove themselves
    }
}
