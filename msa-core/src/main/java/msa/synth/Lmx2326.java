package msa.synth;

/**
 * National LMX2326 register words, ported from [Command2326R] and [Create2326N].
 * The chip has a 21-bit shift register; bit k of the returned int is BASIC's Nk.
 * Bits 21..23 are 0 (the BASIC code clocks 24 bits; the first three fall out of the register).
 */
public final class Lmx2326 {
    private Lmx2326() {}

    /** Prescaler modulus P: ncounter = B * 32 + A. */
    public static final int PRESCALER = 32;

    /**
     * FoLD pin 14 selection ("pllout", Special Tests window). 0 = tri-state, the BASIC default.
     */
    public enum FoLD {
        TRISTATE(0, 0, 0), HIGH(1, 1, 0), LOW(1, 1, 1), N_DIVIDER(0, 1, 0),
        R_DIVIDER(0, 0, 1), SERIAL_DATA(0, 1, 1), DIGITAL_LOCK(1, 0, 0), ANALOG_LOCK(1, 0, 1);

        final int n4, n5, n6;

        FoLD(int n4, int n5, int n6) {
            this.n4 = n4;
            this.n5 = n5;
            this.n6 = n6;
        }
    }

    /** Initialisation / function latch: address bits N1 N0 = 1 1. */
    public static int initWord(int phasePolarity, FoLD fold) {
        if (phasePolarity != 0 && phasePolarity != 1) throw new IllegalArgumentException("phase polarity must be 0 or 1");
        return 0b11                       // N0, N1: F1 address
             | fold.n4 << 4 | fold.n5 << 5 | fold.n6 << 6
             | phasePolarity << 7;        // N7: phase detector polarity, 1 = positive
        // N2 counter reset, N3 power down, N8..N20 fastlock/test: all 0
    }

    /** R counter latch: address 0 0, R in N2..N15, lock detect mode N20 = 0 (3 cycles). */
    public static int rWord(int rcounter) {
        if (rcounter < 3) throw new SynthException("2326 R counter <3");
        if (rcounter > 16383) throw new SynthException("2326 R counter >16383");
        return rcounter << 2;
    }

    /** B and A counters for an N value, and the N latch word. */
    public record NWord(int bcounter, int acounter, int bits) {}

    /** N latch: N0 = 1, N1 = 0, A in N2..N6, B in N7..N19, N20 = 1 (charge pump 1 mA). */
    public static NWord nWord(int ncounter) {
        int b = ncounter / PRESCALER;
        int a = ncounter - b * PRESCALER;
        if (b < 3) throw new SynthException("2326 Bcounter <3");
        if (b > 8191) throw new SynthException("2326 Bcounter >8191");
        if (b < a) throw new SynthException("2326 Bcounter<Acounter");
        int bits = 1 | a << 2 | b << 7 | 1 << 20;
        return new NWord(b, a, bits);
    }
}
