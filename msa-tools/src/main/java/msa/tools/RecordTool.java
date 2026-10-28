package msa.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import msa.config.MsaConfig;
import msa.config.PathCalibration;
import msa.hw.LoggingTransport;
import msa.hw.MsaException;
import msa.hw.MsaTransport;
import msa.hw.UsbMsa;
import msa.model.MsaSettings;
import msa.replay.SweepRecorder;
import msa.sweep.SweepEngine;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;

/**
 * {@code record START STOP STEPS --out FILE [--sweeps N] [sweep options]}: runs N sweeps (default 5) and
 * writes every point to FILE for {@code --replay}. Settings start from Prefs.txt like the console.
 * Several sweeps are worth recording: the replay cycles through them, which reproduces the real noise.
 */
final class RecordTool {
    private RecordTool() {}

    static int run(List<String> args, boolean sim, Path log) {
        SynthTool.Opts o = new SynthTool.Opts(args);
        if (!o.has("--out")) throw new MsaException("record needs --out FILE");
        Path info = o.msaInfo();
        MsaConfig cfg;
        MsaSettings base;
        try {
            cfg = MsaConfig.read(info.resolve("config.txt"));
            base = MsaSettings.load(info, cfg).settings();
        } catch (IOException e) {
            throw new MsaException("cannot read MSA_Info: " + e.getMessage(), e);
        }
        SweepSettings s = ConsoleToolAccess.overrides(base.sweep(), o);
        int sweeps = o.integer("--sweeps", 5);
        Path out = Path.of(o.text("--out", "recording.csv"));
        System.out.println("recording " + sweeps + " sweeps: " + ConsoleTool.describe(s));

        CountDownLatch done = new CountDownLatch(sweeps);
        try (SweepRecorder rec = new SweepRecorder(out);
             SweepEngine engine = new SweepEngine(cfg, path -> {
                 try {
                     return PathCalibration.load(info, path);
                 } catch (IOException | IllegalArgumentException e) {
                     return null;
                 }
             }, () -> {
                 MsaTransport t = SynthTool.transport(o, sim, cfg, new SynthPlanner(cfg, s.path()));
                 if (log != null) t = LoggingTransport.toFile(t, log);
                 return new UsbMsa(t);
             })) {
            engine.addListener(rec, Runnable::run);
            engine.addListener(new SweepEngine.Listener() {
                @Override
                public void sweepCompleted(int n, long ns) {
                    System.out.printf("  sweep %d done (%.2f s)%n", n, ns / 1e9);
                    done.countDown();
                }

                @Override
                public void error(Throwable t) {
                    System.out.println("  ERROR " + t.getMessage());
                }
            }, Runnable::run);
            engine.start(s);
            if (!done.await(Math.max(60, sweeps * 120L), TimeUnit.SECONDS))
                System.out.println("  timed out before all sweeps completed");
            engine.halt();
            engine.awaitState(SweepEngine.State.HALTED, Duration.ofSeconds(5));
            System.out.printf("wrote %d points (%d complete sweeps) to %s%n",
                    rec.pointsWritten(), rec.sweepsCompleted(), out.toAbsolutePath());
        } catch (IOException e) {
            throw new MsaException("cannot write " + out + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    /** Package access to the console's option handling, so both commands read options the same way. */
    private static final class ConsoleToolAccess {
        static SweepSettings overrides(SweepSettings s, SynthTool.Opts o) {
            return ConsoleTool.overridesFor(s, o);
        }
    }
}
