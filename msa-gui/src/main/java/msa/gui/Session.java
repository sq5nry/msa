package msa.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

import msa.config.MsaConfig;
import msa.config.PathCalibration;
import msa.hw.UsbMsa;
import msa.hw.usb.LibUsbTransport;
import msa.model.DisplaySettings;
import msa.model.MsaSettings;
import msa.replay.DemoSignals;
import msa.replay.ReplayTransport;
import msa.sweep.AutoSamples;
import msa.sweep.AutoSteps;
import msa.sweep.AutoWait;
import msa.sweep.MsaMode;
import msa.sweep.SweepEngine;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;
import msa.model.VnaSettings;
import msa.trace.GraphData;
import msa.trace.SweepData;
import msa.vna.LineCal;
import msa.vna.TransmissionCal;
import java.nio.charset.StandardCharsets;

/**
 * Everything the main window works on, without JavaFX: config.txt, the settings loaded from Prefs.txt (and
 * the baseline they were loaded from, so saving only rewrites changed keys), and the sweep engine.
 * The BASIC program keeps the last signal-generator frequency and TG offset while the other mode is
 * active (sgout, offset, normrev); so does this class.
 */
public final class Session implements AutoCloseable {

    /** Where measurements come from. */
    public sealed interface Source permits Usb, Replay, Demo {
        String label();
    }
    public record Usb() implements Source { public String label() { return "MSA (USB)"; } }
    public record Replay(Path file) implements Source { public String label() { return "replay " + file.getFileName(); } }
    public record Demo() implements Source { public String label() { return "demo signal"; } }

    private final Path msaInfo;
    private volatile MsaConfig cfg;
    private final Source source;
    private final SweepEngine engine;
    private final List<String> warnings = new ArrayList<>();
    /** Written by the sweep thread (calibration loader) and read by the UI thread. */
    private final Map<Integer, Boolean> calibrated = new java.util.concurrent.ConcurrentHashMap<>();

    private final msa.config.MsaInfo.AppConfig appConfig;
    private SessionState restored;
    private Properties lastSaved;
    private MsaSettings.Loaded baseline;
    private volatile MsaSettings settings;
    private final TransmissionCal transmissionCal;
    private final msa.vna.ReflectionCal reflectionCal;
    private volatile DemoSignals.Dut demoDut = DemoSignals.Dut.BANDPASS;
    private double sgoutMHz = 10;
    private double tgOffsetMHz = 0;
    private boolean tgReverse = false;

    public Session(Path msaInfo, Source source) throws IOException {
        this.msaInfo = Objects.requireNonNull(msaInfo);
        this.source = Objects.requireNonNull(source);
        this.cfg = MsaConfig.read(msaInfo.resolve("config.txt"));
        this.baseline = MsaSettings.load(msaInfo, cfg);
        warnings.addAll(baseline.warnings());
        this.settings = baseline.settings();
        rememberGenerator(settings.sweep());
        this.appConfig = msa.config.MsaInfo.AppConfig.load(msaInfo);
        if (appConfig.rememberSettings()) {
            try {
                SessionState.load(SessionState.file(msaInfo), settings, cfg.filters().size(), cfg.tgTop() > 0, cfg.hasVna())
                        .ifPresent(l -> {
                    restored = l.state();
                    settings = restored.settings();
                    sgoutMHz = restored.sgoutMHz();
                    tgOffsetMHz = restored.tgOffsetMHz();
                    tgReverse = restored.tgReverse();
                    if (!l.warnings().isEmpty())
                        warnings.add(SessionState.FILE + ": ignored " + String.join(", ", l.warnings()));
                });
            } catch (IOException | RuntimeException e) {
                warnings.add(SessionState.FILE + " could not be read (" + e.getMessage() + "): starting from Prefs.txt");
            }
        }
        this.transmissionCal = new TransmissionCal(msaInfo.resolve("OperatingCal"));
        if (transmissionCal.loadProblem() != null) warnings.add(transmissionCal.loadProblem());
        this.reflectionCal = new msa.vna.ReflectionCal(msaInfo.resolve("OperatingCal"));
        if (reflectionCal.loadProblem() != null) warnings.add(reflectionCal.loadProblem());
        this.engine = new SweepEngine(cfg, this::loadCalibration, deviceFactory());
    }

