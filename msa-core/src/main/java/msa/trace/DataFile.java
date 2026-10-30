package msa.trace;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import msa.sweep.MsaMode;

/**
 * "File > Save Data" for the spectrum analyser: one line per step with frequency, power and raw ADC.
 * The BASIC program writes its own context file format; this is a plain text table instead, readable by
 * spreadsheets and by {@code msa-probe sweep --csv} users. Header lines start with '!' as in the BASIC
 * data files (and in Touchstone files), so the BASIC convention for comments is kept.
 */
public final class DataFile {

    private DataFile() {}

    /** Writes the measured points of {@code d}; {@code header} lines are written as "! line". */
    public static void write(Path file, SweepData d, List<String> header) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(w, d, header);
        }
    }

    public static void write(Writer w, SweepData d, List<String> header) throws IOException {
        write(w, d, header, MsaMode.SA);
    }

    /** As {@link #write(Path, SweepData, List)} with the columns of the mode. */
    public static void write(Path file, SweepData d, List<String> header, MsaMode mode) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(w, d, header, mode);
        }
    }

    /**
     * Spectrum analyser: step, frequency, power, ADC. Transmission: also S21 in dB (referenced to the
     * calibration) and, in vector transmission, S21 phase (referenced, with plane extension), the phase as
     * measured, the phase ADC and the PDM state. Reflection: S11 (calibrated, re the graph R0), the series
     * impedance, then the raw readings.
     */
    public static void write(Writer w, SweepData d, List<String> header, MsaMode mode) throws IOException {
        String nl = System.lineSeparator();
        for (String h : header) w.write("! " + h + nl);
        switch (mode) {
            case SA -> w.write("! Step, Freq(MHz), Mag(dBm), ADC" + nl);
            case SCALAR_TRANS -> w.write("! Step, Freq(MHz), S21(dB), Mag(dBm), ADC" + nl);
            case REFLECTION -> w.write("! Step, Freq(MHz), S11(dB), S11(deg), Rs(ohm), Xs(ohm), Mag(dBm), Phase(deg), ADC, PhaseADC, PDM" + nl);
            default -> w.write("! Step, Freq(MHz), S21(dB), S21(deg), Mag(dBm), Phase(deg), ADC, PhaseADC, PDM" + nl);
        }
        for (int i = 0; i < d.size(); i++) {
            if (!d.isMeasured(i)) continue;
            switch (mode) {
                case SA -> w.write(String.format(Locale.ROOT, "%d, %.6f, %.5f, %d%n", i, d.frequency(i), d.dBm(i), d.adc(i)));
                case SCALAR_TRANS -> w.write(String.format(Locale.ROOT, "%d, %.6f, %.5f, %.5f, %d%n", i, d.frequency(i),
                        d.s21Db(i), d.dBm(i), d.adc(i)));
                case REFLECTION -> {
                    msa.vna.ReflectionValues r = d.reflection(i);
                    w.write(String.format(Locale.ROOT, "%d, %.6f, %.5f, %.2f, %.6g, %.6g, %.5f, %.2f, %d, %d, %d%n", i,
                            d.frequency(i), r.s11Db(), r.s11Deg(), r.rs(), r.xs(), d.dBm(i), d.phase(i), d.adc(i),
                            d.phaseAdc(i), d.pdm(i)));
                }
                default -> w.write(String.format(Locale.ROOT, "%d, %.6f, %.5f, %.2f, %.5f, %.2f, %d, %d, %d%n", i, d.frequency(i),
                        d.s21Db(i), d.s21Deg(i), d.dBm(i), d.phase(i), d.adc(i), d.phaseAdc(i), d.pdm(i)));
            }
        }
    }
}
