package msa.vna;

/** A complex number (the BASIC program's real/imag pairs and cxDivide / cxInvert). */
public record Cx(double re, double im) {

    public static final Cx ZERO = new Cx(0, 0);
    public static final Cx ONE = new Cx(1, 0);

    /** From magnitude in dB and angle in degrees. */
    public static Cx ofDbDeg(double db, double deg) {
        double m = Math.pow(10, db / 20), r = Math.toRadians(deg);
        return new Cx(m * Math.cos(r), m * Math.sin(r));
    }

    /** From linear magnitude and angle in degrees. */
    public static Cx polar(double mag, double deg) {
        double r = Math.toRadians(deg);
        return new Cx(mag * Math.cos(r), mag * Math.sin(r));
    }

    public Cx plus(Cx o) { return new Cx(re + o.re, im + o.im); }
    public Cx minus(Cx o) { return new Cx(re - o.re, im - o.im); }
    public Cx times(Cx o) { return new Cx(re * o.re - im * o.im, re * o.im + im * o.re); }
    public Cx times(double k) { return new Cx(re * k, im * k); }

    /** cxDivide; division by zero gives NaN parts. */
    public Cx div(Cx o) {
        double d = o.re * o.re + o.im * o.im;
        if (d == 0) return new Cx(Double.NaN, Double.NaN);
        return new Cx((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d);
    }

    /** cxInvert. */
    public Cx inverse() { return ONE.div(this); }

    public double abs() { return Math.hypot(re, im); }
    public double abs2() { return re * re + im * im; }

    /** uATan2: angle in degrees, -180..180. */
    public double deg() { return Math.toDegrees(Math.atan2(im, re)); }

    /** 20 log10 |z|, computed as 10 log10 |z|^2 (uSafeLog10 keeps a zero finite: -200 dB). */
    public double db() {
        double m2 = abs2();
        return m2 < 1e-20 ? -200 : 10 * Math.log10(m2);
    }

    public boolean isNaN() { return Double.isNaN(re) || Double.isNaN(im); }
}
