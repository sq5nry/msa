package msa.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import msa.config.MsaConfig;
import msa.config.PathCalibration;
import msa.model.MsaSettings;
import msa.hw.LoggingTransport;
import msa.hw.MsaException;
import msa.hw.MsaTransport;
import msa.hw.SlimCommands.VideoFilter;
import msa.hw.UsbMsa;
import msa.hw.usb.LibUsbTransport;
import msa.sweep.SweepEngine;
import msa.sweep.SweepSettings;
import msa.sweep.Sweeper;
import msa.synth.SynthPlanner;

/**
 * Keyboard front end for {@link SweepEngine}: the GUI's buttons as one-letter commands, so the engine
 * can be exercised on the real MSA before any GUI exists.
 * <pre>
 *   console [START STOP STEPS] [--band B --path P --video V --wait MS --samples N --tg-offset MHZ --sg MHZ]
 * </pre>
 * With --replay FILE the console runs on a recording instead of the MSA.
 * Starts from the saved settings (MSA_Prefs/Prefs.txt, as the old program does at start-up); arguments
 * override them. "save" writes the current settings back.
 */
final class ConsoleTool {
    private ConsoleTool() {}

    private static final String HELP = """
            commands (type and press Enter):
              h  halt            c  continue        e  halt at end of sweep
              r  step right      l  step left       R  restart (re-initialise hardware)
              x  reconnect now (it also retries by itself every second after an error)
              f START STOP       new range          n STEPS     steps per sweep
              w MS   wait        s N   samples      v wide|mid|narrow|xnarrow   video filter
              b 0..3 band        p N   path         d lr|rl|alt  direction
              t      print the current trace (index, MHz, ADC, dBm)
              save   write the current settings to Prefs.txt (old file kept as Prefs.txt.bak)
              rec FILE   start recording every point to FILE      rec off   stop recording
              show   print the current settings
              q  quit           ?  this help""";

    static int run(List<String> args, boolean sim, Path log) {
        SynthTool.Opts o = new SynthTool.Opts(args);
        Path info = o.msaInfo();
        MsaConfig cfg;
        try {
            cfg = MsaConfig.read(info.resolve("config.txt"));
        } catch (IOException e) {
            throw new MsaException("cannot read " + info.resolve("config.txt"), e);
        }
        MsaSettings.Loaded loaded;
        try {
            loaded = MsaSettings.load(info, cfg);
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("  (cannot read Prefs.txt: " + e.getMessage() + "; using defaults)");
            loaded = new MsaSettings.Loaded(MsaSettings.defaults(), msa.config.Preferences.empty(), List.of());
        }
        for (String w : loaded.warnings()) System.out.println("  prefs: " + w);
        SweepSettings s = overrides(loaded.settings().sweep(), o);
        System.out.println("  " + describe(s));

        SweepEngine engine = new SweepEngine(cfg, path -> loadCal(info, path), () -> {
            MsaTransport t = SynthTool.transport(o, sim, cfg, new SynthPlanner(cfg, 1));
            if (log != null) t = LoggingTransport.toFile(t, log);
            return new UsbMsa(t);
        });
        engine.addListener(new Printer(), Runnable::run);
        System.out.println(HELP);
        engine.start(s);
        state = new Saved(info, cfg, loaded);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] w = line.trim().split("\\s+");
                if (w[0].isEmpty()) continue;
                try {
                    if (!command(engine, w)) break;
                } catch (RuntimeException e) {
                    System.out.println("  ? " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new MsaException("console input failed", e);
        } finally {
            engine.close();
            stopRecording(engine);
        }
        return 0;
    }

    private static msa.replay.SweepRecorder recorder;

    private static void record(SweepEngine e, String[] w) {
        try {
            if (recorder != null) {
                e.removeListener(recorder);
                recorder.close();
                System.out.printf("  recording stopped: %d points, %d complete sweeps in %s%n",
                        recorder.pointsWritten(), recorder.sweepsCompleted(), recorder.file());
                recorder = null;
            }
            if (w.length > 1 && !w[1].equals("off")) {
                recorder = new msa.replay.SweepRecorder(Path.of(w[1]));
                e.addListener(recorder, Runnable::run);
                e.restart();   // start a fresh sweep so the recording begins at a sweep boundary
                System.out.println("  recording to " + recorder.file().toAbsolutePath());
            }
        } catch (IOException ex) {
            System.out.println("  recording failed: " + ex.getMessage());
        }
    }

    static void stopRecording(SweepEngine e) {
        if (recorder != null) record(e, new String[] {"rec", "off"});
    }

    /** What "save" needs; the console is single-threaded, so a static holder is enough. */
    private static final class Saved {
        final Path info;
        final MsaConfig cfg;
        MsaSettings.Loaded loaded;

        Saved(Path info, MsaConfig cfg, MsaSettings.Loaded loaded) {
            this.info = info;
            this.cfg = cfg;
            this.loaded = loaded;
        }
    }

    private static Saved state;

    static SweepSettings overridesFor(SweepSettings s, SynthTool.Opts o) { return overrides(s, o); }

    /** Command-line values replace the saved ones. */
    private static SweepSettings overrides(SweepSettings s, SynthTool.Opts o) {
        if (o.positionalCount() >= 4)
            s = s.withRange(o.positionalNumber(1), o.positionalNumber(2)).withSteps((int) o.positionalNumber(3));
        if (o.has("--band")) s = s.withBand(o.integer("--band", 1));
        if (o.has("--path")) s = s.withPath(o.integer("--path", 1));
        if (o.has("--video")) s = s.withVideo(VideoFilter.valueOf(o.text("--video", "wide").toUpperCase(Locale.ROOT)));
        if (o.has("--wait")) s = s.withWait(o.integer("--wait", 5));
        if (o.has("--samples")) s = s.withSamples(o.integer("--samples", 1));
        if (o.has("--sg")) s = s.withGenerator(new SynthPlanner.SignalGen(o.number("--sg", 10)));
        else if (o.has("--tg-offset")) s = s.withGenerator(new SynthPlanner.Tracking(o.number("--tg-offset", 0)));
        return s;
    }

