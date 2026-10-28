package msa.vna;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import msa.sweep.SweepSettings;

/**
 * The operating calibration of the transmission modes ("Operating Cal" menu): the band calibration of the
 * current session, the base calibration kept in MSA_Info/OperatingCal/BaseLineCal.txt, and which one the
 * user wants applied ("Reference To"). {@link #install} decides what actually applies to a sweep, as
 * InstallSelectedLineCal does.
 */
public final class TransmissionCal {

    /** desiredCalLevel / applyCalLevel: 0 = no reference, 1 = base, 2 = band. */
    public enum Level {
        NONE("No Reference"), BASE("Baseline Cal"), BAND("Band Sweep Cal");

        public final String title;

        Level(String title) { this.title = title; }
    }

    /** What applies to a sweep: the level actually used and the reference at each of its points (or null). */
    public record Installed(Level level, double[] db, double[] deg) {
        public static final Installed NONE = new Installed(Level.NONE, null, null);
    }

    /** File name in the OperatingCal folder, shared with the BASIC program. */
    public static final String BASE_FILE = "BaseLineCal.txt";

    private final Path folder;
    private LineCal band;
    private LineCal base;
    private String loadProblem;

    /** @param operatingCalFolder MSA_Info/OperatingCal; the base calibration is read from it if present */
    public TransmissionCal(Path operatingCalFolder) {
        this.folder = operatingCalFolder;
        Path f = baseFile();
        if (f != null && Files.exists(f)) {
            try {
                base = LineCal.read(f);
            } catch (IOException | RuntimeException e) {
                loadProblem = f + " could not be read: " + e.getMessage();
            }
        }
    }

    /** A calibration that only lives in memory (tests). */
    public static TransmissionCal inMemory() { return new TransmissionCal(null); }

    public Path baseFile() { return folder == null ? null : folder.resolve(BASE_FILE); }

    /** Why the base calibration file could not be used, or null. */
    public String loadProblem() { return loadProblem; }

    public LineCal band() { return band; }
    public LineCal base() { return base; }

    public void setBand(LineCal c) { band = c; }
    public void clearBand() { band = null; }

    /** [SaveBaseCal]: the band calibration becomes the base and is written to BaseLineCal.txt. */
    public void saveBandAsBase() throws IOException {
        if (band == null) throw new IllegalStateException("there is no band calibration to save");
        base = band;
        Path f = baseFile();
        if (f != null) base.write(f, "BaseLine Calibration Data");
    }

    /** [ClearBaseCal]: forgets the base calibration and deletes BaseLineCal.txt. */
    public void clearBase() throws IOException {
        base = null;
        Path f = baseFile();
        if (f != null) Files.deleteIfExists(f);
    }

    /**
     * InstallSelectedLineCal: with "Band" wanted, the band calibration if it was taken at exactly this
     * sweep's steps; otherwise (or with "Base" wanted) the base calibration interpolated to the sweep, if it
     * was made with the same path and fixture; otherwise no reference.
     *
     * @param freqs the sweep's frequencies, one per point
     */
    public Installed install(Level wanted, SweepSettings s, String jigAttach, double jigR0, double[] freqs) {
        if (wanted == Level.NONE) return Installed.NONE;
        if (wanted == Level.BAND && band != null && band.matches(s) && band.freq().length == freqs.length)
            return new Installed(Level.BAND, band.db(), band.deg());
        if (base != null && base.usableFor(s.path(), jigAttach, jigR0)) {
            double[][] v = base.at(freqs);
            return new Installed(Level.BASE, v[0], v[1]);
        }
        return Installed.NONE;
    }
}
