package msa.replay;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import msa.config.MsaConfig;
import msa.hw.AdcConfig;
import msa.hw.SlimCommands.VideoFilter;
import msa.hw.UsbMsa;
import msa.hw.sim.SimulatedTransport;
import msa.sweep.SweepEngine;
import msa.sweep.SweepSettings;
import msa.sweep.Sweeper;
import msa.synth.HardwareInit;
import msa.synth.SynthPlanner;

class ReplayTest {

    private static MsaConfig cfg() throws Exception {
        return MsaConfig.read(Path.of(ReplayTest.class.getResource("/msa-info/config.txt").toURI()));
    }

    /** Two sweeps over 100..200 MHz in 10 MHz steps: sweep 1 reads 20000 + 10 f, sweep 2 reads 1 more. */
    private static Path recording(Path dir) throws Exception {
        StringBuilder sb = new StringBuilder("# msa-recording v1\nsweep,index,freqMHz,band,path,adc1,adc2,dBm\n");
        for (int s = 1; s <= 2; s++)
            for (int i = 0; i <= 10; i++) {
                int f = 100 + 10 * i;
                sb.append(s).append(',').append(i).append(',').append(f).append(".000000,1,1,")
                  .append(20000 + 10 * f + (s - 1)).append(',').append(30000 + f).append(",\n");
            }
        Path p = dir.resolve("rec.csv");
        Files.writeString(p, sb.toString());
        return p;
    }

    @Test
    void recordingAnswersExactInterpolatedAndOutsideFrequencies(@TempDir Path tmp) throws Exception {
        Recording r = Recording.read(recording(tmp));
        assertEquals(22, r.points());
        assertArrayEquals(new long[] {21500, 30150}, r.sample(1, 150, 0, 1));          // sweep 1
        assertArrayEquals(new long[] {21501, 30150}, r.sample(1, 150, 1, 1));          // sweep 2
        assertArrayEquals(new long[] {21500, 30150}, r.sample(1, 150, 2, 1));          // cycles
        assertArrayEquals(new long[] {21550, 30155}, r.sample(1, 155, 0, 1));          // halfway
        assertArrayEquals(new long[] {21000, 30100}, r.sample(1, 999, 0, 1));          // floor = lowest point (100 MHz)
        assertArrayEquals(new long[] {21000, 30100}, r.sample(1, -5, 0, 1));
        assertArrayEquals(new long[] {21500, 30150}, r.sample(4, 150, 0, 1));          // unrecorded path: any recorded path
        assertEquals(21501, r.sample(1, 150, 0, 2)[0], "averaging 2 readings: (21500 + 21501) / 2 rounded");
    }

    @Test
    void tunedFrequencyIsDecodedFromTheCommandsInAllBands() throws Exception {
        MsaConfig cfg = cfg();
        for (double f : new double[] {0.5, 100, 999.9, 1500, 2500}) {
            int band = SynthPlanner.autoBand(f);
            SynthPlanner p = new SynthPlanner(cfg, 2);
            SimulatedTransport sim = new SimulatedTransport();
            try (UsbMsa msa = new UsbMsa(sim)) {
                Sweeper sw = new Sweeper(msa, p, new SynthPlanner.Tracking(0));
                sw.initialise(new HardwareInit.Switches(VideoFilter.WIDE, band, false, false), 0);
                sw.command(f, band);
                assertEquals(2, ReplayTransport.pathOf(sim.state(), cfg));
                assertEquals(f, ReplayTransport.tunedFrequency(sim.state(), cfg, new HashMap<>()), 1e-4, "at " + f + " MHz");
            }
        }
    }

