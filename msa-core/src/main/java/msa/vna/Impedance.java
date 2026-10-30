package msa.vna;

/**
 * Conversions between reflection coefficient, impedance and fixture S21 (the uRefcoToImpedance,
 * uImpedanceToRefco, uPhaseShiftImpedance, uRefcoToSeriesS21DB, uRefcoToShuntS21DB routines). Impedances
 * are complex ohms; "huge" values are capped at {@link #MAX} as the BASIC program does (constMaxValue).
 */
public final class Impedance {

    /** constMaxValue: the largest R, L, C or reactance used. */
    public static final double MAX = 1e12;

    private Impedance() {}

    /**
     * uRefcoToImpedance: Z = R0 (1 + G) / (1 - G). |G| is limited to 0..1; G near +1 is a huge resistance,
     * near -1 zero; tiny parts (below 1 milliohm) are set to 0.
     */
    public static Cx fromRefco(double r0, Cx g) {
        double rho = g.abs();
        if (rho > 1) g = g.times(1 / rho);
        double a = g.re(), b = g.im();
        if (a > 0.999999) return new Cx(MAX, 0);
        if (a < -0.999999) return Cx.ZERO;
        double d = (1 - a) * (1 - a) + b * b;
        if (d < 1e-10) return new Cx(MAX, 0);
        Cx z = new Cx(r0 * (1 + a), r0 * b).div(new Cx(1 - a, -b));
        double r = z.re() < 0.001 ? 0 : z.re(), x = Math.abs(z.im()) < 0.001 ? 0 : z.im();
        return new Cx(r, x);
    }

    /** uImpedanceToRefco: G = (Z - R0) / (Z + R0); a negative resistance gives -1. */
    public static Cx toRefco(double r0, Cx z) {
        if (z.re() < 0) return new Cx(-1, 0);
        Cx den = new Cx(z.re() + r0, z.im());
        if (den.abs2() == 0) return new Cx(1, 0);
        return new Cx(z.re() - r0, z.im()).div(den);
    }

    /**
     * uPhaseShiftImpedance: impedance at the input of a line of impedance r0 and one-way phase theta
     * (degrees, may be negative) terminated by z: r0 (z + j r0 tan) / (r0 + j z tan).
     */
    public static Cx phaseShift(double r0, double thetaDeg, Cx z) {
        double t = thetaDeg;
        while (t > 180) t -= 360;
        while (t < -180) t += 360;
        double tan;
        if (Math.abs(t - 90) < 1e-8) tan = 1e9;
        else if (Math.abs(t + 90) < 1e-8) tan = -1e9;
        else tan = Math.tan(Math.toRadians(t));
        Cx num = new Cx(z.re(), z.im() + r0 * tan);
        Cx den = new Cx(r0 - z.im() * tan, z.re() * tan);
        if (Math.abs(den.re()) < 1e-9 && Math.abs(den.im()) < 1e-9) return new Cx(1e9, 0);
        return num.div(den).times(r0);
    }

    /** uRefcoToSeriesS21DB: S21 of a series fixture holding the impedance with this reflection: 2(1-G)/(2+(1-G)). */
    public static Cx seriesS21(Cx g) {
        g = limit(g);
        if (g.re() > 0.99999999) return Cx.ofDbDeg(-199, 0);
        Cx omg = Cx.ONE.minus(g);
        return omg.times(2).div(new Cx(2, 0).plus(omg));
    }

    /** uRefcoToShuntS21DB: S21 of a shunt fixture holding the impedance with this reflection: 2(1+G)/(2+(1+G)). */
    public static Cx shuntS21(Cx g) {
        g = limit(g);
        if (g.re() < -0.999999999) return Cx.ofDbDeg(-199, 0);
        Cx opg = Cx.ONE.plus(g);
        return opg.times(2).div(new Cx(2, 0).plus(opg));
    }

    private static Cx limit(Cx g) {
        double rho = g.abs();
        return rho > 1 ? g.times(1 / rho) : g;
    }

    /** uEquivParallelImped: the parallel R and X giving R + jX ({@link #MAX} for "none"). */
    public static double[] parallel(double r, double x) {
        double m2 = r * r + x * x;
        if (r == 0 && x == 0) return new double[] {0, MAX};
        double pr = r == 0 ? MAX : m2 / r;
        double px = x == 0 ? MAX : m2 / x;
        return new double[] {pr, px};
    }
}
