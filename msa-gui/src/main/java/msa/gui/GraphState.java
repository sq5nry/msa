package msa.gui;

import java.util.Optional;

import msa.model.DisplaySettings;
import msa.sweep.FrequencyRange;
import msa.trace.GraphData;
import msa.trace.Markers;
import msa.trace.Reference;
import msa.trace.SweepData;
import msa.trace.TraceStore;

/**
 * What the graph shows besides the measurements, kept apart from JavaFX so the marker-button and
 * reference rules can be tested: markers and the selected one, reference lines, trace widths, the
 * "stick" start, and the mouse query point.
 */
public final class GraphState {

    /** Reference Lines dialog: which axes, what kind, values and the graph option. */
    public record ReferenceSpec(Kind kind, boolean y1, boolean y2, double fixedY1, double fixedY2, Reference.TraceMath math) {
        public enum Kind { NONE, CURRENT_DATA, FIXED }

        public static final ReferenceSpec NONE = new ReferenceSpec(Kind.NONE, false, true, 0, -50, Reference.TraceMath.NONE);

        public boolean active() { return kind != Kind.NONE && (y1 || y2); }
        public boolean doesMath() { return active() && !math.equals(Reference.TraceMath.NONE); }

        /** Short text for the setup info ("Data-Ref" etc.), empty without math. */
        public String mathLabel() {
            if (!doesMath()) return "";
            if (math.equals(Reference.TraceMath.SUBTRACT_REFERENCE)) return "Data-Ref";
            if (math.equals(Reference.TraceMath.ADD_REFERENCE)) return "Data+Ref";
            return "Ref-Data";
        }
    }

    public final Markers markers = new Markers();
    public final TraceStore store;
    private Markers.Id selected;
    private boolean showMarkers = true;
    private int traceWidth1 = 1, traceWidth2 = 1;
    private int stickFrom;
    private double queryIndex = Double.NaN;
    private ReferenceSpec referenceSpec = ReferenceSpec.NONE;
    private Reference ref1, ref2;
    /** Frequencies of the sweep the markers were placed on. */
    private double[] markerAxis;
    /** Markers restored from the last run (by frequency), placed when the first sweep starts. */
    private java.util.Map<Markers.Id, Double> pendingMarkers = java.util.Map.of();

    public GraphState(int stickHistory) {
        store = new TraceStore(stickHistory);
        store.addChangeListener((c, i) -> version++);
    }

    private long version, shownVersion = -1;
    private SweepData shownCache;

    // ---------------------------------------------------------------- data shown

    /** The sweep the graph, markers and tables use: the running sweep over the previous one ("erase" display). */
    public SweepData shown() {
        if (shownVersion == version) return shownCache;   // the merge copies the sweep: once per change, not per call
        SweepData cur = store.current();
        shownCache = cur == null ? null : cur.complete() ? cur : cur.mergedOver(store.lastComplete());
        shownVersion = version;
        return shownCache;
    }

    /**
     * Graph value on an axis at a step, including reference math (y = A*ref + B*y).
     * NaN if the axis is off or the point is not measured.
     */
    public double value(SweepData d, GraphData g, int axis, double index) {
        if (g == GraphData.NONE) return Double.NaN;
        double y = d.value(g, index);
        Reference r = axis == 1 ? ref1 : ref2;
        if (r != null && referenceSpec.doesMath() && (axis == 1 ? referenceSpec.y1() : referenceSpec.y2()))
            y = referenceSpec.math().apply(r.at(d.frequency(index)), y);
        return y;
    }

    /** The reference line value to draw on an axis, or NaN if none is drawn there (none, or math mode). */
    public double referenceLine(int axis, double frequencyMHz) {
        Reference r = axis == 1 ? ref1 : ref2;
        if (r == null || referenceSpec.doesMath() || !(axis == 1 ? referenceSpec.y1() : referenceSpec.y2())) return Double.NaN;
        return r.at(frequencyMHz);
    }

    // ---------------------------------------------------------------- references

    public ReferenceSpec referenceSpec() { return referenceSpec; }