    @Test
    void replayAnyGridThroughTheRealEngine(@TempDir Path tmp) throws Exception {
        Path rec = recording(tmp);
        MsaConfig cfg = cfg();
        List<long[]> got = new ArrayList<>();
        try (SweepEngine e = new SweepEngine(cfg, p -> null, () -> {
            try {
                return new UsbMsa(ReplayTransport.open(rec, cfg));
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        })) {
            e.addListener(new SweepEngine.Listener() {
                public void point(int n, int i, Sweeper.Point p) {
                    synchronized (got) { got.add(new long[] {n, i, p.adc().adc1()}); }
                }
            }, Runnable::run);
            e.start(SweepSettings.defaults().withRange(140, 160).withSteps(4).withWait(0));   // 140,145,150,155,160
            long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (got.size() < 10 && System.nanoTime() < end) Thread.sleep(10);
            e.halt();
        }
        synchronized (got) {
            assertEquals(21400, got.get(0)[2]);
            assertEquals(21450, got.get(1)[2]);   // between recorded 140 and 150
            assertEquals(21500, got.get(2)[2]);
            assertEquals(21550, got.get(3)[2]);
            assertEquals(21600, got.get(4)[2]);
            assertEquals(21401, got.get(5)[2], "second replayed sweep uses the second recorded sweep");
        }
    }

    @Test
    void recorderWritesWhatReplayReads(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("sim.csv");
        MsaConfig cfg = cfg();
        try (SweepRecorder rec = new SweepRecorder(file);
             SweepEngine e = new SweepEngine(cfg, p -> null, () -> new UsbMsa(new SimulatedTransport()))) {
            e.addListener(rec, Runnable::run);
            e.start(SweepSettings.defaults().withRange(100, 110).withSteps(10).withWait(0));
            long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (rec.sweepsCompleted() < 2 && System.nanoTime() < end) Thread.sleep(10);
            e.halt();
            assertTrue(e.awaitState(SweepEngine.State.HALTED, Duration.ofSeconds(5)));
        }
        String text = Files.readString(file);
        assertTrue(text.startsWith("# msa-recording v2\n"));
        assertTrue(text.contains("# settings 100.0-110.0 MHz, 10 steps, path 1"));
        Recording r = Recording.read(file);
        assertTrue(r.points() >= 22);
        assertArrayEquals(new double[] {100, 110}, r.span(1), 1e-9);
        assertTrue(r.sample(1, 105, 0, 1)[0] > 0);
        // a replay transport opens on it and answers ADC reads
        try (UsbMsa msa = new UsbMsa(ReplayTransport.open(file, cfg))) {
            assertTrue(msa.readAdcs(AdcConfig.defaults()).adc1() > 0);
        }
    }

    @Test
    void phaseReadingsFollowTheCommandedPdmState(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("vna.csv");
        Files.writeString(f, "# msa-recording v2\n"
                + "sweep,index,freqMHz,band,path,adc1,adc2,dBm,pdm,phase\n"
                + "1,0,100.000000,1,1,30000,40000,-10.000,1,12.00\n"
                + "1,1,101.000000,1,1,30000,20000,-10.000,0,90.00\n"
                + "1,2,102.000000,1,1,30000,40000,-10.000,\n");                 // v1-style row: pdm 0
        Recording r = Recording.read(f);
        Recording.PdmShift normal = new Recording.PdmShift(0, 10000, 65535);
        Recording.PdmShift inverted = new Recording.PdmShift(1, 10000, 65535);
        assertEquals(30000, r.sample(1, 100, 0, 1, normal)[1], "recorded inverted, wanted normal: minus the shift");
        assertEquals(40000, r.sample(1, 100, 0, 1, inverted)[1]);
        assertEquals(30000, r.sample(1, 101, 0, 1, inverted)[1], "recorded normal, wanted inverted: plus the shift");
        assertEquals(40000, r.sample(1, 102, 0, 1, normal)[1]);
        assertEquals(40000, r.sample(1, 100, 0, 1)[1], "no PDM model: as recorded");
        Recording.PdmShift wrap = new Recording.PdmShift(1, 40000, 65535);
        assertEquals(Math.floorMod(20000 + 40000, 65535), r.sample(1, 101, 0, 1, wrap)[1], "wraps at full scale");
    }
}
