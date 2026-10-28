package msa.spike;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import msa.config.MsaConfig;
import msa.config.PathCalibration;
import msa.hw.UsbMsa;
import msa.hw.sim.SimulatedTransport;
import msa.hw.usb.LibUsbTransport;
import msa.replay.ReplayTransport;
import msa.sweep.SweepEngine;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;
import msa.trace.TraceStore;

/**
 * Shared setup for both trials: arguments, engine, trace store, and the measurements.
 * <pre>
 *   [START STOP STEPS] [--replay FILE | --usb] [--msa-info DIR] [--path P] [--video V] [--wait MS] [--samples N]
 * </pre>
 * Settings start from MSA_Prefs/Prefs.txt (path, video filter, wait), like msa-probe console; arguments override.
 * Without --replay or --usb a built-in demo signal is used: a drifting carrier with harmonics over noise.
 */
final class SpikeSetup {

    final SweepSettings settings;
    final SweepEngine engine;
    final TraceStore store = new TraceStore(1);
    final PaintStats stats = new PaintStats();
    final String source;
    /** Second status line: path/RBW, step, video, wait, calibration, and a warning if the step is wider than the RBW. */
    final String setupInfo;

    private SpikeSetup(SweepSettings settings, SweepEngine engine, String source, String setupInfo) {
        this.settings = settings;
        this.engine = engine;
        this.source = source;
        this.setupInfo = setupInfo;
    }

    static SpikeSetup create(List<String> argv) {
        List<String> pos = new ArrayList<>();
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i < argv.size(); i++) {
            String a = argv.get(i);
            if (a.equals("--usb")) opt.put(a, "");
            else if (a.startsWith("--")) opt.put(a, argv.get(++i));
            else pos.add(a);
        }
        Path info = opt.containsKey("--msa-info") ? Path.of(opt.get("--msa-info")) : findMsaInfo();
        MsaConfig cfg;
        try {
            cfg = MsaConfig.read(info.resolve("config.txt"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + info.resolve("config.txt") + " (use --msa-info DIR)", e);
        }
        SweepSettings s = SweepSettings.defaults().withWait(5);
        String prefsNote;
        try {
            s = msa.model.MsaSettings.load(info, cfg).settings().sweep();
            prefsNote = "Prefs.txt";
        } catch (IOException | RuntimeException e) {
            prefsNote = "defaults (Prefs.txt not readable)";
        }
        double start = pos.size() > 0 ? Double.parseDouble(pos.get(0)) : 0;
        double stop = pos.size() > 1 ? Double.parseDouble(pos.get(1)) : 1000;
        int steps = pos.size() > 2 ? Integer.parseInt(pos.get(2)) : 800;
        s = s.withRange(start, stop).withSteps(steps).withGenerator(new SynthPlanner.Tracking(0));
        if (opt.containsKey("--path")) s = s.withPath(Integer.parseInt(opt.get("--path")));
        if (opt.containsKey("--video"))
            s = s.withVideo(msa.hw.SlimCommands.VideoFilter.valueOf(opt.get("--video").toUpperCase(java.util.Locale.ROOT)));
        if (opt.containsKey("--wait")) s = s.withWait(Integer.parseInt(opt.get("--wait")));
        if (opt.containsKey("--samples")) s = s.withSamples(Integer.parseInt(opt.get("--samples")));

        String replay = opt.get("--replay");
        boolean usb = opt.containsKey("--usb");
        String source = replay != null ? "replay " + Path.of(replay).getFileName() : usb ? "MSA (USB)" : "demo signal";
        SweepEngine engine = new SweepEngine(cfg, path -> {
            try {
                return PathCalibration.load(info, path);
            } catch (IOException | IllegalArgumentException e) {
                return null;
            }
        }, () -> {
            try {
                if (replay != null) return new UsbMsa(ReplayTransport.open(Path.of(replay), cfg));
                if (usb) return new UsbMsa(LibUsbTransport.open());
                return new UsbMsa(demo(cfg));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return new SpikeSetup(s, engine, source, describe(s, cfg, info, prefsNote));
    }

    private static String describe(SweepSettings s, MsaConfig cfg, Path info, String prefsNote) {
        double rbwKHz = cfg.filter(s.path()).bwKHz();
        double stepKHz = 1000 * (s.stopMHz() - s.startMHz()) / s.steps();
        String cal;
        try {
            PathCalibration.load(info, s.path());
            cal = "calibration OK";
        } catch (IOException | IllegalArgumentException e) {
            cal = "NO path calibration: rough ADC scale, dBm not meaningful";
        }
        String warn = stepKHz > rbwKHz
                ? String.format(java.util.Locale.ROOT, "  !! step %.4g kHz > RBW %.4g kHz: signals between points are missed", stepKHz, rbwKHz)
                : "";
        return String.format(java.util.Locale.ROOT, "path %d (RBW %.4g kHz) | step %.4g kHz | video %s | wait %d ms | samples %d | %s | %s | MSA_Info %s%s",
                s.path(), rbwKHz, stepKHz, s.video().name().toLowerCase(java.util.Locale.ROOT), s.waitMs(), s.samples(),
                cal, prefsNote, info, warn);
    }

    /** Wraps a UI executor so the delay from "point measured" to "UI thread got it" is measured. */
    Executor timed(Executor ui) {
        return r -> {
            long t0 = System.nanoTime();
            ui.execute(() -> {
                stats.deliveryLag(System.nanoTime() - t0);
                r.run();
            });
        };
    }

    void start() {
        engine.addListener(store, timed(uiExecutor));
        engine.addListener(new SweepEngine.Listener() {
            @Override
            public void point(int n, int i, msa.sweep.Sweeper.Point p) { stats.point(); }

            @Override
            public void sweepCompleted(int n, long ns) { stats.sweep(ns); }
        }, Runnable::run);
        engine.start(settings);
    }

    Executor uiExecutor = Runnable::run;

    private static Path findMsaInfo() { return msa.config.MsaInfo.locate(null); }

    /** Demo: a carrier drifting through the span plus harmonics, over noise; ADC roughly 328 counts per dB. */
    private static SimulatedTransport demo(MsaConfig cfg) {
        Random rnd = new Random(1);
        Map<Integer, SynthPlanner> planners = new HashMap<>();
        AtomicLong reads = new AtomicLong();
        SimulatedTransport.AdcModel model = st -> {
            double f = ReplayTransport.tunedFrequency(st, cfg, planners);
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
}
