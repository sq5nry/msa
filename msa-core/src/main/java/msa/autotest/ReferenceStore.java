package msa.autotest;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Reference results of this particular MSA: a healthy unit's levels differ from the design values by as much
 * as 15 dB but are repeatable, so after a run the user can keep the results as the reference, and later runs
 * are compared with it. Stored in MSA_Info/Autotest: {@value #VALUES} (one key per measured value) and one
 * CSV per curve (trace-KEY.csv: x, y).
 */
public final class ReferenceStore {

    public static final String VALUES = "reference.properties";

    private final Path folder;
    private final Map<String, Double> values = new TreeMap<>();
    private final Map<String, double[][]> traces = new HashMap<>();
    private String savedAt = "";

    /** @param folder MSA_Info/Autotest, or null for a store that is never written (tests, no MSA_Info) */
    public ReferenceStore(Path folder) { this.folder = folder; }

    public static ReferenceStore empty() { return new ReferenceStore(null); }

    /** Reads the folder if it exists; unreadable entries are skipped. */
    public static ReferenceStore load(Path folder) throws IOException {
        ReferenceStore r = new ReferenceStore(folder);
        Path f = folder.resolve(VALUES);
        if (!Files.exists(f)) return r;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        r.savedAt = p.getProperty("#savedAt", "");
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith("#")) continue;
            try {
                r.values.put(k, Double.parseDouble(p.getProperty(k)));
            } catch (NumberFormatException ignored) {
                // a damaged line: that value has no reference
            }
        }
        try (var files = Files.list(folder)) {
            for (Path t : files.toList()) {
                String n = t.getFileName().toString();
                if (!n.startsWith("trace-") || !n.endsWith(".csv")) continue;
                try {
                    r.traces.put(n.substring(6, n.length() - 4), readTrace(t));
                } catch (IOException | RuntimeException ignored) {
                    // skip a damaged curve
                }
            }
        }
        return r;
    }

    public boolean isEmpty() { return values.isEmpty() && traces.isEmpty(); }
    public String savedAt() { return savedAt; }
    public Path folder() { return folder; }

    /** NaN if there is no reference for the key. */
    public double value(String key) { return values.getOrDefault(key, Double.NaN); }

    /** {x[], y[]} or null. */
    public double[][] trace(String key) { return traces.get(key); }

    /**
     * Makes these results the reference: every measured value and every curve that has a key replaces
     * the stored one (tests not run keep their old reference), then writes the folder.
     *
     * @param curves key -> {x[], y[]}
     */
    public void update(List<AutotestModel.Result> results, Map<String, double[][]> curves) throws IOException {
        for (AutotestModel.Result r : results)
            for (AutotestModel.Measurement m : r.measurements())
                if (m.key() != null && !Double.isNaN(m.value())) values.put(m.key(), m.value());
        traces.putAll(curves);
        savedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT));
        if (folder == null) return;
        Files.createDirectories(folder);
        Properties p = new Properties();
        values.forEach((k, v) -> p.setProperty(k, Double.toString(v)));
        p.setProperty("#savedAt", savedAt);
        try (Writer w = Files.newBufferedWriter(folder.resolve(VALUES), StandardCharsets.UTF_8)) {
            p.store(w, "MSA autotest reference values of this MSA (Autotest window: Save as Reference)");
        }
        for (Map.Entry<String, double[][]> e : curves.entrySet()) {
            List<String> lines = new ArrayList<>();
            lines.add("# " + e.getKey() + ", saved " + savedAt);
            double[] x = e.getValue()[0], y = e.getValue()[1];
            for (int i = 0; i < x.length; i++) lines.add(x[i] + "," + y[i]);
            Files.write(folder.resolve("trace-" + e.getKey() + ".csv"), lines, StandardCharsets.UTF_8);
        }
    }

    private static double[][] readTrace(Path f) throws IOException {
        List<double[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] w = t.split(",");
            rows.add(new double[] {Double.parseDouble(w[0]), Double.parseDouble(w[1])});
        }
        double[][] out = new double[2][rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            out[0][i] = rows.get(i)[0];
            out[1][i] = rows.get(i)[1];
        }
        return out;
    }
}
