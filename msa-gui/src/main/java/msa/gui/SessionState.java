package msa.gui;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import msa.hw.SlimCommands.VideoFilter;
import msa.model.DisplaySettings;
import msa.model.MsaSettings;
import msa.model.VnaSettings;
import msa.sweep.FrequencyRange;
import msa.sweep.HardwareSetup;
import msa.sweep.MsaMode;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;
import msa.trace.GraphData;
import msa.trace.Markers;
import msa.vna.TransmissionCal;

/**
 * What the window looked like when it was closed, restored at the next start if msa-java.properties has
 * {@code rememberSettings=true} (the default): the sweep panel (span, steps/auto, wait/auto, samples, RBW path,
 * video filter, band, divisions, scale, direction, generator mode with both the SG frequency and the TG
 * offset/direction), the operating mode with the transmission reference choice, through delay and plane
 * extension, the Y axes and trace styles, the markers (by frequency) with their options, and the main
 * window's position, size and maximised state. (The band calibration itself is not kept, as in BASIC; the
 * base calibration is in OperatingCal/BaseLineCal.txt.)
 *
 * <p>Stored in MSA_Prefs/{@value #FILE}, apart from Prefs.txt, so the Liberty BASIC program's preferences
 * are only changed by an explicit "Save Prefs". Colours are not stored here; they still come from Prefs.txt.
 * Reading is tolerant: a damaged or partial file restores what it can and the rest comes from Prefs.txt.
 */