    /**
     * Sets the reference lines. With {@code captureData} the current data becomes the reference ("Close;
     * Update Data"); otherwise a previously captured data reference is kept ("Close; Keep Data").
     */
    public void setReference(ReferenceSpec spec, boolean captureData, DisplaySettings d) {
        SweepData data = shown();
        boolean recapture = captureData || referenceSpec.kind() != ReferenceSpec.Kind.CURRENT_DATA;
        referenceSpec = spec;
        switch (spec.kind()) {
            case NONE -> { ref1 = null; ref2 = null; }
            case FIXED -> { ref1 = new Reference.Fixed(spec.fixedY1()); ref2 = new Reference.Fixed(spec.fixedY2()); }
            case CURRENT_DATA -> {
                if (recapture || ref1 == null || ref2 == null) {
                    if (data == null || data.measuredCount() == 0) {
                        ref1 = ref2 = null;
                    } else {
                        ref1 = GraphData.ofCode(d.y1Data()) == GraphData.NONE ? null : Reference.Stored.of(data, GraphData.ofCode(d.y1Data()));
                        ref2 = GraphData.ofCode(d.y2Data()) == GraphData.NONE ? null : Reference.Stored.of(data, GraphData.ofCode(d.y2Data()));
                    }
                }
            }
        }
    }

    public void clearReference() { setReference(ReferenceSpec.NONE, false, null); }

    // ---------------------------------------------------------------- markers

    /**
     * Called when a sweep starts. Markers are kept by step index, so when the frequency axis changes
     * (start, stop, step count, linear/log; e.g. a new filter path with automatic steps) each marker is
     * moved to the whole step nearest to the frequency it was on. Markers that fall outside the new sweep
     * are removed, as is the Halt marker.
     *
     * @return true if markers were moved or removed
     */
    public boolean followAxis(SweepData now) {
        if (now == null) return false;
        double[] f = now.frequencies();
        double[] old = markerAxis;
        markerAxis = f;
        if (!pendingMarkers.isEmpty()) {
            pendingMarkers.forEach((id, freq) -> {
                if (id.automatic()) markers.place(id, 0);
                else if (freq >= Math.min(f[0], f[f.length - 1]) && freq <= Math.max(f[0], f[f.length - 1]))
                    markers.place(id, Math.round(now.indexOf(freq)));
            });
            pendingMarkers = java.util.Map.of();
            return true;
        }
        if (old == null || java.util.Arrays.equals(old, f)) return false;
        markers.remove(Markers.Id.HALT);
        double lo = Math.min(f[0], f[f.length - 1]), hi = Math.max(f[0], f[f.length - 1]);
        double tol = f.length > 1 ? Math.abs(f[1] - f[0]) / 2 + 1e-9 : 1e-9;
        markers.remap(i -> {
            double freq = frequencyAt(old, i);
            if (freq < lo - tol || freq > hi + tol) return Double.NaN;
            return Math.round(now.indexOf(freq));
        });
        return true;
    }

    /** Restores markers saved by frequency; they are placed when the first sweep starts. */
    public void restoreMarkers(java.util.Map<Markers.Id, Double> byFrequency) {
        pendingMarkers = java.util.Map.copyOf(byFrequency);
    }

    /** Placed markers by frequency (NaN for P+/P-, which are found again), for saving between runs. */
    public java.util.Map<Markers.Id, Double> markerFrequencies() {
        if (!pendingMarkers.isEmpty()) return pendingMarkers;    // not placed yet: keep them as they were
        java.util.Map<Markers.Id, Double> out = new java.util.EnumMap<>(Markers.Id.class);
        markers.positions().forEach((id, i) -> {
            if (id == Markers.Id.HALT) return;
            if (id.automatic()) out.put(id, Double.NaN);
            else if (markerAxis != null && !Double.isNaN(i)) out.put(id, frequencyAt(markerAxis, i));
        });
        return out;
    }

    private static double frequencyAt(double[] f, double index) {
        int i = (int) Math.floor(Math.max(0, Math.min(f.length - 1, index)));
        if (i >= f.length - 1) return f[f.length - 1];
        return f[i] + (index - i) * (f[i + 1] - f[i]);
    }

    public Optional<Markers.Id> selected() { return Optional.ofNullable(selected); }
    public void select(Markers.Id id) { selected = id; }