    static String describe(SweepSettings s) {
        String gen = switch (s.generator()) {
            case SynthPlanner.Tracking t -> "TG offset " + t.offsetMHz() + " MHz";
            case SynthPlanner.SignalGen g -> "SG at " + g.sgoutMHz() + " MHz";
        };
        return String.format(Locale.ROOT, "%.6f-%.6f MHz (centre %.6f, span %.6f), %d steps, %s %s, wait %d ms, samples %d, "
                        + "path %d, video %s, band %s, %s",
                s.startMHz(), s.stopMHz(), s.range().centerMHz(), s.range().spanMHz(), s.steps(),
                s.scale().name().toLowerCase(Locale.ROOT), s.direction().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                s.waitMs(), s.samples(), s.path(), s.video().name().toLowerCase(Locale.ROOT),
                s.band() == 0 ? "auto" : String.valueOf(s.band()), gen);
    }

    private static PathCalibration loadCal(Path info, int path) {
        try {
            return PathCalibration.load(info, path);
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("  (no calibration for path " + path + ": ADC values only)");
            return null;
        }
    }

    /** @return false to quit */
    private static boolean command(SweepEngine e, String[] w) {
        SweepSettings s = e.settings();
        switch (w[0]) {
            case "h" -> e.halt();
            case "c" -> e.resume();
            case "e" -> e.haltAtEnd();
            case "r" -> e.stepRight();
            case "l" -> e.stepLeft();
            case "R" -> e.restart();
            case "x" -> e.reconnect();
            case "f" -> e.update(x -> x.withRange(Double.parseDouble(w[1]), Double.parseDouble(w[2])));
            case "n" -> e.update(x -> x.withSteps(Integer.parseInt(w[1])));
            case "w" -> e.update(x -> x.withWait(Integer.parseInt(w[1])));
            case "s" -> e.update(x -> x.withSamples(Integer.parseInt(w[1])));
            case "v" -> e.update(x -> x.withVideo(VideoFilter.valueOf(w[1].toUpperCase(Locale.ROOT))));
            case "b" -> e.update(x -> x.withBand(Integer.parseInt(w[1])));
            case "p" -> e.update(x -> x.withPath(Integer.parseInt(w[1])));
            case "d" -> { SweepSettings.Direction dir = switch (w[1]) {
                case "rl" -> SweepSettings.Direction.RIGHT_TO_LEFT;
                case "alt" -> SweepSettings.Direction.ALTERNATE;
                default -> SweepSettings.Direction.LEFT_TO_RIGHT;
            };
                e.update(x -> x.withDirection(dir)); }
            case "t" -> printTrace(e.snapshot());
            case "show" -> System.out.println("  " + describe(s));
            case "rec" -> record(e, w);
            case "save" -> {
                try {
                    MsaSettings now = state.loaded.settings().withSweep(s);
                    state.loaded = now.save(state.info, state.loaded, state.cfg);
                    System.out.println("  saved to " + msa.config.Preferences.defaultFile(state.info));
                } catch (IOException ex) {
                    System.out.println("  save failed: " + ex.getMessage());
                }
            }
            case "q" -> { return false; }
            default -> System.out.println(HELP);
        }
        return true;
    }

    private static void printTrace(SweepEngine.Trace t) {
        System.out.printf("  sweep %d, %d of %d points measured%n", t.sweepNumber(), t.measuredCount(), t.points().length);
        for (int i = 0; i < t.points().length; i++) {
            Sweeper.Point p = t.points()[i];
            if (p != null) System.out.printf("  %4d %10.4f MHz  %5d  %s%n", i, p.plan().trueFreq(), p.adc().adc1(), dbm(p));
        }
    }

    private static String dbm(Sweeper.Point p) {
        return Double.isNaN(p.dBm()) ? "" : String.format("%8.2f dBm", p.dBm());
    }

    /** Prints state changes, one summary line per sweep, and the point where a halt or single step landed. */
    private static final class Printer implements SweepEngine.Listener {
        private Sweeper.Point max;
        private int maxIndex;
        private Sweeper.Point lastPoint;
        private int lastIndex;

        @Override
        public void stateChanged(SweepEngine.State state, String reason) {
            System.out.println("  [" + state + "] " + reason);
            if (state == SweepEngine.State.HALTED && lastPoint != null) printLast();
        }

        @Override
        public void sweepStarted(int n, SweepSettings s, int dir) {
            max = null;
        }

        @Override
        public void point(int sweep, int index, Sweeper.Point p) {
            lastPoint = p;
            lastIndex = index;
            if (max == null || p.adc().adc1() > max.adc().adc1()) {
                max = p;
                maxIndex = index;
            }
        }

        @Override
        public void sweepCompleted(int n, long ns) {
            System.out.printf("  sweep %d: %.2f s, peak %5d at %.4f MHz (step %d) %s%n",
                    n, ns / 1e9, max.adc().adc1(), max.plan().trueFreq(), maxIndex, dbm(max));
        }

        @Override
        public void error(Throwable t) {
            System.out.println("  ERROR " + t.getMessage());
        }

        private void printLast() {
            System.out.printf("  at step %d: %.4f MHz  ADC %d  %s%n", lastIndex, lastPoint.plan().trueFreq(),
                    lastPoint.adc().adc1(), dbm(lastPoint));
        }
    }
}
