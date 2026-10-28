package msa.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MSA_Cal/MSA_CalPathN.txt (magnitude table: ADC reading -> dBm [, phase correction]) and
 * MSA_Cal/MSA_CalFreq.txt (frequency table: MHz -> dB correction), read like calReadFile$:
 * "*" starts a comment, "TAG=" lines, data separated by space, comma or tab, until "EndCalFile=".
 * Conversion is linear interpolation, clamped at the table ends (calConvertMagPhase, calConvertFreqError).
 */
public final class PathCalibration {

    /** Sorted by x (ADC value or MHz); y = dBm or dB; z = phase correction in degrees (magnitude table only). */
    public record Table(double[] x, double[] y, double[] z) {
        public int size() { return x.length; }

        /** Linear interpolation with the end values used outside the table. */
        public double y(double v) { return interp(v, y); }

        public double z(double v) { return interp(v, z); }

        private double interp(double v, double[] col) {
            int n = x.length;
            if (n == 0) return 0;
            if (v <= x[0]) return col[0];
            if (v >= x[n - 1]) return col[n - 1];
            int i = 1;
            while (v > x[i]) i++;
            double dx = x[i] - x[i - 1];
            if (dx == 0) dx = 1; // BASIC: "to prevent divide by zero"
            return col[i] - (x[i] - v) * (col[i] - col[i - 1]) / dx;
        }
    }

    private final Table mag;
    private final Table freq;

    public PathCalibration(Table mag, Table freq) {
        this.mag = mag;
        this.freq = freq;
    }

    /** Loads MSA_CalPath{path}.txt and MSA_CalFreq.txt from an MSA_Info folder. */
    public static PathCalibration load(Path msaInfo, int path) throws IOException {
        Path dir = msaInfo.resolve("MSA_Cal");
        Table m = parse(Files.readString(dir.resolve("MSA_CalPath" + path + ".txt"), StandardCharsets.ISO_8859_1), "MAGTABLE");
        Path f = dir.resolve("MSA_CalFreq.txt");
        Table fr = Files.exists(f) ? parse(Files.readString(f, StandardCharsets.ISO_8859_1), "FREQTABLE")
                                   : new Table(new double[] {0}, new double[] {0}, new double[] {0});
        return new PathCalibration(m, fr);
    }

    public static Table parse(String text, String wantedTag) {
        List<double[]> rows = new ArrayList<>();
        boolean inTable = false;
        double prevX = -100000;
        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("*")) continue;
            int eq = line.indexOf('=');
            if (eq >= 0) {
                String tag = line.substring(0, eq).strip().toUpperCase(Locale.ROOT);
                if (tag.equals("ENDCALFILE")) break;
                inTable = tag.equals(wantedTag);
                continue;
            }
            if (!inTable) continue;
            int star = line.indexOf('*');
            if (star >= 0) line = line.substring(0, star);
            String[] w = line.strip().split("[\\s,]+");
            if (w.length < 2) throw new IllegalArgumentException("bad calibration line: " + raw);
            double x = Double.parseDouble(w[0]);
            if (x <= prevX) continue; // BASIC drops non-increasing entries from files
            prevX = x;
            rows.add(new double[] {x, Double.parseDouble(w[1]), w.length > 2 ? Double.parseDouble(w[2]) : 0});
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("no " + wantedTag + " data");
        double[] x = new double[rows.size()], y = new double[rows.size()], z = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            x[i] = rows.get(i)[0];
            y[i] = rows.get(i)[1];
            z[i] = rows.get(i)[2];
        }
        return new Table(x, y, z);
    }

    public Table magTable() { return mag; }
    public Table freqTable() { return freq; }

    /** Magnitude calibration only: ADC reading to dBm (calConvertMagPhase). */
    public double magDb(long adc) { return mag.y(adc); }

    /**
     * Phase correction for this magnitude reading (calConvertMagPhase "difPhase", MagTable third column):
     * the change of the detector's phase with signal level, subtracted from the measured phase.
     */
    public double phaseCorrection(long adc) { return mag.z(adc); }

    /**
     * Frequency correction in dB (calConvertFreqError). Looked up by the TRUE signal frequency, as
     * CalcFreqCorrection does; the table holds separate sections for 1G, 2G and 3G in one frequency axis.
     */
    public double freqCorrection(double trueFreqMHz) { return freq.y(trueFreqMHz); }

    /** Power in dBm: magDB + freq correction at the true frequency. */
    public double dBm(long adc, double trueFreqMHz) {
        return magDb(adc) + freqCorrection(trueFreqMHz);
    }
}