    public boolean showMarkers() { return showMarkers; }
    public void setShowMarkers(boolean b) { showMarkers = b; }

    /** [MarkerClick]: the selected marker, or L if none is selected, goes to the nearest whole step. */
    public Markers.Id placeAtClick(double index, int points) {
        Markers.Id id = selected != null ? selected : Markers.Id.L;
        markers.place(id, clamp(Math.round(index), points));
        return id;
    }

    /** Key L, R, 1..6 at the mouse query point ([mAddMarkerFromKeyboard]); false for other keys. */
    public boolean placeFromKey(String key, int points) {
        if (Double.isNaN(queryIndex) || key == null || key.length() != 1) return false;
        Markers.Id id = switch (key.toUpperCase(java.util.Locale.ROOT)) {
            case "L" -> Markers.Id.L;
            case "R" -> Markers.Id.R;
            case "1" -> Markers.Id.M1;
            case "2" -> Markers.Id.M2;
            case "3" -> Markers.Id.M3;
            case "4" -> Markers.Id.M4;
            case "5" -> Markers.Id.M5;
            case "6" -> Markers.Id.M6;
            default -> null;
        };
        if (id == null) return false;
        markers.place(id, clamp(Math.round(queryIndex), points));
        return true;
    }

    /** "-" / "+" ([IncDecPoint]): one whole step down or up; P+/P- cannot be moved. */
    public void stepSelected(int dir, int points) {
        if (selected == null || selected.automatic()) return;
        Optional<Double> i = markers.index(selected);
        if (i.isEmpty()) return;
        double p = i.get();
        double n = dir < 0 ? Math.ceil(p) - 1 : Math.floor(p) + 1;
        markers.place(selected, clamp(n, points));
    }

    /** "Enter" ([mEnterMarker]): the selected marker to the whole step nearest the frequency. */
    public Optional<Double> enterFrequency(double frequencyMHz, SweepData d) {
        if (selected == null || selected.automatic() || d == null) return Optional.empty();
        double i = clamp(Math.round(d.indexOf(frequencyMHz)), d.size());
        markers.place(selected, i);
        return Optional.of(d.frequency(i));
    }

    /** Frequency of the selected marker on this sweep, if placed. */
    public Optional<Double> selectedFrequency(SweepData d) {
        if (selected == null || d == null) return Optional.empty();
        return markers.index(selected).map(i -> d.frequency(Math.min(i, d.size() - 1.0)));
    }

    /** "Delete": removes the selected marker. */
    public void deleteSelected() { if (selected != null) markers.remove(selected); }

    /** [Expand]: new start/stop from L and R (start/stop of the sweep where one is missing); empty if neither. */
    public Optional<FrequencyRange> expandLR(SweepData d) {
        if (d == null || (!markers.has(Markers.Id.L) && !markers.has(Markers.Id.R))) return Optional.empty();
        double lo = markers.index(Markers.Id.L).map(d::frequency).orElse(d.frequency(0));
        double hi = markers.index(Markers.Id.R).map(d::frequency).orElse(d.frequency(d.size() - 1));
        return Optional.of(new FrequencyRange(Math.min(lo, hi), Math.max(lo, hi)));
    }

    /** [mMarkToCenter]: selected marker frequency rounded to 1 Hz as the new centre, same span. */
    public Optional<FrequencyRange> markToCenter(SweepData d, FrequencyRange current) {
        return selectedFrequency(d).map(f -> current.withCenter(Math.floor(1e6 * f + 0.5) / 1e6));
    }

    private static double clamp(double i, int points) { return Math.max(0, Math.min(points - 1, i)); }

    // ---------------------------------------------------------------- misc view state

    public int traceWidth(int axis) { return axis == 1 ? traceWidth1 : traceWidth2; }
    public void setTraceWidth(int axis, int w) {
        if (axis == 1) traceWidth1 = w; else traceWidth2 = w;
    }

    /** Stick traces older than this sweep number are not drawn ("Redraw" clears them). */
    public int stickFrom() { return stickFrom; }
    public void clearStick() { stickFrom = store.current() == null ? 0 : store.current().number(); }

    public double queryIndex() { return queryIndex; }
    public void setQueryIndex(double i) { queryIndex = i; }
}
