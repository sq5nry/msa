package msa.vna;

import java.util.Locale;

/**
 * A series or parallel R, L, C combination, optionally behind a delay: the "RLC[...]" specification of the
 * BASIC program (uParseRLC, uComboImpedance, uRLCComboResponse), used for the calibration standards, e.g.
 * {@code RLC[S, C0, D0]} (ideal open), {@code RLC[P, R0, L0, D0]} (ideal short), {@code RLC[P, R50, C0]}
 * (ideal 50 ohm load), {@code RLC[P, R49.9, C0.4p, D12p]}.
 *
 * <p>Items: S or P (series / parallel, default P), R ohms, L henries, C farads, QL and QC (component Q),
 * D one-way delay in seconds (in a 50 ohm line). Numbers may end in a multiplier: f p n u m k M G T.
 * The BASIC program also accepts a "Coax[...]" line; that is not supported here.
 *
 * @param series true for S (R + L + C in series), false for P
 * @param r      ohms ({@link Impedance#MAX} = absent in parallel)
 * @param l      henries ({@link Impedance#MAX} = absent in parallel)
 * @param c      farads (0 = absent in parallel; {@link Impedance#MAX} = short in series)
 * @param delay  one-way delay in seconds
 */
public record RlcSpec(String text, boolean series, double r, double l, double c, double ql, double qc, double delay) {

    /** The line impedance the delay is taken in (CoaxParseSpecs default). */
    public static final double LINE_R0 = 50;

    public static RlcSpec parse(String spec) {
        String s = spec == null ? "" : spec.strip();
        if (s.toUpperCase(Locale.ROOT).contains("COAX")) throw new IllegalArgumentException("Coax[...] specifications are not supported: " + spec);
        String body = s;
        int tag = s.indexOf("RLC");
        if (tag >= 0) {
            int open = s.indexOf('[', tag), close = s.indexOf(']', tag);
            if (open < 0 || close < 0 || open >= close) throw new IllegalArgumentException("bad RLC specification: " + spec);
            body = s.substring(open + 1, close);
        } else if (s.startsWith("[") && s.endsWith("]")) {
            body = s.substring(1, s.length() - 1);
        }
        boolean series = false;
        double r = Impedance.MAX, l = Impedance.MAX, c = 0, ql = 100000, qc = 100000, d = 0;
        for (String part : body.split(",")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            String tagName;
            String data;
            if (p.startsWith("Q") && p.length() >= 2) {
                tagName = p.substring(0, 2);
                data = p.substring(2);
            } else {
                tagName = p.substring(0, 1);
                data = p.substring(1);
            }
            double v = data.isBlank() ? 0 : value(data);
            switch (tagName) {
                case "S" -> { series = true; r = 0; l = 0; c = Impedance.MAX; }
                case "P" -> { series = false; r = Impedance.MAX; l = Impedance.MAX; c = 0; }
                case "R" -> { r = v; if (r < 0) throw new IllegalArgumentException("negative R in " + spec); }
                case "L" -> l = v;
                case "C" -> c = v;
                case "QL" -> { ql = v; if (ql <= 0) throw new IllegalArgumentException("QL must be positive in " + spec); }
                case "QC" -> { qc = v; if (qc <= 0) throw new IllegalArgumentException("QC must be positive in " + spec); }
                case "D" -> d = v;
                default -> throw new IllegalArgumentException("unknown item \"" + p + "\" in " + spec);
            }
        }
        return new RlcSpec(s, series, r, l, c, ql, qc, d);
    }

    /** uValWithMult: a number with an optional multiplier character ("10n", "2.2k", "1e-9"). */
    public static double value(String text) {
        String t = text.replace(" ", "");
        int i = 0;
        while (i < t.length() && "0123456789+-.eE".indexOf(t.charAt(i)) >= 0) i++;
        // "1e" or "2E" followed by a non-digit is a number then a multiplier ("E" is not one)
        String num = t.substring(0, i);
        double base;
        try {
            base = num.isEmpty() ? 0 : Double.parseDouble(num);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + text);
        }
        if (i >= t.length()) return base;
        double mult = switch (t.charAt(i)) {
            case 'T' -> 1e12;
            case 'G' -> 1e9;
            case 'M' -> 1e6;
            case 'k', 'K' -> 1e3;
            case 'm' -> 1e-3;
            case 'u' -> 1e-6;
            case 'n' -> 1e-9;
            case 'p' -> 1e-12;
            case 'f' -> 1e-15;
            default -> 1;
        };
        return base * mult;
    }

    /** uComboImpedance at f Hz. */
    public Cx impedance(double fHz) {
        double w = 2 * Math.PI * fHz;
        double zlr, zli, zcr, zci;
        if (fHz != 0 && Math.abs(l) < Impedance.MAX) {
            zli = w * l;
            zlr = zli / ql;
        } else {
            zli = Impedance.MAX;
            zlr = 0;
        }
        if (fHz != 0 && c != 0) {
            zci = -1 / (w * c);
            zcr = Math.abs(zci) / qc;
        } else {
            zci = -Impedance.MAX;
            zcr = 0;
        }
        double zr, zi;
        if (series) {
            zr = r + zlr;
            zi = zli;
            if (c == 0 || fHz == 0) {
                zi = -Impedance.MAX;
            } else if (Math.abs(c) < Impedance.MAX) {
                zi += zci;
                zr += zcr;
            }
        } else {
            if (r == 0 || l == 0 || Math.abs(c) >= Impedance.MAX) {
                zr = 0;
                zi = 0;
            } else {
                Cx y = new Cx(1 / r, 0);
                if (!(fHz == 0 || Math.abs(l) >= Impedance.MAX)) y = y.plus(new Cx(zlr, zli).inverse());
                if (c != 0) y = y.plus(new Cx(zcr, zci).inverse());
                Cx z = y.inverse();
                zr = z.re();
                zi = z.im();
            }
        }
        zi = Math.max(-Impedance.MAX, Math.min(Impedance.MAX, zi));
        zr = Math.max(0, Math.min(Impedance.MAX, zr));
        return new Cx(zr, zi);
    }

    /**
     * uRLCComboResponse "S11": reflection coefficient re z0 at f MHz, the delay taken as a round trip in a
     * {@link #LINE_R0} line.
     */
    public Cx s11(double fMHz, double z0) {
        double fHz = fMHz * 1e6;
        Cx z = impedance(fHz);
        if (delay != 0) {
            Cx g = Impedance.toRefco(LINE_R0, z);
            g = Cx.polar(g.abs(), g.deg() - 2 * delay * 360 * fHz);
            z = Impedance.fromRefco(LINE_R0, g);
        }
        return Impedance.toRefco(z0, z);
    }
}
