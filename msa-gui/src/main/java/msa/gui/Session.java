package msa.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import msa.config.MsaConfig;
import msa.config.PathCalibration;
import msa.hw.UsbMsa;
import msa.hw.sim.SimulatedTransport;
import msa.hw.usb.LibUsbTransport;
import msa.model.DisplaySettings;
import msa.model.MsaSettings;
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
    private volatile DemoDut demoDut = DemoDut.BANDPASS;
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
        this.engine = new SweepEngine(cfg, this::loadCalibration, deviceFactory());
    }

    private Supplier<UsbMsa> deviceFactory() {
        return () -> {
            try {
                return switch (source) {
                    case Usb u -> new UsbMsa(LibUsbTransport.open());
                    case Replay r -> new UsbMsa(ReplayTransport.open(r.file(), cfg));
                    case Demo d -> new UsbMsa(demo(cfg, () -> sweep().mode(), () -> demoDut, this::demoCalibration));
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

    /** Reference and plane extension for a sweep's data (no correction outside transmission). */
    public SweepData.Correction correctionFor(SweepSettings s) {
        if (!s.mode().transmission()) return SweepData.Correction.NONE;
        TransmissionCal.Installed in = installedCal(s);
        boolean vector = s.mode() == MsaMode.VECTOR_TRANS;
        return new SweepData.Correction(in.db(), vector ? in.deg() : null, vector ? vna().planeExtNs() : 0);
    }

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

    /** What the demo source's simulated test fixture holds in the transmission modes. */
    public enum DemoDut {
        THROUGH("Through"), BANDPASS("Band-pass (series RLC, 200 MHz)"), LOWPASS("Low-pass (shunt 30 pF)");

        public final String title;

        DemoDut(String title) { this.title = title; }
    }

    public DemoDut demoDut() { return demoDut; }
    public void setDemoDut(DemoDut d) { demoDut = d; }

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

    /**
     * Demo source for trying the GUI without hardware. Spectrum analyser: a carrier drifting through
     * 100-500 MHz with two harmonics, over noise, on a made-up ADC scale (about 328 counts per dB).
     * Transmission: the tracking generator through a simulated cable (4 ns, a little loss) and the selected
     * test component, read by a simulated phase detector that honours the PDM state (so the dead-zone
     * inversion and the calibration can be tried).
     */
    static SimulatedTransport demo(MsaConfig cfg, Supplier<MsaMode> mode, Supplier<DemoDut> dut,
                                   java.util.function.IntFunction<PathCalibration> cals) {
        Random rnd = new Random(1);
        Map<Integer, SynthPlanner> planners = new HashMap<>();
        AtomicLong reads = new AtomicLong();
        msa.sweep.PhaseDetector pd = msa.sweep.PhaseDetector.of(cfg);
        SimulatedTransport.AdcModel model = st -> {
            double f = ReplayTransport.tunedFrequency(st, cfg, planners);
            if (mode.get().networkAnalyser() && !Double.isNaN(f)) {
                double[] h = demoTransmission(f, dut.get());                // {dB, degrees}
                double dbm = -10 + h[0] + rnd.nextGaussian() * 0.03;
                int path = ReplayTransport.pathOf(st, cfg);
                double finalFreq = cfg.filter(path).freqMHz();
                double deg = h[1] + 30 + rnd.nextGaussian() * 0.05 + (st.pdmInverted ? 180 + pd.inversionDeviation(finalFreq) : 0);
                PathCalibration cal = cals.apply(path);
                long adc = cal != null ? adcFor(cal, dbm - cal.freqCorrection(f))
                        : Math.round(10000 + 328 * (Math.max(dbm, -110) + 100));
                long ph = Math.round(Math.floorMod(Math.round(deg * 1000), 360000) / 360000.0 * pd.maxPdmOut());
                return new long[] {Math.max(0, Math.min(65535, adc)), ph};
            }
            double carrier = 300 + 200 * Math.sin(reads.incrementAndGet() / 20000.0);
            double dbm = -110 + rnd.nextGaussian() * 1.5;
            for (int h = 1; h <= 3; h++) {
                double d = (f - h * carrier) / 1.5;
                dbm = Math.max(dbm, -15 - 20 * (h - 1) - 6 * d * d);
            }
            long adc = Math.round(10000 + 328 * (dbm + 100));
            return new long[] {Math.max(0, Math.min(65535, adc)), 32768};
        };
        SimulatedTransport t = new SimulatedTransport(model, 0x1024, 33, 0);
        t.setDescription("demo signal");
        return t;
    }

    /** Inverse of the magnitude table: the ADC reading that the calibration turns into this level. */
    static long adcFor(PathCalibration cal, double dbm) {
        PathCalibration.Table t = cal.magTable();
        double[] x = t.x(), y = t.y();
        if (dbm <= y[0]) return Math.round(x[0]);
        for (int i = 1; i < x.length; i++)
            if (dbm <= y[i]) return Math.round(x[i - 1] + (dbm - y[i - 1]) * (x[i] - x[i - 1]) / (y[i] - y[i - 1]));
        return Math.round(x[x.length - 1]);
    }

    /** Demo fixture response at f MHz: 4 ns of cable with 0.5 dB + 0.2 dB/100 MHz loss, then the component. */
    static double[] demoTransmission(double fMHz, DemoDut dut) {
        double w = 2 * Math.PI * fMHz * 1e6;
        double re = 1, im = 0;                                   // component S21 in a 50 ohm system
        switch (dut) {
            case THROUGH -> { }
            case BANDPASS -> {                                    // series R 5, L 400 nH, C 1.583 pF
                double x = w * 400e-9 - 1 / (w * 1.583e-12);
                double dr = 105, di = x;                          // S21 = 100 / (100 + Z)
                double den = dr * dr + di * di;
                re = 100 * dr / den;
                im = -100 * di / den;
            }
            case LOWPASS -> {                                     // shunt 30 pF: S21 = 1 / (1 + j w C 25)
                double b = w * 30e-12 * 25;
                double den = 1 + b * b;
                re = 1 / den;
                im = -b / den;
            }
        }
        double db = 10 * Math.log10(re * re + im * im) - 0.5 - 0.002 * fMHz;
        double deg = Math.toDegrees(Math.atan2(im, re)) - 360 * fMHz * 1e6 * 4e-9;
        return new double[] {db, deg};
    }
}
