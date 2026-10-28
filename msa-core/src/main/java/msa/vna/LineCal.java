package msa.vna;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import msa.sweep.PhaseDetector;
import msa.sweep.SweepSettings;
import msa.trace.SweepData;

/**
 * A transmission line calibration: the magnitude and phase measured with a through connection, one row per
 * frequency (bandLineCal() / baseLineCal() in spectrumanalyzer.bas). Subtracting it from later measurements
 * gives S21 referenced to the through.
 *
 * <p>The "band" calibration is taken at the steps of the current sweep and is used only while the sweep
 * is the same. A "base" calibration is a band calibration saved to MSA_Info/OperatingCal/BaseLineCal.txt;
 * it is interpolated to whatever sweep is running, as long as path and test fixture match.
 *
 * @param freq      true signal frequency in MHz, ascending
 * @param db        magnitude in dB(m)
 * @param deg       phase in degrees, -180..180 (0 in scalar transmission)
 * @param timeStamp "mm/dd/yy; hh:mm:ss" as the BASIC program writes it
 * @param linear    linear (true) or log sweep
 * @param path      final filter path the calibration was made with
 * @param jigAttach S21 test fixture: "Series" or "Shunt"
 * @param jigR0     fixture reference resistance in ohms
 */
public record LineCal(double[] freq, double[] db, double[] deg, String timeStamp, boolean linear, int path,
                      String jigAttach, double jigR0) {

    public LineCal {
        if (freq.length == 0 || freq.length != db.length || freq.length != deg.length)
            throw new IllegalArgumentException("calibration needs equal, non-empty frequency, dB and degree columns");
        freq = freq.clone();
        db = db.clone();
        deg = deg.clone();
    }

    public int steps() { return freq.length - 1; }
    public double startMHz() { return freq[0]; }
    public double stopMHz() { return freq[freq.length - 1]; }

    /**
     * [BandLineCal]: the calibration from a complete sweep taken without any reference, with the delay of the
     * through connection removed (0.36 degree per ns per MHz) in vector transmission.
     *
     * @param raw            the sweep, with power and phase as measured
     * @param withPhase      vector transmission (phase stored), or scalar (phase 0)
     * @param throughDelayNs delay of the calibration through connection
     */
    public static LineCal fromSweep(SweepData raw, SweepSettings s, boolean withPhase, double throughDelayNs,
                                    String jigAttach, double jigR0) {
        int n = raw.size();
        double[] f = new double[n], m = new double[n], p = new double[n];
        boolean descending = n > 1 && raw.frequency(n - 1) < raw.frequency(0);
        for (int k = 0; k < n; k++) {
            int i = descending ? n - 1 - k : k;
            if (!raw.isMeasured(i)) throw new IllegalArgumentException("the calibration sweep is not complete");
            f[k] = raw.frequency(i);
            m[k] = raw.dBm(i);
            double ph = withPhase ? raw.phase(i) : 0;
            if (Double.isNaN(m[k]) || Double.isNaN(ph))
                throw new IllegalArgumentException("no calibrated reading at " + f[k] + " MHz (path calibration missing?)");
            p[k] = withPhase ? PhaseDetector.normalise(ph + 0.36 * throughDelayNs * f[k]) : 0;
        }
        return new LineCal(f, m, p, timeStamp(LocalDateTime.now()), s.scale() == SweepSettings.Scale.LINEAR,
                s.path(), jigAttach, jigR0);
    }

    /** date$("mm/dd/yy"); "; "; time$(). */
    public static String timeStamp(LocalDateTime t) {
        return t.format(DateTimeFormatter.ofPattern("MM/dd/yy; HH:mm:ss", Locale.ROOT));
    }

    /**
     * BandLineCalIsCurrent: taken at exactly the steps of this sweep. Unlike the BASIC program this also
     * requires the same filter path, since a reference from another path is wrong by that path's response.
     */
    public boolean matches(SweepSettings s) {
        return steps() == s.steps() && linear == (s.scale() == SweepSettings.Scale.LINEAR) && path == s.path()
                && same(startMHz(), s.startMHz()) && same(stopMHz(), s.stopMHz());
    }

    /** BaseLineCalIsCurrent: usable (by interpolation) with this path and test fixture. */
    public boolean usableFor(int path, String jigAttach, double jigR0) {
        return steps() > 0 && this.path == path && this.jigAttach.equalsIgnoreCase(jigAttach) && this.jigR0 == jigR0;
    }

    private static boolean same(double a, double b) { return Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(a)); }

    /** Reference values at the given frequencies: [0] = dB, [1] = degrees. */
    public double[][] at(double[] freqs) {
        double[][] out = new double[2][freqs.length];
        for (int i = 0; i < freqs.length; i++) {
            double[] v = interpolate(freqs[i]);
            out[0][i] = v[0];
            out[1][i] = v[1];
        }
        return out;
    }

    /**
     * intSrcToDest, linear and polar: below the first entry and above the last the end values are used;
     * in between, dB linearly and the angle the short way round (a step of more than 180 degrees is taken
     * as a wrap), result in -180..180.
     */
    public double[] interpolate(double f) {
        int n = freq.length;
        if (f <= freq[0]) return new double[] {db[0], deg[0]};
        if (f > freq[n - 1]) return new double[] {db[n - 1], deg[n - 1]};
        int lo = 0, hi = n - 1;                    // first entry with freq >= f
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (freq[mid] >= f) hi = mid; else lo = mid + 1;
        }
        int c = lo;
        if (freq[c] == f) return new double[] {db[c], deg[c]};
        double span = freq[c] - freq[c - 1];
        if (span == 0) return new double[] {db[c - 1], deg[c - 1]};
        double r = (f - freq[c - 1]) / span;
        double m = db[c - 1] + r * (db[c] - db[c - 1]);
        double dif = deg[c] - deg[c - 1];
        if (Math.abs(dif) > 180) dif += dif > 0 ? -360 : 360;
        return new double[] {m, PhaseDetector.normalise(deg[c - 1] + r * dif)};
    }

    /** CalInfo$: "Performed 09/27/26; 18:56:56; Path 3; 400 linear steps, 650 to 850 MHz." */
    public String info() {
        return "Performed " + timeStamp + "; Path " + path + "; " + steps() + " " + (linear ? "linear" : "log")
                + " steps, " + num(startMHz()) + " to " + num(stopMHz()) + " MHz.";
    }

    // ---------------------------------------------------------------- file

    /** BaseLineCalContextAsTextArray, as MSA_Info/OperatingCal/BaseLineCal.txt holds it. */
    public List<String> lines(String title) {
        List<String> out = new ArrayList<>();
        out.add("! " + title);
        out.add("!");
        out.add("!" + timeStamp);
        out.add((linear ? "!Linear Sweep; " : "!Log Sweep ") + "Path " + path + "; S21Jig=" + jigAttach + "; S21JigR0=" + num(jigR0));
        out.add("# MHz S DB R 50");
        out.add("! MHz  S21_DB  S21_Degrees");
        for (int i = 0; i < freq.length; i++) out.add(num(freq[i]) + " " + num(db[i]) + " " + num(deg[i]));
        return out;
    }

    public void write(Path file, String title) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, lines(title), StandardCharsets.ISO_8859_1);
    }

    /**
     * GetBaseLineCalContextFromFile: the third title line is the time stamp, the fourth holds "Linear"/"Log",
     * "Path N", "S21Jig=" (default Series) and "S21JigR0=" (default 50); data rows are freq, dB, degrees.
     */
    public static LineCal read(Path file) throws IOException {
        List<String> titles = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        int lineNo = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
            lineNo++;
            String t = raw.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("!")) {
                if (rows.isEmpty()) titles.add(t.substring(1).strip());
                continue;
            }
            String[] w = t.split("[\\s,]+");
            if (w.length < 3) throw new IOException(file + " line " + lineNo + ": expected frequency, dB and degrees");
            try {
                rows.add(new double[] {Double.parseDouble(w[0]), Double.parseDouble(w[1]), Double.parseDouble(w[2])});
            } catch (NumberFormatException e) {
                throw new IOException(file + " line " + lineNo + ": " + e.getMessage());
            }
        }
        if (rows.isEmpty()) throw new IOException(file + ": no calibration data");
        String stamp = titles.size() > 2 ? titles.get(2) : "";
        String sweep = titles.size() > 3 ? titles.get(3) : "";
        int path = 1;
        String p = param(sweep, "Path ");
        if (!p.isEmpty()) {
            try {
                path = Integer.parseInt(p.trim());
            } catch (NumberFormatException e) {
                throw new IOException(file + ": bad path \"" + p + "\"");
            }
        }
        String jig = param(sweep, "S21Jig=");
        if (jig.isEmpty()) jig = "Series";
        double r0 = 50;
        try {
            String r = param(sweep, "S21JigR0=");
            if (!r.isEmpty() && Double.parseDouble(r) != 0) r0 = Double.parseDouble(r);
        } catch (NumberFormatException e) {
            // BASIC val() of garbage is 0, which means 50
        }
        double[] f = new double[rows.size()], m = new double[rows.size()], d = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            f[i] = rows.get(i)[0];
            m[i] = rows.get(i)[1];
            d[i] = rows.get(i)[2];
        }
        return new LineCal(f, m, d, stamp, sweep.contains("Linear"), path, jig, r0);
    }

    /** uGetParamText$: text after the keyword up to ";" or the end. */
    private static String param(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return "";
        int start = i + key.length(), end = s.indexOf(';', start);
        return (end < 0 ? s.substring(start) : s.substring(start, end)).strip();
    }

    /** Numbers as Liberty BASIC prints them: no trailing zeros, at most 9 significant digits. */
    static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        String s = new java.math.BigDecimal(v).round(new java.math.MathContext(9)).stripTrailingZeros().toPlainString();
        return s;
    }
}
