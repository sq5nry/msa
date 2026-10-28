package msa.sweep;

/**
 * Sweep span in MHz. Start/stop is the stored form (Prefs.txt "MinMaxXAxis"); centre/span is a view of it,
 * as in the Sweep Parameters Window's Cent/Span and Start/Stop boxes.
 */
public record FrequencyRange(double startMHz, double stopMHz) {

    public FrequencyRange {
        if (!Double.isFinite(startMHz) || !Double.isFinite(stopMHz)) throw new IllegalArgumentException("frequencies must be finite");
        if (stopMHz < startMHz) throw new IllegalArgumentException("stop must be >= start (use the sweep direction for R-L)");
    }

    public static FrequencyRange ofCenterSpan(double centerMHz, double spanMHz) {
        if (spanMHz < 0) throw new IllegalArgumentException("span must be >= 0");
        return new FrequencyRange(centerMHz - spanMHz / 2, centerMHz + spanMHz / 2);
    }

    public double centerMHz() { return (startMHz + stopMHz) / 2; }
    public double spanMHz() { return stopMHz - startMHz; }

    public FrequencyRange withCenter(double c) { return ofCenterSpan(c, spanMHz()); }
    public FrequencyRange withSpan(double s) { return ofCenterSpan(centerMHz(), s); }
}
