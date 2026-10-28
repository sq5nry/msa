package msa.gui;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;

/**
 * Hands work from the sweep thread to the JavaFX thread in batches. {@code Platform::runLater} per measured
 * point floods the UI queue when a sweep runs fast (thousands of points per second); this queues the tasks
 * and schedules one runLater that runs everything queued so far, in order. Mouse and keyboard events get
 * their turn between batches, so the window stays responsive however fast the points come in.
 */
final class FxBatcher implements Executor {

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();

    @Override
    public void execute(Runnable r) {
        queue.add(r);
        if (scheduled.compareAndSet(false, true)) Platform.runLater(this::drain);
    }

    private void drain() {
        scheduled.set(false);
        // only what is queued now; later arrivals schedule the next batch
        for (int n = queue.size(); n > 0; n--) {
            Runnable r = queue.poll();
            if (r == null) break;
            r.run();
        }
        if (!queue.isEmpty() && scheduled.compareAndSet(false, true)) Platform.runLater(this::drain);
    }
}
