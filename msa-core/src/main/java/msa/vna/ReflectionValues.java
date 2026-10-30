package msa.vna;

import msa.trace.GraphData;

/**
 * Everything the reflection mode shows, derived from S11 re the graph R0 at one frequency
 * (CalcReflectDerivedData, CalcReflectGraphData).
 *
 * @param s11Db  S11 magnitude, dB
 * @param s11Deg S11 angle, degrees -180..180
 * @param rho    |S11|
 * @param rs     series resistance, ohms
 * @param xs     series reactance, ohms
 * @param rp     equivalent parallel resistance ({@link Impedance#MAX} for none)
 * @param xp     equivalent parallel reactance
 * @param cs     equivalent series capacitance, F
 * @param ls     equivalent series inductance, H
 * @param cp     equivalent parallel capacitance, F
 * @param lp     equivalent parallel inductance, H
 * @param swr    VSWR (9999 for total reflection)
 */
public record ReflectionValues(double s11Db, double s11Deg, double rho, double rs, double xs, double rp, double xp,
                               double cs, double ls, double cp, double lp, double swr) {

    private static final double MAX = Impedance.MAX;

    /** CalcReflectDerivedData. */
    public static ReflectionValues of(Cx s11, double fMHz, double graphR0) {
        double db = s11.db(), deg = s11.deg(), rho = Math.pow(10, db / 20);
        Cx z = Impedance.fromRefco(graphR0, Cx.polar(rho, deg));
        double r = z.re(), x = z.im();
        double fHz = fMHz * 1e6, w = 2 * Math.PI * fHz;
        double ls, cs;
        if (fHz == 0) {
            ls = 0;
            cs = MAX;
        } else {
            ls = x >= MAX ? MAX : x / w;
            cs = x == 0 ? MAX : -1 / (w * x);
        }
        if (Math.abs(x) < 0.001) x = 0;
        if (r < 0.001) r = 0;
        double[] par = Impedance.parallel(r, x);
        double rp = par[0], xp = par[1];
        if (Math.abs(xp) < 0.001) xp = 0;
        if (rp < 0.001) rp = 0;
        double lp, cp;
        if (fHz == 0) {
            rp = MAX;
            lp = MAX;
            cp = 0;
        } else {
            lp = xp >= MAX ? MAX : xp / w;
            cp = xp == 0 ? MAX : -1 / (w * xp);
        }
        double swr = rho > 0.999999 ? 9999 : (1 + rho) / (1 - rho);
        cs = clamp1(cs);
        ls = clamp1(ls);
        cp = clamp1(cp);
        if (Math.abs(cs) < 1e-15) cs = 0;
        if (Math.abs(cp) < 1e-15) cp = 0;
        if (Math.abs(ls) < 1e-12) ls = 0;
        if (Math.abs(lp) < 1e-12) lp = 0;
        return new ReflectionValues(db, deg, rho, r, x, rp, xp, cs, ls, cp, lp, swr);
    }

    private static double clamp1(double v) { return Math.max(-1, Math.min(1, v)); }

    public double zMag() { return Math.hypot(rs, xs); }
    public double zDeg() { return Math.toDegrees(Math.atan2(xs, rs)); }

    /** CalcReflectGraphData for one data type (NaN for types that are not reflection types). */
    public double value(GraphData g) {
        return switch (g) {
            case S11_DB -> s11Db;
            case S11_ANGLE, THETA -> s11Deg;
            case RHO -> rho;
            case Z_MAG -> zMag();
            case Z_ANGLE -> zDeg();
            case RS -> rs;
            case XS -> xs;
            case RP -> rp;
            case XP -> xp;
            case CS -> cs;
            case LS -> ls;
            case CP -> cp;
            case LP -> lp;
            case SWR -> swr;
            case RETURN_LOSS -> -s11Db;
            case REFLECT_POWER -> 100 * rho * rho;
            case COMPONENT_Q -> rs == 0 ? 99999 : Math.abs(xs) / rs;
            case ADMIT_MAG -> zMag() == 0 ? MAX : 1 / zMag();
            case ADMIT_ANGLE -> -zDeg();
            case CONDUCTANCE -> new Cx(rs, xs).inverse().re();
            case SUSCEPTANCE -> new Cx(rs, xs).inverse().im();
            case NONE -> 0;
            default -> Double.NaN;
        };
    }
}
