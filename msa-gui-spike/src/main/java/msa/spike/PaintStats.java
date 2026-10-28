package msa.spike;

import java.util.Locale;

/** Numbers to compare the two toolkits; reset every second. Thread-safe. */
final class PaintStats {
    private long frames, paintNanosSum, paintNanosMax, points, sweeps, lagNanosSum, lagNanosMax, lagCount;
    private long windowStart = System.nanoTime();
    private String last = "measuring...";
    private long lastSweepNanos;

    synchronized void frame(long paintNanos) {
        frames++;
        paintNanosSum += paintNanos;
        paintNanosMax = Math.max(paintNanosMax, paintNanos);
        roll();
    }

    synchronized void point() { points++; }

    synchronized void sweep(long ns) {
        sweeps++;
        lastSweepNanos = ns;
    }

    synchronized void deliveryLag(long ns) {
        lagNanosSum += ns;
        lagNanosMax = Math.max(lagNanosMax, ns);
        lagCount++;
    }

    /** One-line summary of the last full second. */
    synchronized String summary() {
        roll();
        return last;
    }

    private void roll() {
        long now = System.nanoTime();
        double secs = (now - windowStart) / 1e9;
        if (secs < 1) return;
        last = String.format(Locale.ROOT,
                "%.0f fps | paint avg %.2f ms max %.2f ms | %.0f points/s | sweep %.2f s | UI delivery lag avg %.2f ms max %.1f ms",
                frames / secs, frames == 0 ? 0 : paintNanosSum / 1e6 / frames, paintNanosMax / 1e6, points / secs,
                lastSweepNanos / 1e9, lagCount == 0 ? 0 : lagNanosSum / 1e6 / lagCount, lagNanosMax / 1e6);
        frames = paintNanosSum = paintNanosMax = points = lagNanosSum = lagNanosMax = lagCount = 0;
        windowStart = now;
    }
}
