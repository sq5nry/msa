package msa.hw;

/**
 * ADC read parameters, the BASIC "UsbAdcControl" struct (spectrumanalyzer.bas, around line 1419).
 *
 * @param adcs     always 3 with EEPROM firmware v33 (read both magnitude and phase ADCs) -> command 0xB2
 * @param clocking 1; ignored by firmware v33
 * @param delay    ADC convert-pulse high time, 4 = about 4.4 us
 * @param bits     16; ignored by firmware v33
 * @param average  1..255 samples averaged in the FX2 ("Samples" box, global adcsamples)
 */
public record AdcConfig(int adcs, int clocking, int delay, int bits, int average) {
    public AdcConfig {
        if (adcs < 1 || adcs > 3) throw new IllegalArgumentException("adcs must be 1..3 (msadll rejects others)");
        check("clocking", clocking); check("delay", delay); check("bits", bits);
        if (average < 1 || average > 255) throw new IllegalArgumentException("average must be 1..255");
    }

    private static void check(String name, int v) {
        if (v < 0 || v > 255) throw new IllegalArgumentException(name + " must fit in a byte");
    }

    /** The values spectrumanalyzer.bas v118 sets at start-up. */
    public static AdcConfig defaults() { return new AdcConfig(3, 1, 4, 16, 1); }

    public AdcConfig withAverage(int samples) { return new AdcConfig(adcs, clocking, delay, bits, samples); }
}