    private Supplier<UsbMsa> deviceFactory() {
        return () -> {
            try {
                return switch (source) {
                    case Usb u -> new UsbMsa(LibUsbTransport.open());
                    case Replay r -> new UsbMsa(ReplayTransport.open(r.file(), cfg));
                    case Demo d -> new UsbMsa(msa.replay.DemoSignals.create(cfg, this::sweepingMode, () -> demoDut,
                            this::demoCalibration));
                };
            } catch (IOException e) {
                throw new msa.hw.MsaException(source.label() + ": " + e.getMessage(), e);
            } catch (msa.hw.MsaException e) {
                throw e;
            } catch (RuntimeException | LinkageError e) {
                // anything else would end the sweep thread; the engine handles MsaException (retry, message)
                throw new msa.hw.MsaException(source.label() + ": " + e, e);
            }
        };
    }

    private final Map<Integer, java.util.Optional<PathCalibration>> demoCals = new java.util.concurrent.ConcurrentHashMap<>();

    /** The mode of the sweep the engine runs (an autotest sweeps with its own settings), for the demo signal. */
    private MsaMode sweepingMode() {
        SweepSettings s = engine == null ? null : engine.settings();
        return s != null ? s.mode() : sweep().mode();
    }

    /** The demo turns levels into ADC readings through the real path calibration, so they read back right. */
    private PathCalibration demoCalibration(int path) {
        return demoCals.computeIfAbsent(path, p -> {
            try {
                return java.util.Optional.of(PathCalibration.load(msaInfo, p));
            } catch (IOException | IllegalArgumentException e) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }

    private PathCalibration loadCalibration(int path) {
        try {
            PathCalibration c = PathCalibration.load(msaInfo, path);
            calibrated.put(path, true);
            return c;
        } catch (IOException | IllegalArgumentException e) {
            calibrated.put(path, false);
            return null;
        }
    }

    /** True if MSA_CalPath{path}.txt loads; dBm values are meaningless without it. */
    public boolean hasCalibration(int path) {
        return calibrated.computeIfAbsent(path, p -> {
            try {
                PathCalibration.load(msaInfo, p);
                return true;
            } catch (IOException | IllegalArgumentException e) {
                return false;
            }
        });
    }

    public Path msaInfo() { return msaInfo; }
    public MsaConfig config() { return cfg; }
    public Source source() { return source; }
    public SweepEngine engine() { return engine; }
    /** Problems found while loading Prefs.txt (shown once at start). */
    public List<String> warnings() { return List.copyOf(warnings); }

    public MsaSettings settings() { return settings; }
    public SweepSettings sweep() { return settings.sweep(); }
    public DisplaySettings display() { return settings.display(); }

    /** True if the hardware has a tracking generator (config.txt TGtop > 0), so the Mode menu is shown. */
    public boolean hasTrackingGenerator() { return cfg.tgTop() > 0; }

    /** BASIC gentrk. */
    public boolean trackingMode() { return sweep().generator() instanceof SynthPlanner.Tracking; }

    public void start() {
        settings = settings.withSweep(withAutoSteps(sweep()));
        engine.start(sweep());
    }

    /**
     * New sweep settings: remembered and handed to the engine (which restarts only if needed). With
     * automatic steps the step count is recalculated from the span and the path's RBW first.
     */
    public void applySweep(SweepSettings s) {
        s = withAutoSteps(s);
        settings = settings.withSweep(s);
        rememberGenerator(s);
        engine.apply(s);
    }

    public void applyDisplay(DisplaySettings d) { settings = settings.withDisplay(d); }

    public boolean autoSteps() { return settings.autoSteps(); }

    /** Turns automatic Steps/Sweep on (recalculating now) or off (keeping the current count). */
    public void setAutoSteps(boolean on) {
        settings = settings.withAutoSteps(on);
        if (on) applySweep(sweep());
    }

    /** The automatic step count for these settings (whether or not automatic steps are on). */
    public int autoStepsFor(SweepSettings s) {
        return AutoSteps.of(s.range(), s.scale(), cfg.filter(s.path()).bwKHz(),
                s.generator() instanceof SynthPlanner.Tracking);
    }

    public boolean autoWait() { return settings.autoWait(); }

    /** Turns automatic Wait on (recalculating now) or off (keeping the current value). */
    public void setAutoWait(boolean on) {
        settings = settings.withAutoWait(on);
        if (on) applySweep(sweep());
    }

    /** The automatic wait for these settings' video filter and RBW (whether or not automatic wait is on). */
    public int autoWaitFor(SweepSettings s) { return AutoWait.of(s, cfg); }

    public boolean autoSamples() { return settings.autoSamples(); }

    /** Turns automatic Samples on (recalculating now) or off (keeping the current value). */
    public void setAutoSamples(boolean on) {
        settings = settings.withAutoSamples(on);
        if (on) applySweep(sweep());
    }

    /** The automatic ADC averaging for these settings' video filter. */
    public int autoSamplesFor(SweepSettings s) { return AutoSamples.of(s, cfg); }

    /** Applies whichever of automatic steps, wait and samples are on. */
    private SweepSettings withAutoSteps(SweepSettings s) {
        if (settings.autoSteps()) s = s.withSteps(autoStepsFor(s));
        if (settings.autoWait()) s = s.withWait(autoWaitFor(s));
        if (settings.autoSamples()) s = s.withSamples(autoSamplesFor(s));
        return s;
    }

    /** The SA modes' SG frequency and TG offset are kept while a network-analyser mode (TG at offset 0) runs. */
    private void rememberGenerator(SweepSettings s) {
        if (s.mode() != MsaMode.SA) return;
        switch (s.generator()) {
            case SynthPlanner.Tracking t -> { tgOffsetMHz = t.offsetMHz(); tgReverse = t.reverse(); }
            case SynthPlanner.SignalGen g -> sgoutMHz = g.sgoutMHz();
        }
    }

    /** Spectrum analyser, SNA/VNA transmission or reflection. */
    public MsaMode mode() { return sweep().mode(); }

    /** True if the MSA has the VNA phase detector (config.txt hasVNA) and a tracking generator. */
    public boolean hasVna() { return cfg.hasVna() && hasTrackingGenerator(); }

    /** The transmission mode of this hardware: vector with the phase detector, scalar without. */
    public MsaMode transmissionMode() { return cfg.hasVna() ? MsaMode.VECTOR_TRANS : MsaMode.SCALAR_TRANS; }

    /**
     * Mode menu ([RestartSAmodes], [RestartTransmissionMode]): plain spectrum analyser (TG used as signal
     * generator at sgout), with tracking generator (offset, normal/reverse), or transmission (TG tracking
     * with no offset). Like the BASIC program this also resets the graph data to the mode's defaults
     * (SetDefaultGraphData: primary axis Y2 = dBm or dB -100..0, Y1 = phase -180..180 in VNA transmission,
     * else off; no autoscale) and restarts.
     *
     * @return true if the mode actually changed
     */
    public boolean setMode(MsaMode mode, boolean tracking) {
        boolean changed = mode != mode() || (mode == MsaMode.SA && tracking != trackingMode());
        SynthPlanner.Generator g = mode.networkAnalyser() ? new SynthPlanner.Tracking(0, false)
                : tracking ? new SynthPlanner.Tracking(tgOffsetMHz, tgReverse) : new SynthPlanner.SignalGen(sgoutMHz);
        DisplaySettings def = DisplaySettings.defaults();
        GraphData y1 = GraphData.defaultFor(mode, false), y2 = GraphData.defaultFor(mode, true);
        DisplaySettings d = display().withGraphData(y1.code, y2.code)
                .withY1Axis(y1 == GraphData.NONE ? def.y1Axis() : new DisplaySettings.AxisRange(-180, 180))
                .withY2Axis(new DisplaySettings.AxisRange(-100, 0)).withAutoscale(false, false)
                .withTraceModes(def.y1Mode(), def.y2Mode());
        settings = settings.withDisplay(d);
        if (source instanceof Demo && mode.networkAnalyser() && demoDut.onePort != (mode == MsaMode.REFLECTION))
            demoDut = mode == MsaMode.REFLECTION ? DemoSignals.Dut.SERIES_RLC : DemoSignals.Dut.BANDPASS;
        applySweep(sweep().withGenerator(g).withMode(mode));
        engine.restart();
        return changed;
    }

    /** {@link #setMode} for the two spectrum-analyser modes. */
    public boolean setTrackingMode(boolean tracking) { return setMode(MsaMode.SA, tracking); }

    // ---------------------------------------------------------------- network analyser

    public VnaSettings vna() { return settings.vna(); }

    /** The band and base transmission calibrations. */
    public TransmissionCal transmissionCal() { return transmissionCal; }

    /** Sweep window "Plane Extension" (ns). */
    public void setPlaneExt(double ns) { settings = settings.withVna(vna().withPlaneExt(ns)); }

    /** Operating Cal &gt; Reference To. */
    public void setCalLevel(TransmissionCal.Level l) { settings = settings.withVna(vna().withCalLevel(l)); }

    /** Delay of the calibration through connection (ns). */
    public void setThroughDelay(double ns) { settings = settings.withVna(vna().withThroughDelay(ns)); }

    private record CalKey(SweepSettings s, VnaSettings v, LineCal band, LineCal base) {}
    private CalKey lastCalKey;
    private TransmissionCal.Installed lastInstalled;

    /** What reference applies to a sweep with these settings (InstallSelectedLineCal); remembered for redraws. */
    public synchronized TransmissionCal.Installed installedCal(SweepSettings s) {
        if (!s.mode().transmission()) return TransmissionCal.Installed.NONE;
        VnaSettings v = vna();
        CalKey k = new CalKey(s, v, transmissionCal.band(), transmissionCal.base());
        if (!k.equals(lastCalKey)) {
            lastInstalled = transmissionCal.install(v.calLevel(), s, v.s21JigAttach(), v.s21JigR0(), s.frequencies());
            lastCalKey = k;
        }
        return lastInstalled;
    }

    /** Reference and plane extension for a sweep's data; in reflection also the OSL calibration and R0s. */
    public SweepData.Correction correctionFor(SweepSettings s) {
        if (s.mode() == MsaMode.REFLECTION) {
            VnaSettings v = vna();
            msa.vna.ReflectionCal.Installed in = installedReflectionCal(s);
            boolean extend = v.reflectFixture() != msa.vna.OslCal.Fixture.SERIES;
            return new SweepData.Correction(null, null, v.planeExtNs(),
                    new SweepData.Reflection(in.points(), v.s11BridgeR0(), v.s11GraphR0(), extend));
        }
        if (!s.mode().transmission()) return SweepData.Correction.NONE;
        TransmissionCal.Installed in = installedCal(s);
        boolean vector = s.mode() == MsaMode.VECTOR_TRANS;
        return new SweepData.Correction(in.db(), vector ? in.deg() : null, vector ? vna().planeExtNs() : 0);
    }

    // ---------------------------------------------------------------- reflection

    /** The band and base reflection (OSL) calibrations. */
    public msa.vna.ReflectionCal reflectionCal() { return reflectionCal; }

    private record ReflKey(SweepSettings s, VnaSettings v, msa.vna.OslCal band, msa.vna.OslCal base) {}
    private ReflKey lastReflKey;
    private msa.vna.ReflectionCal.Installed lastReflInstalled;

    /** What OSL calibration applies to a sweep with these settings (InstallSelectedOSLCal). */
    public synchronized msa.vna.ReflectionCal.Installed installedReflectionCal(SweepSettings s) {
        if (s.mode() != MsaMode.REFLECTION) return msa.vna.ReflectionCal.Installed.NONE;
        VnaSettings v = vna();
        ReflKey k = new ReflKey(s, v, reflectionCal.band(), reflectionCal.base());
        if (!k.equals(lastReflKey)) {
            lastReflInstalled = reflectionCal.install(v.calLevel(), s, v.reflectFixture(), v.s11BridgeR0(), s.frequencies());
            lastReflKey = k;
        }
        return lastReflInstalled;
    }

    /** The level of the calibration that applies in the current mode (none outside the network-analyser modes). */
    public TransmissionCal.Level appliedCalLevel(SweepSettings s) {
        if (s.mode() == MsaMode.REFLECTION) return installedReflectionCal(s).level();
        return installedCal(s).level();
    }

    /** Sweep window R0 for the reflection graphs. */
    public void setGraphR0(double r0) {
        if (!(r0 > 0)) throw new IllegalArgumentException("R0: must be above 0 ohms");
        settings = settings.withVna(vna().withGraphR0(r0));
    }

    /**
     * [OSLDone] / [ProcessOSLBandCal]: the measured standards become the band OSL calibration of this sweep,
     * the fixture and R0 are remembered, and Reference To becomes "Band Sweep Cal".
     *
     * @param open  raw readings with the open attached, or null; likewise short and load
     * @throws IllegalArgumentException as {@link msa.vna.OslCal#compute}
     */
    public msa.vna.OslCal performOslCal(SweepSettings s, double[] freq, msa.vna.OslCal.Reading open,
                                        msa.vna.OslCal.Reading shortR, msa.vna.OslCal.Reading load,
                                        msa.vna.CalStandards stds, msa.vna.OslCal.Fixture fixture, double r0,
                                        double shuntDelayNs) {
        msa.vna.OslCal c = msa.vna.OslCal.compute(freq, open, shortR, load, stds, fixture, r0, shuntDelayNs,
                s.scale() == SweepSettings.Scale.LINEAR, s.path());
        settings = settings.withVna(vna().withReflectFixture(fixture, r0, shuntDelayNs));
        reflectionCal.setBand(c);
        setCalLevel(TransmissionCal.Level.BAND);
        return c;
    }

    /**
     * [PerformOSLCalUpdate]: the reference standard measured again replaces the band calibration's reference
     * (the error coefficients stay), partly correcting drift since the calibration.
     */
    public msa.vna.OslCal updateOslReference(msa.vna.OslCal.Reading again) {
        msa.vna.OslCal b = reflectionCal.band();
        if (b == null) throw new IllegalStateException("no band calibration");
        msa.vna.OslCal u = new msa.vna.OslCal(b.freq(), again.db(), again.deg(), b.a(), b.b(), b.c(), b.refType(),
                b.timeStamp(), b.linear(), b.path(), b.fixture(), b.r0(), b.shuntDelayNs());
        reflectionCal.setBand(u);
        return u;
    }

    /** The calibration standard sets in MSA_Info/OperatingCal/CalSets. */
    public java.util.List<msa.vna.CalStandards> calStandards() {
        return msa.vna.CalStandards.list(msaInfo.resolve("OperatingCal").resolve(msa.vna.CalStandards.FOLDER));
    }

    /** Where a new calibration standard set is saved. */
    public Path calSetsFolder() { return msaInfo.resolve("OperatingCal").resolve(msa.vna.CalStandards.FOLDER); }

    /**
     * [BandLineCal]: a complete sweep taken with the through connected becomes the band calibration, and
     * "Reference To" becomes "Band Sweep Cal".
     *
     * @throws IllegalArgumentException if the sweep is not complete or has no calibrated values
     */
    public LineCal performBandCal(SweepData raw, SweepSettings s) {
        VnaSettings v = vna();
        LineCal c = LineCal.fromSweep(raw, s, s.mode() == MsaMode.VECTOR_TRANS, v.throughDelayNs(), v.s21JigAttach(), v.s21JigR0());
        transmissionCal.setBand(c);
        setCalLevel(TransmissionCal.Level.BAND);
        return c;
    }

    /**
     * Setup &gt; PDM Calibration "Save New Value": writes invdeg into config.txt (the previous file is kept as
     * config.txt.bak) and uses it from the next step on.
     */
    public void savePdmInversion(double invDeg) throws IOException {
        Path f = msaInfo.resolve("config.txt");
        String text = Files.readString(f, StandardCharsets.ISO_8859_1);
        String value = fmtConfig(invDeg);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?im)^(\\s*invdeg\\s*=)[^\\r\\n]*").matcher(text);
        String updated;
        if (m.find()) {
            updated = text.substring(0, m.start()) + m.group(1) + value + text.substring(m.end());
        } else {
            String nl = text.contains("\r\n") ? "\r\n" : text.contains("\r") ? "\r" : "\n";
            updated = text + (text.endsWith(nl) || text.isEmpty() ? "" : nl) + "invdeg=" + value + nl;
        }
        Files.copy(f, f.resolveSibling("config.txt.bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(f, updated, StandardCharsets.ISO_8859_1);
        cfg = MsaConfig.read(f);
        engine.setPdmInversion(invDeg);
    }

    private static String fmtConfig(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** What the demo's simulated test fixture holds in the transmission modes. */
    public DemoSignals.Dut demoDut() { return demoDut; }
    public void setDemoDut(DemoSignals.Dut d) { demoDut = d; }

    /** Saves to MSA_Info/MSA_Prefs/Prefs.txt (only changed keys) and becomes the new baseline. */
    public void savePrefs() throws IOException {
        baseline = settings.save(msaInfo, baseline, cfg);
    }

    /** "Save Prefs" to another file (the baseline for Prefs.txt stays as it is). */
    public void savePrefsAs(Path file) throws IOException {
        if (file.toAbsolutePath().normalize().equals(
                msaInfo.resolve("MSA_Prefs").resolve("Prefs.txt").toAbsolutePath().normalize())) {
            savePrefs();
            return;
        }
        settings.saveFile(file, baseline, cfg);
    }

    /** "Load Prefs": replaces the settings, restarts the sweep; returns the loader's warnings. */
    public List<String> loadPrefs(Path file) throws IOException {
        MsaSettings.Loaded l = MsaSettings.loadFile(file, cfg);
        settings = l.settings();
        settings = settings.withSweep(withAutoSteps(sweep()));
        rememberGenerator(sweep());
        engine.apply(sweep());
        engine.restart();
        return l.warnings();
    }

    /** The state restored from the last run, if rememberSettings is on and there was one. */
    public java.util.Optional<SessionState> restored() { return java.util.Optional.ofNullable(restored); }

    /** msa-java.properties: rememberSettings (default true). */
    public boolean remembersSettings() { return appConfig.rememberSettings(); }

    /**
     * Saves the window state for the next start (only with rememberSettings, and only if it changed).
     * Called periodically and at exit.
     */
    public void saveState(GraphState g, boolean displaySweepTime, SessionState.WindowGeometry window) throws IOException {
        if (!appConfig.rememberSettings()) return;
        SessionState st = new SessionState(settings, sgoutMHz, tgOffsetMHz, tgReverse, g.markerFrequencies(),
                g.selected().orElse(null), g.showMarkers(), g.markers.lrMode(), g.traceWidth(1), g.traceWidth(2),
                displaySweepTime, window);
        Properties p = st.toProperties();
        if (p.equals(lastSaved)) return;
        st.save(SessionState.file(msaInfo));
        lastSaved = p;
    }

    @Override
    public void close() { engine.close(); }

    /** The project's msa-info folder, or --msa-info ({@link msa.config.MsaInfo#locate}). */
    public static Path findMsaInfo(String option) { return msa.config.MsaInfo.locate(option); }
}
