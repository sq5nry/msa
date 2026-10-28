package msa.trace;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Graph markers of the BASIC program (markerIDs$: 1..6, L, R, P+, P-, plus Halt), positioned by
 * (possibly fractional) step index so they follow the trace when it is redrawn.
 * P+ and P- are placed automatically on the highest and lowest point of the primary axis, between L
 * and R if the mode is {@link LrMode#PEAKS_BOUNDED}; in the two "relative" modes L and R are placed
 * automatically 3 dB below P+ or 3 dB above P- (Marker Options dialog, mUpdateMarkerLocations, gFindDBOffset).
 * Not thread-safe; use it on the GUI thread together with {@link TraceStore}.
 */
public final class Markers {

    public enum Id {
        M1("1"), M2("2"), M3("3"), M4("4"), M5("5"), M6("6"), L("L"), R("R"), PEAK_POS("P+"), PEAK_NEG("P-"), HALT("Halt");

        public final String label;

        Id(String label) { this.label = label; }

        public boolean automatic() { return this == PEAK_POS || this == PEAK_NEG; }
    }

    /** Marker Options "L,R Markers" radio buttons (doPeaksBounded, doLRRelativeTo$). */
    public enum LrMode {
        /** L,R Independent. */
        INDEPENDENT,
        /** P+, P- bounded by L,R. */
        PEAKS_BOUNDED,
        /** L,R -3 dB re P+. */
        MINUS_3DB_FROM_PEAK,
        /** L,R 3 dB re P-. */
        PLUS_3DB_FROM_DIP;

        public boolean lrAutomatic() { return this == MINUS_3DB_FROM_PEAK || this == PLUS_3DB_FROM_DIP; }
    }

    /** Marker readout (the marker info table): step index, frequency, value in the primary axis unit. */
    public record Readout(Id id, double index, double frequencyMHz, double value) {}

    private final Map<Id, Double> pos = new EnumMap<>(Id.class);
    private LrMode lrMode = LrMode.INDEPENDENT;

    /** Places a marker at a step index (fractional allowed). P+/P- are only switched on; their place is computed. */
    public void place(Id id, double index) {
        pos.put(id, id.automatic() ? Double.NaN : index);
    }

    /** Places a marker at a frequency on the given sweep ("Enter" in the marker box). */
    public void placeAtFrequency(Id id, double frequencyMHz, SweepData d) {
        place(id, d.indexOf(frequencyMHz));
    }

    public void remove(Id id) { pos.remove(id); }
    public void clear() { pos.clear(); }

    /**
     * Moves every placed marker to a new step index ({@code f} maps old index to new; NaN removes the
     * marker). P+ and P- are left alone: they are located again on the next {@link #update}.
     */
    public void remap(java.util.function.DoubleUnaryOperator f) {
        for (Id id : List.copyOf(pos.keySet())) {
            double i = pos.get(id);
            if (Double.isNaN(i)) continue;
            double n = f.applyAsDouble(i);
            if (Double.isNaN(n)) pos.remove(id); else pos.put(id, n);
        }
    }
    public boolean has(Id id) { return pos.containsKey(id); }
    public Optional<Double> index(Id id) { return Optional.ofNullable(pos.get(id)).filter(v -> !v.isNaN()); }

    public void setBoundedByLR(boolean b) { lrMode = b ? LrMode.PEAKS_BOUNDED : LrMode.INDEPENDENT; }
    public void setLrMode(LrMode m) { lrMode = java.util.Objects.requireNonNull(m); }
    public LrMode lrMode() { return lrMode; }

    /** All placed markers and their indices (NaN for P+/P- not located yet), in Id order. */
    public Map<Id, Double> positions() { return java.util.Collections.unmodifiableMap(new EnumMap<>(pos)); }

    /** Repositions P+ / P- and, in the relative modes, L and R on this sweep ([mUpdateMarkerLocations]). */
    public void update(SweepData d, GraphData primary) {
        updatePeaks(d, primary);
        if (lrMode.lrAutomatic()) {
            Id ref = lrMode == LrMode.MINUS_3DB_FROM_PEAK ? Id.PEAK_POS : Id.PEAK_NEG;
            Optional<Double> p = index(ref);
            if (p.isEmpty()) {
                pos.remove(Id.L);
                pos.remove(Id.R);
                return;
            }
            double[] lr = findDbOffset(d, primary, p.get(), lrMode == LrMode.MINUS_3DB_FROM_PEAK ? -3 : 3);
            if (Double.isNaN(lr[0])) pos.remove(Id.L); else pos.put(Id.L, lr[0]);
            if (Double.isNaN(lr[1])) pos.remove(Id.R); else pos.put(Id.R, lr[1]);
        }
    }

    private void updatePeaks(SweepData d, GraphData primary) {
        if (!has(Id.PEAK_POS) && !has(Id.PEAK_NEG)) return;
        int from = 0, to = d.size() - 1;
        if (lrMode == LrMode.PEAKS_BOUNDED) {
            if (has(Id.L) && index(Id.L).isPresent()) from = (int) Math.round(index(Id.L).get());
            if (has(Id.R) && index(Id.R).isPresent()) to = (int) Math.round(index(Id.R).get());
            if (from > to) { int t = from; from = to; to = t; }
        }
        Peaks pk = findPeaks(d, primary, Math.max(0, from), Math.min(d.size() - 1, to));
        if (pk == null) return;
        if (has(Id.PEAK_POS)) pos.put(Id.PEAK_POS, (double) pk.maxIndex);
        if (has(Id.PEAK_NEG)) pos.put(Id.PEAK_NEG, (double) pk.minIndex);
    }

    /** Readouts for all placed markers on this sweep, in Id order. Unmeasured points read NaN. */
    public List<Readout> readouts(SweepData d, GraphData primary) {
        List<Readout> out = new ArrayList<>();
        for (Map.Entry<Id, Double> e : pos.entrySet()) {
            double i = e.getValue();
            if (Double.isNaN(i)) continue;
            i = Math.max(0, Math.min(d.size() - 1, i));
            out.add(new Readout(e.getKey(), i, d.frequency(i), d.value(primary, i)));
        }
        return out;
    }

    /**
     * gFindDBOffset: the (interpolated) indices left and right of {@code index} where the trace first reaches
     * value(index) + dbOff (at or below it for a negative offset, at or above for a positive one).
     * NaN where the trace never gets there. Unmeasured points are skipped.
     */
    public static double[] findDbOffset(SweepData d, GraphData g, double index, double dbOff) {
        double pVal = d.value(g, index);
        double[] out = {Double.NaN, Double.NaN};
        if (Double.isNaN(pVal)) return out;
        double targ = pVal + dbOff;
        int ip = (int) Math.floor(index);
        double prevVal = pVal, prevNum = index;
        for (int i = ip; i >= 0; i--) {
            double v = d.value(g, i);
            if (Double.isNaN(v)) continue;
            if (dbOff < 0 ? v <= targ : v >= targ) { out[0] = interp(targ, i, v, prevNum, prevVal); break; }
            prevVal = v;
            prevNum = i;
        }
        prevVal = pVal;
        prevNum = index;
        for (int i = ip == index ? ip : ip + 1; i < d.size(); i++) {
            double v = d.value(g, i);
            if (Double.isNaN(v)) continue;
            if (dbOff < 0 ? v <= targ : v >= targ) { out[1] = interp(targ, i, v, prevNum, prevVal); break; }
            prevVal = v;
            prevNum = i;
        }
        return out;
    }

    /**
     * gPeakInterp: the index between (n, v) and (n2, v2) where the value would be targ. For equal values the
     * BASIC code returns the value instead of the index (a bug); this returns n, as its comment intends.
     */
    static double interp(double targ, double n, double v, double n2, double v2) {
        if (v == v2) return n;
        return n + (targ - v) / (v2 - v) * (n2 - n);
    }

    /** Highest and lowest point and their indices. */
    public record Peaks(int maxIndex, double max, int minIndex, double min) {}

    /**
     * gFindPeaks: scans [from, to]; on a flat top (equal values right after the new maximum) the marker
     * goes to the middle of the plateau, int((start + end) / 2). Unmeasured (NaN) points are skipped.
     */
    public static Peaks findPeaks(SweepData d, GraphData g, int from, int to) {
        double maxY = Double.NaN, minY = Double.NaN;
        int maxStart = -1, maxEnd = -1, minStart = -1, minEnd = -1;
        boolean maxEnded = true, minEnded = true;
        for (int i = from; i <= to; i++) {
            double y = d.value(g, i);
            if (Double.isNaN(y)) {
                maxEnded = true;
                minEnded = true;
                continue;
            }
            if (Double.isNaN(maxY)) {
                maxY = minY = y;
                maxStart = maxEnd = minStart = minEnd = i;
                continue;
            }
            if (y > maxY) { maxY = y; maxStart = i; maxEnded = false; }
            if (!maxEnded && y >= maxY) maxEnd = i; else maxEnded = true;
            if (y < minY) { minY = y; minStart = i; minEnd = i; minEnded = false; }
            if (!minEnded && y <= minY) minEnd = i; else minEnded = true;
        }
        if (maxStart < 0) return null;
        return new Peaks((maxEnd + maxStart) / 2, maxY, (minEnd + minStart) / 2, minY);
    }
}
