package msa.vna;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import msa.sweep.SweepSettings;

/**
 * The operating calibration of the reflection mode: the band OSL calibration of this session, the base
 * calibration, and what applies to a sweep (InstallSelectedOSLCal). The BASIC program keeps the base OSL
 * calibration in memory only; here it is also kept in MSA_Info/OperatingCal/{@value #BASE_FILE} so it
 * survives a restart.
 */
public final class ReflectionCal {

    public static final String BASE_FILE = "BaseOSLCal.txt";

    /** What applies to a sweep: the level actually used and the calibration at each of its points (or null). */
    public record Installed(TransmissionCal.Level level, OslCal.Point[] points) {
        public static final Installed NONE = new Installed(TransmissionCal.Level.NONE, null);
    }

    private final Path folder;
    private OslCal band;
    private OslCal base;
    private String loadProblem;

    public ReflectionCal(Path operatingCalFolder) {
        this.folder = operatingCalFolder;
        Path f = baseFile();
        if (f != null && Files.exists(f)) {
            try {
                base = OslCal.read(f);
            } catch (IOException | RuntimeException e) {
                loadProblem = f + " could not be read: " + e.getMessage();
            }
        }
    }

    public static ReflectionCal inMemory() { return new ReflectionCal(null); }

    public Path baseFile() { return folder == null ? null : folder.resolve(BASE_FILE); }
    public String loadProblem() { return loadProblem; }
    public OslCal band() { return band; }
    public OslCal base() { return base; }
    public void setBand(OslCal c) { band = c; }
    public void clearBand() { band = null; }

    /** [SaveBaseCal] for reflection (TransferBandToBaseOSLCal), also written to the file. */
    public void saveBandAsBase() throws IOException {
        if (band == null) throw new IllegalStateException("there is no band calibration to save");
        base = band;
        Path f = baseFile();
        if (f != null) base.write(f);
    }

    public void clearBase() throws IOException {
        base = null;
        Path f = baseFile();
        if (f != null) Files.deleteIfExists(f);
    }

    /**
     * InstallSelectedOSLCal: with "Band" wanted, the band calibration if it was taken at exactly this sweep's
     * steps with this fixture; otherwise (or with "Base" wanted) the base calibration interpolated, if made with
     * the same path and fixture; otherwise none.
     */
    public Installed install(TransmissionCal.Level wanted, SweepSettings s, OslCal.Fixture fixture, double r0, double[] freqs) {
        if (wanted == TransmissionCal.Level.NONE) return Installed.NONE;
        if (wanted == TransmissionCal.Level.BAND && band != null && band.matches(s) && band.usableFor(s.path(), fixture, r0)
                && band.freq().length == freqs.length)
            return new Installed(TransmissionCal.Level.BAND, band.points());
        if (base != null && base.usableFor(s.path(), fixture, r0))
            return new Installed(TransmissionCal.Level.BASE, base.at(freqs));
        return Installed.NONE;
    }
}
