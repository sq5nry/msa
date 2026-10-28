package msa.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A recording read back: for each resolution-filter path, the ADC readings measured at each frequency,
 * gathered over all recorded sweeps. {@link #sample} answers "what would the MSA read at f" for any f:
 * exact frequencies use the recorded readings, frequencies in between interpolate the two neighbours,
 * frequencies outside the recorded span return the recording's noise floor.
 */
public final class Recording {

    /** Readings at one frequency; each entry is {adc1, adc2, pdm}. */
    private record Samples(List<long[]> values) {
        long[] get(int k) { return values.get(Math.floorMod(k, values.size())); }

        double meanAdc1() { return values.stream().mapToLong(v -> v[0]).average().orElse(0); }
    }

    private final Map<Integer, TreeMap<Double, Samples>> byPath = new HashMap<>();
    private final Map<Integer, Samples> floorByPath = new HashMap<>();
    private final Path source;
    private int points;

    private Recording(Path source) { this.source = source; }

    public static Recording read(Path file) throws IOException {
        Recording r = new Recording(file);
        int lineNo = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("sweep,")) continue;
            String[] w = t.split(",", -1);
            if (w.length < 7) throw new IOException(file + " line " + lineNo + ": expected 8 columns");
            try {
                double f = Double.parseDouble(w[2]);
                int path = Integer.parseInt(w[4]);
                long a1 = Long.parseLong(w[5]), a2 = Long.parseLong(w[6]);
                long pdm = w.length > 8 && !w[8].isBlank() ? Long.parseLong(w[8].trim()) : 0;   // v2 recordings
                r.byPath.computeIfAbsent(path, k -> new TreeMap<>())
                        .computeIfAbsent(f, k -> new Samples(new ArrayList<>())).values().add(new long[] {a1, a2, pdm});
                r.points++;
            } catch (NumberFormatException e) {
                throw new IOException(file + " line " + lineNo + ": " + e.getMessage());
            }
        }
        if (r.points == 0) throw new IOException(file + ": no points");
        for (Map.Entry<Integer, TreeMap<Double, Samples>> e : r.byPath.entrySet()) {
            Samples floor = e.getValue().values().stream()
                    .min((a, b) -> Double.compare(a.meanAdc1(), b.meanAdc1())).orElseThrow();
            r.floorByPath.put(e.getKey(), floor);
        }
        return r;
    }

    public Path source() { return source; }
    public int points() { return points; }
    public java.util.Set<Integer> paths() { return byPath.keySet(); }

    /** Lowest and highest recorded frequency for a path (any path if it was not recorded). */
    public double[] span(int path) {
        TreeMap<Double, Samples> m = table(path);
        return new double[] {m.firstKey(), m.lastKey()};
    }

    /**
     * Reading number {@code k} at frequency f on a path (k cycles through the recorded sweeps);
     * {@code average} consecutive readings are averaged, like the FX2's sample averaging.
     *
     * @return {adc1, adc2}
     */
    public long[] sample(int path, double f, int k, int average) {
        return sample(path, f, k, average, null);
    }

    /**
     * As {@link #sample(int, double, int, int)}, with the phase readings converted to the PDM state the
     * replaying analyser has commanded: a reading recorded with the other state is moved by the inversion.
     *
     * @param pdm null to return the phase readings as recorded
     */
    public long[] sample(int path, double f, int k, int average, PdmShift pdm) {
        int n = Math.max(1, average);
        double s1 = 0, s2 = 0;
        for (int j = 0; j < n; j++) {
            double[] v = single(path, f, k * n + j, pdm);
            s1 += v[0];
            s2 += v[1];
        }
        return new long[] {Math.round(s1 / n), Math.round(s2 / n)};
    }

    /**
     * The phase detector of the replaying analyser: the state it is in, and how far an inversion moves the
     * reading (180 degrees plus the deviation at this final IF, in ADC counts).
     */
    public record PdmShift(int state, double counts, long maxPdmOut) {
        double toState(long adc2, long recordedState) {
            if (recordedState == state) return adc2;
            double v = adc2 + (state == 1 ? counts : -counts);
            return Math.floorMod(Math.round(v), maxPdmOut);
        }
    }

    private double[] single(int path, double f, int k, PdmShift pdm) {
        TreeMap<Double, Samples> m = table(path);
        int p = byPath.containsKey(path) ? path : byPath.keySet().iterator().next();
        if (f < m.firstKey() - 1e-6 || f > m.lastKey() + 1e-6) return adjust(floorByPath.get(p).get(k), pdm);
        Map.Entry<Double, Samples> lo = m.floorEntry(f), hi = m.ceilingEntry(f);
        if (lo == null) return adjust(hi.getValue().get(k), pdm);
        if (hi == null || Math.abs(lo.getKey() - f) < 1e-6) return adjust(lo.getValue().get(k), pdm);
        if (Math.abs(hi.getKey() - f) < 1e-6) return adjust(hi.getValue().get(k), pdm);
        double t = (f - lo.getKey()) / (hi.getKey() - lo.getKey());
        double[] a = adjust(lo.getValue().get(k), pdm), b = adjust(hi.getValue().get(k), pdm);
        double d2 = b[1] - a[1];
        long max = pdm == null ? 65536 : pdm.maxPdmOut();
        if (Math.abs(d2) > max / 2.0) d2 += d2 > 0 ? -max : max;          // phase readings wrap
        return new double[] {a[0] + t * (b[0] - a[0]), Math.floorMod(Math.round(a[1] + t * d2), max)};
    }

    private static double[] adjust(long[] v, PdmShift pdm) {
        return new double[] {v[0], pdm == null ? v[1] : pdm.toState(v[1], v.length > 2 ? v[2] : 0)};
    }

    private TreeMap<Double, Samples> table(int path) {
        TreeMap<Double, Samples> m = byPath.get(path);
        return m != null ? m : byPath.values().iterator().next();
    }
}