public record SessionState(MsaSettings settings, double sgoutMHz, double tgOffsetMHz, boolean tgReverse,
                           Map<Markers.Id, Double> markerFreqs, Markers.Id selected, boolean showMarkers,
                           Markers.LrMode lrMode, int traceWidth1, int traceWidth2, boolean displaySweepTime,
                           WindowGeometry window) {

    /**
     * Main window position and size (the normal, not maximised, bounds) and whether it was maximised.
     * Screen coordinates as JavaFX gives them; null if unknown.
     */
    public record WindowGeometry(double x, double y, double width, double height, boolean maximized) {}

    public static final String FILE = "java-session.properties";

    public SessionState {
        markerFreqs = Map.copyOf(markerFreqs);
    }

    public static Path file(Path msaInfo) { return msaInfo.resolve("MSA_Prefs").resolve(FILE); }

    // ---------------------------------------------------------------- write

    public Properties toProperties() {
        Properties p = new Properties();
        SweepSettings s = settings.sweep();
        DisplaySettings d = settings.display();
        put(p, "sweep.start", s.startMHz());
        put(p, "sweep.stop", s.stopMHz());
        p.setProperty("sweep.steps", String.valueOf(s.steps()));
        p.setProperty("sweep.autoSteps", String.valueOf(settings.autoSteps()));
        p.setProperty("sweep.autoWait", String.valueOf(settings.autoWait()));
        p.setProperty("sweep.autoSamples", String.valueOf(settings.autoSamples()));
        p.setProperty("sweep.scale", s.scale().name());
        p.setProperty("sweep.direction", s.direction().name());
        p.setProperty("sweep.wait", String.valueOf(s.waitMs()));
        p.setProperty("sweep.samples", String.valueOf(s.samples()));
        p.setProperty("hw.path", String.valueOf(s.path()));
        p.setProperty("hw.video", s.video().name());
        p.setProperty("hw.band", String.valueOf(s.band()));
        p.setProperty("hw.mode", s.mode().name());
        VnaSettings v = settings.vna();
        put(p, "vna.planeExtNs", v.planeExtNs());
        p.setProperty("vna.calLevel", v.calLevel().name());
        put(p, "vna.throughDelayNs", v.throughDelayNs());
        p.setProperty("gen.tracking", String.valueOf(s.generator() instanceof SynthPlanner.Tracking));
        put(p, "gen.sgout", sgoutMHz);
        put(p, "gen.offset", tgOffsetMHz);
        p.setProperty("gen.reverse", String.valueOf(tgReverse));
        p.setProperty("display.y1Data", String.valueOf(d.y1Data()));
        p.setProperty("display.y2Data", String.valueOf(d.y2Data()));
        put(p, "display.y1Min", d.y1Axis().min());
        put(p, "display.y1Max", d.y1Axis().max());
        put(p, "display.y2Min", d.y2Axis().min());
        put(p, "display.y2Max", d.y2Axis().max());
        p.setProperty("display.autoscale1", String.valueOf(d.autoscaleY1()));
        p.setProperty("display.autoscale2", String.valueOf(d.autoscaleY2()));
        p.setProperty("display.y1Mode", String.valueOf(d.y1Mode()));
        p.setProperty("display.y2Mode", String.valueOf(d.y2Mode()));
        p.setProperty("display.gridX", String.valueOf(d.gridDivX()));
        p.setProperty("display.gridY", String.valueOf(d.gridDivY()));
        p.setProperty("display.refreshEachScan", String.valueOf(d.refreshEachScan()));
        p.setProperty("display.sweepTime", String.valueOf(displaySweepTime));
        p.setProperty("trace.width1", String.valueOf(traceWidth1));
        p.setProperty("trace.width2", String.valueOf(traceWidth2));
        markerFreqs.forEach((id, f) -> put(p, "marker." + id.name(), f));
        if (selected != null) p.setProperty("markers.selected", selected.name());
        p.setProperty("markers.show", String.valueOf(showMarkers));
        p.setProperty("markers.lrMode", lrMode.name());
        if (window != null) {
            put(p, "window.x", window.x());
            put(p, "window.y", window.y());
            put(p, "window.width", window.width());
            put(p, "window.height", window.height());
            p.setProperty("window.maximized", String.valueOf(window.maximized()));
        }
        return p;
    }

    /** Writes via a temporary file, so a crash while writing cannot leave half a file. */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            toProperties().store(w, "MSA Java GUI: state at exit, restored at start (rememberSettings in msa-java.properties)");
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------------------------------------------------------------- read

    /** Result of reading: the state and the keys that had to be ignored. */
    public record Loaded(SessionState state, List<String> warnings) {}

    /**
     * Reads the file over {@code base} (the settings from Prefs.txt): every valid key replaces the
     * corresponding value, anything invalid keeps the base value and is reported.
     *
     * @return empty if there is no file
     */
    public static Optional<Loaded> load(Path file, MsaSettings base, int filterCount) throws IOException {
        return load(file, base, filterCount, true, true);
    }

    /**
     * As {@link #load(Path, MsaSettings, int)}; a saved network-analyser mode is only restored if the
     * hardware has a tracking generator ({@code hasTg}) and, for the vector modes, the phase detector.
     */
    public static Optional<Loaded> load(Path file, MsaSettings base, int filterCount, boolean hasTg, boolean hasVna)
            throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return Optional.of(fromProperties(p, base, filterCount, hasTg, hasVna));
    }

    public static Loaded fromProperties(Properties p, MsaSettings base, int filterCount) {
        return fromProperties(p, base, filterCount, true, true);
    }

    public static Loaded fromProperties(Properties p, MsaSettings base, int filterCount, boolean hasTg, boolean hasVna) {
        Reader2 r = new Reader2(p);
        SweepSettings bs = base.sweep();
        DisplaySettings bd = base.display();

        double sg = bs.generator() instanceof SynthPlanner.SignalGen g ? g.sgoutMHz() : 10;
        double off = bs.generator() instanceof SynthPlanner.Tracking t ? t.offsetMHz() : 0;
        boolean rev = bs.generator() instanceof SynthPlanner.Tracking t && t.reverse();
        sg = r.num("gen.sgout", sg);
        off = r.num("gen.offset", off);
        rev = r.bool("gen.reverse", rev);
        boolean tracking = r.bool("gen.tracking", bs.generator() instanceof SynthPlanner.Tracking);
        MsaMode mode = r.enumeration("hw.mode", MsaMode.class, bs.mode());
        if (mode == MsaMode.REFLECTION) mode = MsaMode.VECTOR_TRANS;                  // not ported yet
        if ((mode != MsaMode.SA && !hasTg) || (mode.readsPhase() && !hasVna)) {
            r.warn.add("hw.mode=" + mode + " (not available on this hardware)");
            mode = MsaMode.SA;
        }
        // the network analyser modes track with no offset ([RestartTransmissionMode]); gen.* keep the SA values
        SynthPlanner.Generator gen = mode.networkAnalyser() ? new SynthPlanner.Tracking(0, false)
                : tracking ? new SynthPlanner.Tracking(off, rev) : new SynthPlanner.SignalGen(sg);

        int path = r.integer("hw.path", bs.path());
        if (path < 1 || path > filterCount) { r.warn.add("hw.path=" + path); path = bs.path(); }
        int band = r.integer("hw.band", bs.band());
        if (band < 0 || band > 3) { r.warn.add("hw.band=" + band); band = bs.band(); }
        VideoFilter video = r.enumeration("hw.video", VideoFilter.class, bs.video());
        HardwareSetup hw = new HardwareSetup(path, video, band, gen, mode);

        SweepSettings s = bs.withHardware(hw);
        s = r.apply(s, x -> x.withRange(new FrequencyRange(r.num("sweep.start", x.startMHz()), r.num("sweep.stop", x.stopMHz()))), "sweep.start/stop");
        s = r.apply(s, x -> x.withSteps(r.integer("sweep.steps", x.steps())), "sweep.steps");
        s = r.apply(s, x -> x.withScale(r.enumeration("sweep.scale", SweepSettings.Scale.class, x.scale())), "sweep.scale");
        s = r.apply(s, x -> x.withDirection(r.enumeration("sweep.direction", SweepSettings.Direction.class, x.direction())), "sweep.direction");
        s = r.apply(s, x -> x.withWait(r.integer("sweep.wait", x.waitMs())), "sweep.wait");
        s = r.apply(s, x -> x.withSamples(r.integer("sweep.samples", x.samples())), "sweep.samples");

        DisplaySettings d = bd;
        d = r.applyD(d, x -> x.withGraphData(r.integer("display.y1Data", x.y1Data()), r.integer("display.y2Data", x.y2Data())), "display.y*Data");
        d = r.applyD(d, x -> x.withY1Axis(new DisplaySettings.AxisRange(r.num("display.y1Min", x.y1Axis().min()), r.num("display.y1Max", x.y1Axis().max()))), "display.y1Min/Max");
        d = r.applyD(d, x -> x.withY2Axis(new DisplaySettings.AxisRange(r.num("display.y2Min", x.y2Axis().min()), r.num("display.y2Max", x.y2Axis().max()))), "display.y2Min/Max");
        d = r.applyD(d, x -> x.withAutoscale(r.bool("display.autoscale1", x.autoscaleY1()), r.bool("display.autoscale2", x.autoscaleY2())), "display.autoscale");
        d = r.applyD(d, x -> x.withTraceModes(r.integer("display.y1Mode", x.y1Mode()), r.integer("display.y2Mode", x.y2Mode())), "display.y*Mode");
        d = r.applyD(d, x -> x.withGridDivisions(r.integer("display.gridX", x.gridDivX()), r.integer("display.gridY", x.gridDivY())), "display.grid");
        d = r.applyD(d, x -> x.withRefreshEachScan(r.bool("display.refreshEachScan", x.refreshEachScan())), "display.refreshEachScan");
        d = validGraphData(d, mode, r.warn);

        VnaSettings bv = base.vna();
        VnaSettings vna = bv.withPlaneExt(r.num("vna.planeExtNs", bv.planeExtNs()))
                .withCalLevel(r.enumeration("vna.calLevel", TransmissionCal.Level.class, bv.calLevel()))
                .withThroughDelay(r.num("vna.throughDelayNs", bv.throughDelayNs()));

        Map<Markers.Id, Double> marks = new EnumMap<>(Markers.Id.class);
        for (Markers.Id id : Markers.Id.values()) {
            if (id == Markers.Id.HALT || p.getProperty("marker." + id.name()) == null) continue;
            double f = r.num("marker." + id.name(), Double.NaN);
            if (!Double.isNaN(f) || id.automatic()) marks.put(id, f);
        }
        Markers.Id sel = p.getProperty("markers.selected") == null ? null
                : r.enumeration("markers.selected", Markers.Id.class, null);

        MsaSettings ms = new MsaSettings(s, d, r.bool("sweep.autoSteps", base.autoSteps()),
                r.bool("sweep.autoWait", base.autoWait()), r.bool("sweep.autoSamples", base.autoSamples()), vna);
        SessionState st = new SessionState(ms, sg, off, rev, marks, sel, r.bool("markers.show", true),
                r.enumeration("markers.lrMode", Markers.LrMode.class, Markers.LrMode.INDEPENDENT),
                clampWidth(r.integer("trace.width1", 1)), clampWidth(r.integer("trace.width2", 1)),
                r.bool("display.sweepTime", false), window(r, p));
        return new Loaded(st, List.copyOf(r.warn));
    }

    private static int clampWidth(int w) { return Math.max(0, Math.min(3, w)); }

    /** Graph data the mode does not offer is replaced by the mode's default for that axis (Y2 primary). */
    static DisplaySettings validGraphData(DisplaySettings d, MsaMode mode, List<String> warn) {
        int y1 = d.y1Data(), y2 = d.y2Data();
        if (!valid(y1, mode)) {
            warn.add("display.y1Data=" + y1 + " (not in " + mode.title + ")");
            y1 = GraphData.defaultFor(mode, false).code;
        }
        if (!valid(y2, mode)) {
            warn.add("display.y2Data=" + y2 + " (not in " + mode.title + ")");
            y2 = GraphData.defaultFor(mode, true).code;
        }
        return d.withGraphData(y1, y2);
    }

    private static boolean valid(int code, MsaMode mode) {
        GraphData g = GraphData.ofCode(code);
        return (g != GraphData.NONE || code == GraphData.NONE.code) && g.validFor(mode);
    }

    /** Window geometry if all four numbers are there and the size is sensible, else null. */
    private static WindowGeometry window(Reader2 r, Properties p) {
        for (String k : new String[] {"window.x", "window.y", "window.width", "window.height"})
            if (p.getProperty(k) == null) return null;
        double x = r.num("window.x", Double.NaN), y = r.num("window.y", Double.NaN);
        double w = r.num("window.width", Double.NaN), h = r.num("window.height", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y) || !(w >= 300) || !(h >= 200)) return null;
        return new WindowGeometry(x, y, w, h, r.bool("window.maximized", false));
    }

    private static void put(Properties p, String k, double v) {
        p.setProperty(k, Double.isNaN(v) ? "auto" : String.format(Locale.ROOT, "%.9f", v).replaceAll("0+$", "").replaceAll("\\.$", ""));
    }

    /** Lenient typed access; problems go to {@code warn}. */
    private static final class Reader2 {
        final Properties p;
        final List<String> warn = new ArrayList<>();

        Reader2(Properties p) { this.p = p; }

        double num(String k, double dflt) {
            String v = p.getProperty(k);
            if (v == null) return dflt;
            if (v.trim().equals("auto")) return Double.NaN;
            try {
                double d = Double.parseDouble(v.trim());
                if (Double.isFinite(d)) return d;
            } catch (NumberFormatException ignored) {
                // reported below
            }
            warn.add(k + "=" + v);
            return dflt;
        }

        int integer(String k, int dflt) {
            String v = p.getProperty(k);
            if (v == null) return dflt;
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                warn.add(k + "=" + v);
                return dflt;
            }
        }

        boolean bool(String k, boolean dflt) {
            String v = p.getProperty(k);
            if (v == null) return dflt;
            if (v.trim().equalsIgnoreCase("true")) return true;
            if (v.trim().equalsIgnoreCase("false")) return false;
            warn.add(k + "=" + v);
            return dflt;
        }

        <E extends Enum<E>> E enumeration(String k, Class<E> c, E dflt) {
            String v = p.getProperty(k);
            if (v == null) return dflt;
            try {
                return Enum.valueOf(c, v.trim());
            } catch (IllegalArgumentException e) {
                warn.add(k + "=" + v);
                return dflt;
            }
        }

        SweepSettings apply(SweepSettings s, java.util.function.UnaryOperator<SweepSettings> f, String what) {
            try {
                return f.apply(s);
            } catch (IllegalArgumentException e) {
                warn.add(what + ": " + e.getMessage());
                return s;
            }
        }

        DisplaySettings applyD(DisplaySettings d, java.util.function.UnaryOperator<DisplaySettings> f, String what) {
            try {
                return f.apply(d);
            } catch (IllegalArgumentException e) {
                warn.add(what + ": " + e.getMessage());
                return d;
            }
        }
    }
}
