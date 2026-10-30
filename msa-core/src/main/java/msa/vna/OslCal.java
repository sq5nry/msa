package msa.vna;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import msa.sweep.PhaseDetector;
import msa.sweep.SweepSettings;

/**
 * A reflection (OSL) calibration: per frequency the reference reading that is subtracted from every
 * measurement (the "line cal" of reflection, OSLBandRef) and the error coefficients a, b, c with which the
 * actual reflection S is found from the referenced measurement M (ProcessOSLCal, CalcOSLCoeff,
 * ConvertRawDataToReflection):
 * <pre>
 *   S = (M - b) / (a - c M)
 * </pre>
 * Made with all three standards (full OSL), or with one (reference calibration: the open or the short on a
 * bridge, the short in a series fixture, the open in a shunt fixture), the missing standards taken as ideal.
 *
 * @param freq      true signal frequency in MHz, ascending
 * @param refDb     reference reading, dB(m), subtracted from later readings
 * @param refDeg    reference phase, degrees
 * @param refType   "Open", "Short" or "Load": which standard is the reference
 * @param fixture   bridge or series/shunt fixture used for reflection
 * @param r0        bridge / fixture reference resistance (S11BridgeR0 = S21JigR0)
 * @param shuntDelayNs connection delay of a shunt fixture
 */
