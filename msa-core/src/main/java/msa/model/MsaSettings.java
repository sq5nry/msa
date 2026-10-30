package msa.model;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

import msa.config.MsaConfig;
import msa.config.Preferences;
import msa.hw.SlimCommands.VideoFilter;
import msa.sweep.FrequencyRange;
import msa.sweep.HardwareSetup;
import msa.sweep.MsaMode;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;
import msa.trace.GraphData;
import msa.vna.TransmissionCal;

/**
 * The user-visible state of the spectrum analyser: sweep (with hardware setup) and display.
 * This is what a GUI binds to; the 700 globals of spectrumanalyzer.bas do not leak past it.
 *
 * <p>Persistence:
 * <ul>
 *   <li>MSA_Prefs/Prefs.txt, shared with the Liberty BASIC program. Only keys whose value changed are
 *       rewritten; everything else (fonts, margins, VNA settings, ...) stays byte-identical, and no new
 *       keys or contexts are added (the BASIC loader rejects unknown contexts).</li>
 *   <li>MSA_Prefs/java-prefs.properties for what the BASIC program does not save: samples and the
 *       alternating sweep direction, whether Steps/Sweep, Wait and Samples are calculated automatically
 *       ({@link msa.sweep.AutoSteps}, {@link msa.sweep.AutoWait}, {@link msa.sweep.AutoSamples}), the
 *       transmission "Reference To" choice and the delay of the calibration through.</li>
 * </ul>
 * Loading is tolerant: values that are invalid or belong to features not ported yet are replaced by
 * defaults and reported in {@link Loaded#warnings()}.
 */
