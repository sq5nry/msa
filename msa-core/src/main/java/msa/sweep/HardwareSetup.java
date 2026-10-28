package msa.sweep;

import java.util.Objects;

import msa.hw.SlimCommands.VideoFilter;
import msa.synth.SynthPlanner;

/**
 * The switchable hardware state of the spectrum analyser (Sweep Parameters Window: final filter path,
 * video filter, frequency band, tracking / signal generator) and the operating mode, which decides whether
 * the phase detector is read and the transmission/reflection switch.
 *
 * @param path      resolution filter path 1..N (config.txt "Filter=" lines)
 * @param video     video filter
 * @param band      1..3, or 0 = auto by frequency (Prefs "FreqMode")
 * @param generator tracking generator with offset ("IsTG=1", "Offset") or signal generator ("IsTG=0", "SGPreset").
 *                  The BASIC "Spectrum Analyzer" mode is the signal-generator case: the TG hardware is still
 *                  commanded, at the fixed SG frequency.
 * @param mode      SA, scalar/vector transmission or reflection (Prefs "msaMode")
 */
public record HardwareSetup(int path, VideoFilter video, int band, SynthPlanner.Generator generator, MsaMode mode) {

    public HardwareSetup {
        Objects.requireNonNull(video);
        Objects.requireNonNull(generator);
        Objects.requireNonNull(mode);
        if (path < 1) throw new IllegalArgumentException("path must be >= 1");
        if (band < 0 || band > 3) throw new IllegalArgumentException("band must be 0..3");
    }

    /** Spectrum analyser mode. */
    public HardwareSetup(int path, VideoFilter video, int band, SynthPlanner.Generator generator) {
        this(path, video, band, generator, MsaMode.SA);
    }

    public static HardwareSetup defaults() {
        return new HardwareSetup(1, VideoFilter.WIDE, 1, new SynthPlanner.Tracking(0));
    }

    public boolean trackingGenerator() { return generator instanceof SynthPlanner.Tracking; }

    public HardwareSetup withPath(int p) { return new HardwareSetup(p, video, band, generator, mode); }
    public HardwareSetup withVideo(VideoFilter v) { return new HardwareSetup(path, v, band, generator, mode); }
    public HardwareSetup withBand(int b) { return new HardwareSetup(path, video, b, generator, mode); }
    public HardwareSetup withGenerator(SynthPlanner.Generator g) { return new HardwareSetup(path, video, band, g, mode); }
    public HardwareSetup withMode(MsaMode m) { return new HardwareSetup(path, video, band, generator, m); }
}
