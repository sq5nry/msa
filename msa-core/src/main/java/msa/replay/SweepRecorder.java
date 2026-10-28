package msa.replay;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Locale;

import msa.sweep.SweepEngine;
import msa.sweep.SweepSettings;
import msa.sweep.Sweeper;

/**
 * Writes every measured point to a recording file (CSV with '#' comment lines), for
 * {@link Recording} / {@link ReplayTransport} to play back later.
 *
 * <pre>
 * # msa-recording v2
 * # created 2026-10-25T21:10:00+01:00
 * # settings 0-1000 MHz, 200 steps, path 1, video WIDE, band 1, Tracking[offsetMHz=0.0], wait 5 ms, samples 1, mode SA
 * sweep,index,freqMHz,band,path,adc1,adc2,dBm,pdm,phase
 * 1,0,0.000000,1,1,28847,11044,-6.751,0,
 * </pre>
 * Raw ADC values are stored so a replay goes through the calibration again; dBm and phase are only for
 * reading. pdm is the phase detector state of the reading (v2; v1 files have no pdm and phase columns).
 * Register with {@code Runnable::run} (writes on the sweep thread) and close it when done.
 */
public final class SweepRecorder implements SweepEngine.Listener, AutoCloseable {

    private final BufferedWriter out;
    private final Path file;
    private SweepSettings lastSettings;
    private int pointsWritten;
    private int sweepsCompleted;

    public SweepRecorder(Path file) throws IOException {
        this.file = file;
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        out.write("# msa-recording v2\n");
        out.write("# created " + OffsetDateTime.now().withNano(0) + "\n");
        out.write("sweep,index,freqMHz,band,path,adc1,adc2,dBm,pdm,phase\n");
    }

    public Path file() { return file; }
    public int pointsWritten() { return pointsWritten; }
    public int sweepsCompleted() { return sweepsCompleted; }

    @Override
    public synchronized void sweepStarted(int n, SweepSettings s, int direction) {
        if (!s.equals(lastSettings)) {
            lastSettings = s;
            write(String.format(Locale.ROOT, "# settings %s-%s MHz, %d steps, path %d, video %s, band %d, %s, wait %d ms, samples %d, mode %s%n",
                    s.startMHz(), s.stopMHz(), s.steps(), s.path(), s.video(), s.band(), s.generator(), s.waitMs(), s.samples(),
                    s.mode().prefsName));
        }
    }

    @Override
    public synchronized void point(int sweep, int index, Sweeper.Point p) {
        int path = lastSettings == null ? 1 : lastSettings.path();
        write(String.format(Locale.ROOT, "%d,%d,%.6f,%d,%d,%d,%d,%s,%d,%s%n", sweep, index, p.plan().trueFreq(), p.plan().band(),
                path, p.adc().adc1(), p.adc().adc2(), Double.isNaN(p.dBm()) ? "" : String.format(Locale.ROOT, "%.3f", p.dBm()),
                p.pdm(), Double.isNaN(p.phaseDeg()) ? "" : String.format(Locale.ROOT, "%.2f", p.phaseDeg())));
        pointsWritten++;
    }

    @Override
    public synchronized void sweepCompleted(int n, long ns) {
        sweepsCompleted++;
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String s) {
        try {
            out.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