public record MsaSettings(SweepSettings sweep, DisplaySettings display, boolean autoSteps, boolean autoWait,
                          boolean autoSamples, VnaSettings vna) {

    public static final String JAVA_PREFS = "java-prefs.properties";

    public MsaSettings {
        Objects.requireNonNull(sweep);
        Objects.requireNonNull(display);
        Objects.requireNonNull(vna);
    }

    /** Steps/Sweep and Wait entered by hand (automatic off), as the BASIC program always did. */
    public MsaSettings(SweepSettings sweep, DisplaySettings display) { this(sweep, display, false, false, false); }

    /** Automatic samples off. */
    public MsaSettings(SweepSettings sweep, DisplaySettings display, boolean autoSteps, boolean autoWait) {
        this(sweep, display, autoSteps, autoWait, false);
    }

    /** Default network-analyser settings. */
    public MsaSettings(SweepSettings sweep, DisplaySettings display, boolean autoSteps, boolean autoWait, boolean autoSamples) {
        this(sweep, display, autoSteps, autoWait, autoSamples, VnaSettings.defaults());
    }

    /** Defaults; Steps/Sweep and Wait are automatic unless java-prefs.properties says otherwise. */
    public static MsaSettings defaults() {
        return new MsaSettings(SweepSettings.defaults(), DisplaySettings.defaults(), true, true, true);
    }

    public MsaSettings withSweep(SweepSettings s) { return new MsaSettings(s, display, autoSteps, autoWait, autoSamples, vna); }
    public MsaSettings withDisplay(DisplaySettings d) { return new MsaSettings(sweep, d, autoSteps, autoWait, autoSamples, vna); }
    public MsaSettings withAutoSteps(boolean a) { return new MsaSettings(sweep, display, a, autoWait, autoSamples, vna); }
    public MsaSettings withAutoWait(boolean a) { return new MsaSettings(sweep, display, autoSteps, a, autoSamples, vna); }
    public MsaSettings withAutoSamples(boolean a) { return new MsaSettings(sweep, display, autoSteps, autoWait, a, vna); }
    public MsaSettings withVna(VnaSettings v) { return new MsaSettings(sweep, display, autoSteps, autoWait, autoSamples, v); }

    /** Result of loading: the settings, the underlying Prefs.txt content, and what had to be ignored. */
    public record Loaded(MsaSettings settings, Preferences prefs, List<String> warnings) {}

    // ------------------------------------------------------------------ load

    /** Reads MSA_Prefs/Prefs.txt and java-prefs.properties from an MSA_Info folder; missing files give defaults. */
    public static Loaded load(Path msaInfo, MsaConfig cfg) throws IOException {
        return loadFile(Preferences.defaultFile(msaInfo), cfg);
    }

    /** "File > Load Prefs": a Prefs.txt-format file anywhere, with java-prefs.properties next to it if present. */
    public static Loaded loadFile(Path file, MsaConfig cfg) throws IOException {
        Preferences p = Files.exists(file) ? Preferences.read(file) : Preferences.empty();
        Properties jp = new Properties();
        Path jf = file.resolveSibling(JAVA_PREFS);
        if (Files.exists(jf)) {
            try (Reader r = Files.newBufferedReader(jf, StandardCharsets.UTF_8)) {
                jp.load(r);
            }
        }
        return from(p, jp, cfg);
    }

    public static Loaded from(Preferences p, Properties javaPrefs, MsaConfig cfg) {
        List<String> warn = new ArrayList<>();
        MsaSettings d = defaults();
        Preferences.Context sw = p.context("Sweep").orElse(null);
        Preferences.Context tr = p.context("Trace").orElse(null);
        Preferences.Context gr = p.context("Grid").orElse(null);
        if (sw == null) warn.add("Prefs.txt has no Sweep context: using defaults");

        // ---- mode ([ChangeMode]: no TG means SA only, no VNA hardware means no vector modes)
        String modeText = get(sw, "msaMode").orElse("SA").trim();
        MsaMode mode = MsaMode.ofPrefs(modeText);
        if (mode == MsaMode.SA && !modeText.equalsIgnoreCase("SA")) warn.add("msaMode=" + modeText + " unknown: spectrum analyser");
        if (mode != MsaMode.SA && cfg.tgTop() <= 0) {
            warn.add("msaMode=" + modeText + " needs a tracking generator (config.txt TGtop): spectrum analyser");
            mode = MsaMode.SA;
        }
        if (mode.readsPhase() && !cfg.hasVna()) {
            warn.add("msaMode=" + modeText + " needs the VNA phase detector (config.txt hasVNA): spectrum analyser");
            mode = MsaMode.SA;
        }
        if (num(sw, "BaseFreq", 0, warn) != 0) warn.add("BaseFreq (frequency offset) is not supported yet: ignored");

        // ---- hardware
        HardwareSetup hd = d.sweep().hardware();
        int band = (int) num(sw, "FreqMode", hd.band(), warn);
        if (band < 0 || band > 3) { warn.add("FreqMode=" + band + " out of range: band 1"); band = 1; }
        int path = (int) num(sw, "Path", hd.path(), warn);
        if (path < 1 || path > cfg.filters().size()) {
            warn.add("Path=" + path + " but config.txt has " + cfg.filters().size() + " filters: path 1");
            path = 1;
        }
        VideoFilter video = get(sw, "VideoFilter").map(n -> videoByName(n, cfg, warn)).orElse(hd.video());
        boolean tg = num(sw, "IsTG", 1, warn) != 0 || mode.networkAnalyser();   // network analysis always tracks
        SynthPlanner.Generator gen = tg ? new SynthPlanner.Tracking(num(sw, "Offset", 0, warn), num(sw, "NormRev", 0, warn) != 0)
                                        : new SynthPlanner.SignalGen(num(sw, "SGPreset", 10, warn));
        HardwareSetup hw = new HardwareSetup(path, video, band, gen, mode);

        // ---- network analyser
        VnaSettings vd = VnaSettings.defaults();
        String[] jig = get(sw, "S21Jig").map(v -> v.trim().split("\\s+")).orElse(new String[0]);
        String[] bridge = get(sw, "S11Bridge").map(v -> v.trim().split("\\s+")).orElse(new String[0]);
        String attach = jig.length > 0 && jig[0].equalsIgnoreCase("Shunt") ? "Shunt" : "Series";
        VnaSettings vna = vd.withPlaneExt(num(sw, "PlaneAdj", 0, warn))
                .withS21Jig(attach, word(jig, 1, 50, "S21Jig", warn), word(jig, 2, 0, "S21Jig", warn))
                .withS11Bridge(word(bridge, 0, 50, "S11Bridge", warn), word(bridge, 1, 50, "S11Bridge", warn),
                        bridge.length > 2 ? bridge[2] : "Reflect")
                .withCalLevel(calLevel(javaPrefs.getProperty("vna.calLevel"), warn))
                .withThroughDelay(javaNum(javaPrefs, "vna.throughDelayNs", 0, warn));

        // ---- sweep
        SweepSettings ds = d.sweep();
        FrequencyRange range = ds.range();
        Optional<double[]> x = pair(sw, "MinMaxXAxis", warn);
        if (x.isPresent()) {
            double a = Math.min(x.get()[0], x.get()[1]), b = Math.max(x.get()[0], x.get()[1]);
            range = new FrequencyRange(a, b);
        }
        double[] lin = triple(sw, "IsLinear", new double[] {1, 1, 1}, warn);
        SweepSettings.Scale scale = lin[0] != 0 ? SweepSettings.Scale.LINEAR : SweepSettings.Scale.LOG;
        if (scale == SweepSettings.Scale.LOG && range.startMHz() <= 0) {
            warn.add("log sweep with start <= 0 MHz: linear used");
            scale = SweepSettings.Scale.LINEAR;
        }
        int steps = (int) num(sw, "NumSteps", ds.steps(), warn);
        if (steps < 1) { warn.add("NumSteps=" + steps + ": " + ds.steps() + " used"); steps = ds.steps(); }
        SweepSettings.Direction dir = num(sw, "SweepDir", 1, warn) < 0 ? SweepSettings.Direction.RIGHT_TO_LEFT
                                                                    : SweepSettings.Direction.LEFT_TO_RIGHT;
        if ("alternate".equalsIgnoreCase(javaPrefs.getProperty("direction"))) dir = SweepSettings.Direction.ALTERNATE;
        int wait = (int) Math.round(num(sw, "Wait", ds.waitMs(), warn));
        int samples = ds.samples();
        try {
            samples = Integer.parseInt(javaPrefs.getProperty("samples", String.valueOf(samples)).trim());
            if (samples < 1 || samples > 255) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            warn.add("java-prefs samples invalid: 1 used");
            samples = 1;
        }
        SweepSettings sweep = new SweepSettings(range, steps, scale, dir, Math.max(0, wait), samples, hw);

        // ---- display
        DisplaySettings dd = d.display();
        double[] gd = pairOr(sw, "GraphData", new double[] {dd.y1Data(), dd.y2Data()}, warn);
        int primary = (int) num(gr, "PrimaryAxis", 2, warn) == 1 ? 1 : 2;
        for (int a = 0; a < 2; a++) {
            GraphData g = GraphData.ofCode((int) gd[a]);
            boolean unknown = g == GraphData.NONE && (int) gd[a] != GraphData.NONE.code;
            if (unknown || !g.validFor(mode)) {
                GraphData def = GraphData.defaultFor(mode, a + 1 == primary);
                warn.add("GraphData " + (int) gd[a] + " is not available in " + mode.title + ": " + def.axisTitle + " used");
                gd[a] = def.code;
            }
        }
        double[] as = pairOr(sw, "Autoscale", new double[] {0, 0}, warn);
        DisplaySettings display = new DisplaySettings((int) gd[0], (int) gd[1],
                axis(sw, "MinMaxY1Axis", dd.y1Axis(), warn), axis(sw, "MinMaxY2Axis", dd.y2Axis(), warn),
                as[0] != 0, as[1] != 0, lin[1] != 0, lin[2] != 0,
                num(tr, "RefreshEachScan", 1, warn) != 0,
                (int) pairOr(gr, "GridDivisions", new double[] {dd.gridDivX(), dd.gridDivY()}, warn)[0],
                (int) pairOr(gr, "GridDivisions", new double[] {dd.gridDivX(), dd.gridDivY()}, warn)[1],
                rgb(gr, "BackColor", dd.background(), warn), rgb(gr, "GridLineColor", dd.gridLine(), warn),
                traceColor(tr, 0, dd.trace1(), warn), traceColor(tr, 1, dd.trace2(), warn),
                traceMode(tr, 0, dd.y1Mode(), warn), traceMode(tr, 1, dd.y2Mode(), warn));

        boolean autoSteps = !"false".equalsIgnoreCase(javaPrefs.getProperty("autoSteps", "true").trim());
        boolean autoWait = !"false".equalsIgnoreCase(javaPrefs.getProperty("autoWait", "true").trim());
        boolean autoSamples = !"false".equalsIgnoreCase(javaPrefs.getProperty("autoSamples", "true").trim());
        return new Loaded(new MsaSettings(sweep, display, autoSteps, autoWait, autoSamples, vna), p, List.copyOf(warn));
    }

    private static double word(String[] w, int i, double dflt, String key, List<String> warn) {
        if (w.length <= i) return dflt;
        try {
            return Double.parseDouble(w[i]);
        } catch (NumberFormatException e) {
            warn.add(key + " value \"" + w[i] + "\" is not a number: " + fmt(dflt) + " used");
            return dflt;
        }
    }

    private static double javaNum(Properties p, String key, double dflt, List<String> warn) {
        String v = p.getProperty(key);
        if (v == null) return dflt;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            warn.add("java-prefs " + key + " invalid: " + fmt(dflt) + " used");
            return dflt;
        }
    }

    private static TransmissionCal.Level calLevel(String v, List<String> warn) {
        if (v == null) return TransmissionCal.Level.NONE;
        try {
            return TransmissionCal.Level.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warn.add("java-prefs vna.calLevel=" + v + " unknown: no reference");
            return TransmissionCal.Level.NONE;
        }
    }

    // ------------------------------------------------------------------ save

    /**
     * Writes these settings into {@code prefs}, touching only keys whose meaning differs from
     * {@code original} (the settings that were loaded from it), so unchanged values keep their exact text.
     */
    public void writeTo(Preferences prefs, MsaSettings original, MsaConfig cfg) {
        SweepSettings s = sweep, o = original.sweep;
        Preferences.Context sw = prefs.contextOrCreate("Sweep");
        if (s.band() != o.band()) sw.set("FreqMode", String.valueOf(s.band()));
        if (s.waitMs() != o.waitMs()) sw.set("Wait", String.valueOf(s.waitMs()));
        if (s.path() != o.path()) sw.set("Path", String.valueOf(s.path()));
        if (s.video() != o.video()) sw.set("VideoFilter", videoName(s.video(), cfg));
        if (!s.generator().equals(o.generator())) {
            switch (s.generator()) {
                case SynthPlanner.Tracking t -> {
                    sw.set("IsTG", "1");
                    sw.set("Offset", fmt(t.offsetMHz()));
                    if (sw.get("NormRev").isPresent() || t.reverse()) sw.set("NormRev", bit(t.reverse()));
                }
                case SynthPlanner.SignalGen g -> { sw.set("IsTG", "0"); sw.set("SGPreset", fmt(g.sgoutMHz())); }
            }
        }
        if (!s.range().equals(o.range())) sw.set("MinMaxXAxis", fmt(s.startMHz()) + " " + fmt(s.stopMHz()));
        if (s.steps() != o.steps()) sw.set("NumSteps", String.valueOf(s.steps()));
        if (s.direction() != o.direction() && s.direction() != SweepSettings.Direction.ALTERNATE)
            sw.set("SweepDir", s.direction() == SweepSettings.Direction.RIGHT_TO_LEFT ? "-1" : "1");
        if (s.mode() != o.mode()) sw.set("msaMode", s.mode().prefsName);
        VnaSettings v = vna, ov = original.vna;
        if (v.planeExtNs() != ov.planeExtNs()) sw.set("PlaneAdj", fmt(v.planeExtNs()));
        if (!v.s21JigAttach().equals(ov.s21JigAttach()) || v.s21JigR0() != ov.s21JigR0() || v.s21ShuntDelay() != ov.s21ShuntDelay())
            sw.set("S21Jig", v.s21JigAttach() + " " + fmt(v.s21JigR0()) + " " + fmt(v.s21ShuntDelay()));
        if (v.s11BridgeR0() != ov.s11BridgeR0() || v.s11GraphR0() != ov.s11GraphR0() || !v.s11JigType().equals(ov.s11JigType()))
            sw.set("S11Bridge", fmt(v.s11BridgeR0()) + " " + fmt(v.s11GraphR0()) + " " + v.s11JigType());

        DisplaySettings d = display, od = original.display;
        if (s.scale() != o.scale() || d.y1Linear() != od.y1Linear() || d.y2Linear() != od.y2Linear())
            sw.set("IsLinear", bit(s.scale() == SweepSettings.Scale.LINEAR) + " " + bit(d.y1Linear()) + " " + bit(d.y2Linear()));
        if (d.y1Data() != od.y1Data() || d.y2Data() != od.y2Data()) sw.set("GraphData", d.y1Data() + " " + d.y2Data());
        if (d.autoscaleY1() != od.autoscaleY1() || d.autoscaleY2() != od.autoscaleY2())
            sw.set("Autoscale", bit(d.autoscaleY1()) + " " + bit(d.autoscaleY2()));
        if (!d.y1Axis().equals(od.y1Axis())) sw.set("MinMaxY1Axis", fmt(d.y1Axis().min()) + " " + fmt(d.y1Axis().max()));
        if (!d.y2Axis().equals(od.y2Axis())) sw.set("MinMaxY2Axis", fmt(d.y2Axis().min()) + " " + fmt(d.y2Axis().max()));

        if (d.refreshEachScan() != od.refreshEachScan() || !d.trace1().equals(od.trace1()) || !d.trace2().equals(od.trace2())
                || d.y1Mode() != od.y1Mode() || d.y2Mode() != od.y2Mode()) {
            Preferences.Context tr = prefs.contextOrCreate("Trace");
            if (d.y1Mode() != od.y1Mode() || d.y2Mode() != od.y2Mode()) tr.set("TraceModes", d.y1Mode() + "," + d.y2Mode());
            if (d.refreshEachScan() != od.refreshEachScan()) tr.set("RefreshEachScan", bit(d.refreshEachScan()));
            if (!d.trace1().equals(od.trace1()) || !d.trace2().equals(od.trace2()))
                tr.set("TraceColors", d.trace1() + ";;" + d.trace2());
        }
        if (d.gridDivX() != od.gridDivX() || d.gridDivY() != od.gridDivY() || !d.background().equals(od.background())
                || !d.gridLine().equals(od.gridLine())) {
            Preferences.Context gr = prefs.contextOrCreate("Grid");
            if (d.gridDivX() != od.gridDivX() || d.gridDivY() != od.gridDivY()) gr.set("GridDivisions", d.gridDivX() + " " + d.gridDivY());
            if (!d.background().equals(od.background())) gr.set("BackColor", d.background().toString());
            if (!d.gridLine().equals(od.gridLine())) gr.set("GridLineColor", d.gridLine().toString());
        }
    }

    /** Java-only values (java-prefs.properties). */
    public Properties javaPrefs() {
        Properties p = new Properties();
        p.setProperty("samples", String.valueOf(sweep.samples()));
        p.setProperty("direction", sweep.direction() == SweepSettings.Direction.ALTERNATE ? "alternate" : "normal");
        p.setProperty("autoSteps", String.valueOf(autoSteps));
        p.setProperty("autoWait", String.valueOf(autoWait));
        p.setProperty("autoSamples", String.valueOf(autoSamples));
        p.setProperty("vna.calLevel", vna.calLevel().name());
        p.setProperty("vna.throughDelayNs", fmt(vna.throughDelayNs()));
        return p;
    }

    /**
     * Saves to MSA_Info: Prefs.txt (only changed keys, previous file kept as Prefs.txt.bak) and
     * java-prefs.properties.
     *
     * @return the new baseline for the next save
     */
    public Loaded save(Path msaInfo, Loaded loadedFrom, MsaConfig cfg) throws IOException {
        return saveFile(Preferences.defaultFile(msaInfo), loadedFrom, cfg);
    }

    /** "File > Save Prefs" to any file; java-prefs.properties goes next to it. */
    public Loaded saveFile(Path file, Loaded loadedFrom, MsaConfig cfg) throws IOException {
        Preferences p = loadedFrom.prefs();
        writeTo(p, loadedFrom.settings(), cfg);
        Files.createDirectories(file.getParent());
        p.write(file);
        try (Writer w = Files.newBufferedWriter(file.resolveSibling(JAVA_PREFS), StandardCharsets.UTF_8)) {
            javaPrefs().store(w, "MSA Java port settings not stored in Prefs.txt");
        }
        return new Loaded(this, p, List.of());
    }

    // ------------------------------------------------------------------ helpers

    private static Optional<String> get(Preferences.Context c, String key) {
        return c == null ? Optional.empty() : c.get(key);
    }

    /** First number of the value (BASIC val()). */
    private static double num(Preferences.Context c, String key, double dflt, List<String> warn) {
        return get(c, key).map(v -> {
            try {
                return Double.parseDouble(v.trim().split("\\s+")[0]);
            } catch (NumberFormatException e) {
                warn.add(key + "=" + v + " is not a number: " + fmt(dflt) + " used");
                return dflt;
            }
        }).orElse(dflt);
    }

    private static double[] numbers(String v, int n) {
        String[] w = v.trim().split("[\\s,]+");
        if (w.length < n) throw new NumberFormatException();
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = Double.parseDouble(w[i]);
        return out;
    }

    private static Optional<double[]> pair(Preferences.Context c, String key, List<String> warn) {
        return get(c, key).flatMap(v -> {
            try {
                return Optional.of(numbers(v, 2));
            } catch (NumberFormatException e) {
                warn.add(key + "=" + v + " is invalid: ignored");
                return Optional.empty();
            }
        });
    }

    private static double[] pairOr(Preferences.Context c, String key, double[] dflt, List<String> warn) {
        return pair(c, key, warn).orElse(dflt);
    }

    private static double[] triple(Preferences.Context c, String key, double[] dflt, List<String> warn) {
        return get(c, key).map(v -> {
            try {
                return numbers(v, 3);
            } catch (NumberFormatException e) {
                warn.add(key + "=" + v + " is invalid: ignored");
                return dflt;
            }
        }).orElse(dflt);
    }

    private static DisplaySettings.AxisRange axis(Preferences.Context c, String key, DisplaySettings.AxisRange dflt, List<String> warn) {
        return pair(c, key, warn).map(a -> {
            try {
                return new DisplaySettings.AxisRange(a[0], a[1]);
            } catch (IllegalArgumentException e) {
                warn.add(key + " has max <= min: default used");
                return dflt;
            }
        }).orElse(dflt);
    }

    private static DisplaySettings.Rgb rgb(Preferences.Context c, String key, DisplaySettings.Rgb dflt, List<String> warn) {
        return get(c, key).map(v -> parseRgb(v, key, dflt, warn)).orElse(dflt);
    }

    private static DisplaySettings.Rgb traceColor(Preferences.Context c, int i, DisplaySettings.Rgb dflt, List<String> warn) {
        return get(c, "TraceColors").map(v -> {
            String[] parts = v.split(";;");
            return i < parts.length ? parseRgb(parts[i], "TraceColors", dflt, warn) : dflt;
        }).orElse(dflt);
    }

    /** TraceModes=Y1,Y2 (0..4). */
    private static int traceMode(Preferences.Context c, int i, int dflt, List<String> warn) {
        return get(c, "TraceModes").map(v -> {
            try {
                int m = (int) numbers(v, 2)[i];
                if (m < 0 || m > 4) throw new NumberFormatException();
                return m;
            } catch (NumberFormatException e) {
                warn.add("TraceModes=" + v + " is invalid: normal used");
                return dflt;
            }
        }).orElse(dflt);
    }

    private static DisplaySettings.Rgb parseRgb(String v, String key, DisplaySettings.Rgb dflt, List<String> warn) {
        try {
            return DisplaySettings.Rgb.parse(v);
        } catch (IllegalArgumentException e) {
            warn.add(key + "=" + v + " is not an RGB triple: default used");
            return dflt;
        }
    }

    /** VideoFilter= holds the name from config.txt's VideoFilters list; its position is the latch address. */
    private static VideoFilter videoByName(String name, MsaConfig cfg, List<String> warn) {
        List<MsaConfig.VideoFilterCaps> v = cfg.videoFilters();
        for (int i = 0; i < v.size() && i < 4; i++)
            if (v.get(i).name().equalsIgnoreCase(name.trim())) return VideoFilter.values()[i];
        for (VideoFilter f : VideoFilter.values())
            if (f.name().equalsIgnoreCase(name.trim())) return f;
        warn.add("VideoFilter=" + name + " unknown: Wide used");
        return VideoFilter.WIDE;
    }

    /** The video filter's name as config.txt and Prefs.txt spell it ("Wide", "Mid", ...). */
    public static String videoName(VideoFilter f, MsaConfig cfg) {
        List<MsaConfig.VideoFilterCaps> v = cfg.videoFilters();
        if (f.ordinal() < v.size()) return v.get(f.ordinal()).name();
        Function<String, String> cap = s -> s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
        return f == VideoFilter.XNARROW ? "XNarrow" : cap.apply(f.name());
    }

    private static String bit(boolean b) { return b ? "1" : "0"; }

    /** Number as Liberty BASIC prints it for ordinary values: no trailing ".0", no exponent. */
    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