public record OslCal(double[] freq, double[] refDb, double[] refDeg, Cx[] a, Cx[] b, Cx[] c, String refType,
                     String timeStamp, boolean linear, int path, Fixture fixture, double r0, double shuntDelayNs) {

    /** S11JigType$ "Reflect" (bridge), or "Trans" with S21JigAttach$ "Series" / "Shunt". */
    public enum Fixture {
        BRIDGE("Reflection Bridge"), SERIES("Series Fixture"), SHUNT("Shunt Fixture");

        public final String title;

        Fixture(String title) { this.title = title; }
    }

    /** A measured standard: raw reading (no reference applied), dB(m) and degrees per point. */
    public record Reading(double[] db, double[] deg) {}

    public OslCal {
        int n = freq.length;
        if (n == 0 || refDb.length != n || refDeg.length != n || a.length != n || b.length != n || c.length != n)
            throw new IllegalArgumentException("calibration columns differ in length");
    }

    public int steps() { return freq.length - 1; }
    public double startMHz() { return freq[0]; }
    public double stopMHz() { return freq[freq.length - 1]; }

    /**
     * ProcessOSLCal for the readings of a complete sweep at {@code freq}. Either all three standards were
     * measured (full OSL), or exactly one (reference calibration): open or short on a bridge, short in a
     * series fixture, open in a shunt fixture.
     *
     * @throws IllegalArgumentException when the combination is incomplete, a reference standard is unsuitable,
     *                                  or the coefficients cannot be found
     */
    public static OslCal compute(double[] freq, Reading open, Reading shortR, Reading load, CalStandards stds,
                                 Fixture fixture, double r0, double shuntDelayNs, boolean linear, int path) {
        int n = freq.length;
        int done = (open != null ? 1 : 0) + (shortR != null ? 1 : 0) + (load != null ? 1 : 0);
        if (done == 0) throw new IllegalArgumentException("no calibration performed");
        if (done == 2 || (done == 1 && load != null)) throw new IllegalArgumentException("incomplete calibration performed");
        if (r0 <= 0) throw new IllegalArgumentException("invalid R0");

        // CalcOSLStandards: actual reflection of each standard re R0
        RlcSpec so = stds.openSpec(), ss = stds.shortSpecParsed(), sl = stds.loadSpec();
        Cx[] stdO = new Cx[n], stdS = new Cx[n], stdL = new Cx[n];
        for (int i = 0; i < n; i++) {
            stdO[i] = so.s11(freq[i], r0);
            stdS[i] = ss.s11(freq[i], r0);
            stdL[i] = sl.s11(freq[i], r0);
        }
        double[][] mO = new double[2][n], mS = new double[2][n], mL = new double[2][n];   // dB, deg after reference
        double[] refDb = new double[n], refDeg = new double[n];
        String refType;
        if (done == 1) {
            refType = open != null ? "Open" : "Short";
            if (fixture == Fixture.SERIES && open != null)
                throw new IllegalArgumentException("a series fixture is referenced to the Short");
            if (fixture == Fixture.SHUNT && shortR != null)
                throw new IllegalArgumentException("a shunt fixture is referenced to the Open");
            Reading used = open != null ? open : shortR;
            for (int i = 0; i < n; i++) {
                Cx std = open != null ? stdO[i] : stdS[i];
                double sDb = std.db(), sDeg = std.deg();
                switch (fixture) {
                    case BRIDGE -> {
                        if (sDb < -40) throw new IllegalArgumentException("reference calibration standard is too close to "
                                + fmt(r0) + " ohms");
                        if (open != null) {
                            set(mO, i, 0, 0);
                            stdS[i] = new Cx(-1, 0);
                            set(mS, i, -sDb, norm(-180 - sDeg));
                        } else {
                            set(mS, i, 0, 0);
                            stdO[i] = Cx.ONE;
                            set(mO, i, -sDb, norm(-sDeg));
                        }
                        stdL[i] = Cx.ZERO;
                        set(mL, i, -99 - sDb, 0);
                    }
                    case SHUNT -> {
                        double phaseDelay = freq[i] * 0.36 * shuntDelayNs;          // one way, degrees
                        Cx zOpen = Impedance.phaseShift(50, phaseDelay, Impedance.fromRefco(r0, std));
                        Cx s21Open = Impedance.shuntS21(Impedance.toRefco(r0, zOpen));
                        if (sDb > -0.25 && (sDeg < -165 || sDeg > 165))
                            throw new IllegalArgumentException("reference calibration standard is too close to a Short");
                        Cx s21Short = Impedance.shuntS21(Impedance.toRefco(r0, Impedance.phaseShift(50, phaseDelay, Cx.ZERO)));
                        Cx s21Load = Impedance.shuntS21(Impedance.toRefco(r0, Impedance.phaseShift(50, phaseDelay, new Cx(r0, 0))));
                        set(mO, i, 0, 0);
                        stdS[i] = new Cx(-1, 0);
                        set(mS, i, s21Short.db() - s21Open.db(), norm(s21Short.deg() - s21Open.deg()));
                        stdL[i] = Cx.ZERO;
                        set(mL, i, s21Load.db() - s21Open.db(), norm(s21Load.deg() - s21Open.deg()));
                    }
                    case SERIES -> {
                        Cx s21 = Impedance.seriesS21(std);
                        if (sDb > -0.25 && sDeg < 5 && sDeg > -5)
                            throw new IllegalArgumentException("reference calibration standard is too close to an Open");
                        set(mS, i, 0, 0);
                        stdO[i] = Cx.ONE;
                        set(mO, i, -99 - s21.db(), norm(-s21.deg()));
                        stdL[i] = Cx.ZERO;
                        set(mL, i, -3.52 - s21.db(), norm(-s21.deg()));
                    }
                }
                refDb[i] = used.db()[i];
                refDeg[i] = used.deg()[i];
            }
        } else {
            // the reference is whichever standard reads highest (at the first point)
            double o1 = open.db()[0], l1 = load.db()[0], s1 = shortR.db()[0];
            refType = o1 >= l1 ? (o1 >= s1 ? "Open" : "Short") : (l1 > s1 ? "Load" : "Short");
            Reading ref = switch (refType) {
                case "Open" -> open;
                case "Short" -> shortR;
                default -> load;
            };
            for (int i = 0; i < n; i++) {
                refDb[i] = ref.db()[i];
                refDeg[i] = ref.deg()[i];
                set(mO, i, open.db()[i] - refDb[i], norm(open.deg()[i] - refDeg[i]));
                set(mL, i, load.db()[i] - refDb[i], norm(load.deg()[i] - refDeg[i]));
                set(mS, i, shortR.db()[i] - refDb[i], norm(shortR.deg()[i] - refDeg[i]));
            }
        }
        Cx[] a = new Cx[n], b = new Cx[n], c = new Cx[n];
        for (int i = 0; i < n; i++) {
            Cx[] k = coefficients(Cx.ofDbDeg(mO[0][i], mO[1][i]), Cx.ofDbDeg(mS[0][i], mS[1][i]),
                    Cx.ofDbDeg(mL[0][i], mL[1][i]), stdO[i], stdS[i], stdL[i]);
            if (k == null) throw new IllegalArgumentException("divide by zero in calculating OSL coefficients at "
                    + fmt(freq[i]) + " MHz");
            a[i] = k[0];
            b[i] = k[1];
            c[i] = k[2];
        }
        return new OslCal(freq.clone(), refDb, refDeg, a, b, c, refType, LineCal.timeStamp(LocalDateTime.now()),
                linear, path, fixture, r0, shuntDelayNs);
    }

    /**
     * CalcOSLCoeff: from the measured (mo, ms, ml) and actual (so, ss, sl) reflections of the three
     * standards, the a, b, c of S = (M - b) / (a - c M); null if the standards are degenerate.
     */
    public static Cx[] coefficients(Cx mo, Cx ms, Cx ml, Cx so, Cx ss, Cx sl) {
        Cx k1 = ml.minus(ms), k2 = ms.minus(mo), k3 = mo.minus(ml);
        Cx k4 = sl.times(ss).times(k1), k5 = so.times(ss).times(k2), k6 = sl.times(so).times(k3);
        Cx k7 = so.times(k1), k8 = sl.times(k2), k9 = ss.times(k3);
        Cx d = k4.plus(k5).plus(k6);
        if (d.re() == 0 && d.im() == 0) return null;
        Cx a = mo.times(k7).plus(ml.times(k8)).plus(ms.times(k9)).div(d);
        Cx b = mo.times(k4).plus(ml.times(k5)).plus(ms.times(k6)).div(d);
        Cx c = k7.plus(k8).plus(k9).div(d);
        return new Cx[] {a, b, c};
    }

    /** ConvertRawDataToReflection: actual reflection from a measurement already referenced (reference subtracted). */
    public static Cx apply(Cx m, Cx a, Cx b, Cx c) {
        return m.minus(b).div(a.minus(c.times(m)));
    }

    private static void set(double[][] m, int i, double db, double deg) {
        m[0][i] = db;
        m[1][i] = deg;
    }

    private static double norm(double deg) { return PhaseDetector.normalise(deg); }

    // ---------------------------------------------------------------- applicability

    /** BandOSLCalIsCurrent: taken at exactly the steps of this sweep, same path. */
    public boolean matches(SweepSettings s) {
        return steps() == s.steps() && linear == (s.scale() == SweepSettings.Scale.LINEAR) && path == s.path()
                && same(startMHz(), s.startMHz()) && same(stopMHz(), s.stopMHz());
    }

    /** BaseOSLCalIsCurrent / the fixture part of BandOSLCalIsCurrent. */
    public boolean usableFor(int path, Fixture fixture, double r0) {
        return steps() > 0 && this.path == path && this.fixture == fixture && this.r0 == r0;
    }

    private static boolean same(double x, double y) { return Math.abs(x - y) <= 1e-9 * Math.max(1, Math.abs(x)); }

    /** The calibration at other frequencies (InstallSelectedOSLCal, base): reference in polar form, coefficients linearly. */
    public Point[] at(double[] freqs) {
        Point[] out = new Point[freqs.length];
        LineCal ref = new LineCal(freq, refDb, refDeg, timeStamp, linear, path, "Series", r0);
        for (int k = 0; k < freqs.length; k++) {
            double f = freqs[k];
            double[] r = ref.interpolate(f);
            int n = freq.length;
            Cx ak, bk, ck;
            if (f <= freq[0]) {
                ak = a[0]; bk = b[0]; ck = c[0];
            } else if (f >= freq[n - 1]) {
                ak = a[n - 1]; bk = b[n - 1]; ck = c[n - 1];
            } else {
                int lo = 0, hi = n - 1;
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (freq[mid] >= f) hi = mid; else lo = mid + 1;
                }
                int i = lo;
                double span = freq[i] - freq[i - 1], t = span == 0 ? 0 : (f - freq[i - 1]) / span;
                ak = lerp(a[i - 1], a[i], t);
                bk = lerp(b[i - 1], b[i], t);
                ck = lerp(c[i - 1], c[i], t);
            }
            out[k] = new Point(r[0], r[1], ak, bk, ck);
        }
        return out;
    }

    /** The calibration at the steps of a sweep it {@link #matches}. */
    public Point[] points() {
        Point[] out = new Point[freq.length];
        for (int i = 0; i < out.length; i++) out[i] = new Point(refDb[i], refDeg[i], a[i], b[i], c[i]);
        return out;
    }

    private static Cx lerp(Cx x, Cx y, double t) { return x.plus(y.minus(x).times(t)); }

    /** The calibration at one sweep point. */
    public record Point(double refDb, double refDeg, Cx a, Cx b, Cx c) {
        /** Measured raw reading (dBm, degrees) to actual reflection coefficient re R0. */
        public Cx correct(double dbm, double deg) {
            return apply(Cx.ofDbDeg(dbm - refDb, deg - refDeg), a, b, c);
        }
    }

    /** CalInfo$. */
    public String info() {
        return "Performed " + timeStamp + "; Path " + path + "; " + steps() + " " + (linear ? "linear" : "log")
                + " steps, " + LineCal.num(startMHz()) + " to " + LineCal.num(stopMHz()) + " MHz; " + fixture.title
                + ", R0 " + LineCal.num(r0) + " ohms; reference " + refType + ".";
    }

    private static String fmt(double v) { return LineCal.num(v); }

    // ---------------------------------------------------------------- file (Java extension: BASIC keeps base OSL in memory)

    /** Writes the calibration as text: a header and one row per frequency (MHz, ref dB, ref deg, a, b, c re/im). */
    public void write(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("! Base OSL Calibration Data (msa-java)");
        out.add("!" + timeStamp);
        out.add((linear ? "!Linear Sweep; " : "!Log Sweep; ") + "Path " + path + "; Fixture=" + fixture.name() + "; R0="
                + LineCal.num(r0) + "; ShuntDelay=" + LineCal.num(shuntDelayNs) + "; Ref=" + refType);
        out.add("! MHz RefDB RefDeg aRe aIm bRe bIm cRe cIm");
        for (int i = 0; i < freq.length; i++) {
            out.add(String.join(" ", LineCal.num(freq[i]), d(refDb[i]), d(refDeg[i]), d(a[i].re()), d(a[i].im()),
                    d(b[i].re()), d(b[i].im()), d(c[i].re()), d(c[i].im())));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, out, StandardCharsets.ISO_8859_1);
    }

    private static String d(double v) { return Double.toString(v); }

    public static OslCal read(Path file) throws IOException {
        List<String> titles = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        int lineNo = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
            lineNo++;
            String t = raw.strip();
            if (t.isEmpty()) continue;
            if (t.startsWith("!")) {
                if (rows.isEmpty()) titles.add(t.substring(1).strip());
                continue;
            }
            String[] w = t.split("[\\s,]+");
            if (w.length < 9) throw new IOException(file + " line " + lineNo + ": expected 9 numbers");
            double[] r = new double[9];
            try {
                for (int k = 0; k < 9; k++) r[k] = Double.parseDouble(w[k]);
            } catch (NumberFormatException e) {
                throw new IOException(file + " line " + lineNo + ": " + e.getMessage());
            }
            rows.add(r);
        }
        if (rows.isEmpty() || titles.size() < 3) throw new IOException(file + ": no calibration data");
        String sweep = titles.get(2);
        int n = rows.size();
        double[] f = new double[n], m = new double[n], p = new double[n];
        Cx[] a = new Cx[n], b = new Cx[n], c = new Cx[n];
        for (int i = 0; i < n; i++) {
            double[] r = rows.get(i);
            f[i] = r[0];
            m[i] = r[1];
            p[i] = r[2];
            a[i] = new Cx(r[3], r[4]);
            b[i] = new Cx(r[5], r[6]);
            c[i] = new Cx(r[7], r[8]);
        }
        try {
            return new OslCal(f, m, p, a, b, c, param(sweep, "Ref="), titles.get(1), sweep.contains("Linear"),
                    Integer.parseInt(param(sweep, "Path ")), Fixture.valueOf(param(sweep, "Fixture=").toUpperCase(Locale.ROOT)),
                    Double.parseDouble(param(sweep, "R0=")), Double.parseDouble(param(sweep, "ShuntDelay=")));
        } catch (RuntimeException e) {
            throw new IOException(file + ": bad header \"" + sweep + "\"");
        }
    }

    private static String param(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return "";
        int start = i + key.length(), end = s.indexOf(';', start);
        return (end < 0 ? s.substring(start) : s.substring(start, end)).strip();
    }
}
