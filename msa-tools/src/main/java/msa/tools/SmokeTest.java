package msa.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import msa.hw.AdcConfig;
import msa.hw.AdcResult;
import msa.hw.MsaProtocol;
import msa.hw.MsaTimeoutException;
import msa.hw.SlimCommands;
import msa.hw.SlimCommands.VideoFilter;
import msa.hw.UsbMsa;

/**
 * Hardware smoke test that needs no signal and no synthesiser setup.
 *
 * <p>What it can prove without LOs running: USB and firmware work, ADC reads and FX2 averaging work,
 * and latch 4 (the relay latch shared by video filter, band, FR and TR) really switches - the video
 * filter changes the noise on the ADC, which is measurable. The band relays sit in the RF path before
 * mixer 1, so with no LO1 there is nothing to measure through them: the test commands them, reports
 * the noise floor per band, and with {@code --listen} asks you whether each relay clicked.
 * Checking the band filters' passbands needs a signal and a running sweep; that comes with the sweep engine.
 */
public final class SmokeTest {

    private final UsbMsa msa;
    private final PrintStream out;
    private final boolean listen;
    private final int settleMs;
    private final int n;
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    private int pass, warn, fail;

    public SmokeTest(UsbMsa msa, PrintStream out, boolean listen, int settleMs, int samplesPerPoint) {
        this.msa = msa;
        this.out = out;
        this.listen = listen;
        this.settleMs = settleMs;
        this.n = samplesPerPoint;
    }

    /** Mean and spread of a run of ADC readings (magnitude ADC). */
    record Stats(double mean, double sd, long min, long max, int readsDone) {
        static Stats of(long[] v, int readsDone) {
            double m = Arrays.stream(v).average().orElse(0);
            double var = Arrays.stream(v).mapToDouble(x -> (x - m) * (x - m)).sum() / Math.max(1, v.length - 1);
            return new Stats(m, Math.sqrt(var), Arrays.stream(v).min().orElse(0), Arrays.stream(v).max().orElse(0), readsDone);
        }

        @Override
        public String toString() {
            return String.format("mean %8.1f  sd %7.2f  min %5d  max %5d", mean, sd, min, max);
        }
    }

    public int run() {
        out.println("MSA smoke test - " + msa.transport().description());
        device();
        initialise();
        strayReplies();
        adcTiming();
        averaging();
        videoFilters();
        bands();
        restore();
        out.printf("%nSummary: %d PASS, %d WARN, %d FAIL%n", pass, warn, fail);
        return fail;
    }

    // ---------------------------------------------------------------- sections

    private void device() {
        section("1. Device and firmware");
        info("bcdDevice 0x%04x, UsbMSAGetVersions %d (0x%04x)", msa.bcdDevice(), msa.versions(), msa.versions());
        AdcResult r = msa.readAdcs(AdcConfig.defaults());
        int fw = r.readsDone();
        if (fw >= 28) ok("firmware version %d (A7 step command needs >= 28; v118 was written for 33)", fw);
        else if (fw == 2) bad("firmware older than v21 (reads_done = 2): update the EEPROM to msa-fw-cb-v33.hex");
        else caution("firmware version %d: older than v28, the A7 step command will not work", fw);
        info("reply status byte 0x%02x", r.status());
    }

    private void initialise() {
        section("2. Initialise: filter path 1, DDS1 and DDS3 serial reset");
        int fb = SlimCommands.filterBank(1);
        SlimCommands.run(msa, SlimCommands.commandFilter(fb));
        SlimCommands.run(msa, SlimCommands.resetSerialDds(1, fb, 0));
        SlimCommands.run(msa, SlimCommands.resetSerialDds(3, fb, 0));
        SlimCommands.run(msa, SlimCommands.commandPdm(0));
        ok("commands accepted (latch 1, latch 2)");
    }

    /**
     * Works out how the firmware fills EP6: are there frames waiting with no request, does a latch write
     * produce one, how many frames does one ADC read produce, and is anything left over afterwards.
     * The only dangerous case is a stale frame that carries ADC data (reads_done != 0): a later read
     * could then return an old measurement.
     */
    private void strayReplies() {
        section("3. Reply pattern on EP6");
        List<MsaProtocol.RxFrame> waiting = drainFrames(20);
        info("frames waiting before any request: %d%s", waiting.size(), describe(waiting));
        boolean staleData = waiting.stream().anyMatch(f -> f.adcReadsDone() != 0);

        SlimCommands.run(msa, SlimCommands.commandPdm(0));
        List<MsaProtocol.RxFrame> afterLatch = drainFrames(10);
        info("frames after a latch write: %d%s", afterLatch.size(), describe(afterLatch));
        staleData |= afterLatch.stream().anyMatch(f -> f.adcReadsDone() != 0);

        msa.transport().write(MsaProtocol.adcCommand(AdcConfig.defaults()), 500);
        List<MsaProtocol.RxFrame> afterAdc = drainFrames(10);
        info("frames after one ADC command: %d%s", afterAdc.size(), describe(afterAdc));
        int firstData = -1;
        for (int i = 0; i < afterAdc.size(); i++) if (afterAdc.get(i).adcReadsDone() != 0) { firstData = i; break; }
        long dataFrames = afterAdc.stream().filter(f -> f.adcReadsDone() != 0).count();

        if (afterLatch.size() >= 10 || afterAdc.size() >= 10) {
            info("EP6 answers every read with the current status (status on demand)");
        }
        if (staleData) {
            bad("a frame with ADC data was waiting unrequested: readAdcs could return an old measurement");
        } else if (firstData < 0) {
            bad("no frame with ADC data after an ADC command");
        } else if (dataFrames > 1) {
            caution("one ADC command produced %d data frames: the extra one would be read as the next measurement", dataFrames);
        } else if (firstData >= UsbMsa.MAX_READS_PER_ADC) {
            bad("ADC data came as frame %d, after msadll's 5-read limit", firstData + 1);
        } else {
            ok("status-only frames (reads_done 0) are skipped and ADC data arrives as frame %d, as msadll expects;"
                    + " leftover status frames are harmless", firstData + 1);
        }
    }

    /** Reads until a short timeout, at most {@code max} frames. */
    private List<MsaProtocol.RxFrame> drainFrames(int max) {
        byte[] buf = new byte[MsaProtocol.RX_BUFFER_SIZE];
        List<MsaProtocol.RxFrame> frames = new java.util.ArrayList<>();
        for (int i = 0; i < max; i++) {
            try {
                int len = msa.transport().read(buf, 100);
                if (len >= 8) frames.add(MsaProtocol.parseRx(buf, len));
                else info("short frame (%d bytes): %s", len, MsaProtocol.toHex(Arrays.copyOf(buf, len)));
            } catch (MsaTimeoutException e) {
                break;
            }
        }
        return frames;
    }

    private static String describe(List<MsaProtocol.RxFrame> frames) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(frames.size(), 4); i++) {
            MsaProtocol.RxFrame f = frames.get(i);
            sb.append(String.format("%n         #%d last cmd %02X, status %02X, port A %02X, reads_done %d%s",
                    i + 1, f.lastCommand(), f.status(), f.portA(), f.adcReadsDone(),
                    f.adcReadsDone() != 0 ? String.format(", data %d / %d", f.word(0), f.word(1)) : ""));
        }
        if (frames.size() > 4) sb.append(String.format("%n         ... %d more", frames.size() - 4));
        return sb.toString();
    }

    private void adcTiming() {
        section("4. ADC read timing (" + n + " reads, 1 sample)");
        long[] us = new long[n];
        AdcConfig cfg = AdcConfig.defaults();
        long skippedBefore = msa.skippedFrames();
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            msa.readAdcs(cfg);
            us[i] = (System.nanoTime() - t0) / 1000;
        }
        Arrays.sort(us);
        info("round trip: min %d us, median %d us, 95%% %d us, max %d us",
                us[0], us[n / 2], us[(int) (n * 0.95)], us[n - 1]);
        info("status-only frames skipped: %.2f per ADC read", (msa.skippedFrames() - skippedBefore) / (double) n);
        if (us[n / 2] > 5000) caution("median above 5 ms: check for a USB 1.1 port or hub");
        else ok("timing normal for a USB 2.0 bulk round trip");
    }

    private void averaging() {
        section("5. FX2 sample averaging (video filter Wide, band 1)");
        selectSwitches(VideoFilter.WIDE, 1);
        Stats s1 = null, s64 = null;
        for (int avg : new int[] {1, 4, 16, 64}) {
            Stats s = measure(AdcConfig.defaults().withAverage(avg));
            info("samples %3d: %s", avg, s);
            if (avg == 1) s1 = s;
            if (avg == 64) s64 = s;
        }
        if (s1.sd() < 1.5) caution("noise at 1 sample is only %.2f LSB: too small to show the averaging", s1.sd());
        else if (s64.sd() < s1.sd() / 2) ok("averaging reduces noise (%.2f -> %.2f LSB; ideal /8)", s1.sd(), s64.sd());
        else bad("averaging does not reduce noise (%.2f -> %.2f LSB)", s1.sd(), s64.sd());
    }

    /** Latch 4 check with a measurable effect. */
    private void videoFilters() {
        section("6. Video filters via latch 4 (band 1)");
        Stats wide = null, xnarrow = null;
        for (VideoFilter v : VideoFilter.values()) {
            selectSwitches(v, 1);
            Stats s = measure(AdcConfig.defaults());
            info("%-8s switch 0x%02X: %s", v, SlimCommands.switchData(v, 1, false, false), s);
            if (v == VideoFilter.WIDE) wide = s;
            if (v == VideoFilter.XNARROW) xnarrow = s;
        }
        if (wide.sd() < 1.5) caution("noise with Wide is only %.2f LSB: cannot tell whether the video filter switched", wide.sd());
        else if (xnarrow.sd() < wide.sd() / 2) ok("latch 4 switches: XNarrow noise %.2f vs Wide %.2f LSB", xnarrow.sd(), wide.sd());
        else bad("XNarrow is not quieter than Wide (%.2f vs %.2f LSB): latch 4 relays may not be switching", xnarrow.sd(), wide.sd());
    }

    private void bands() {
        section("7. Band switches 1G -> 2G -> 3G -> 1G (video Wide)" + (listen ? "" : "   [add --listen to check relay clicks]"));
        int clicked = 0, asked = 0;
        int[] sequence = {1, 2, 3, 1};
        for (int i = 0; i < sequence.length; i++) {
            int band = sequence[i];
            // the first selection only sets a known starting state; it may not click if band 1 was already set
            boolean ask = listen && i > 0;
            if (ask) prompt("Press Enter, then listen for the band relay switching to band " + band + " ...");
            selectSwitches(VideoFilter.WIDE, band);
            Stats s = measure(AdcConfig.defaults());
            info("band %d (%dG) switch 0x%02X: %s", band, band, SlimCommands.switchData(VideoFilter.WIDE, band, false, false), s);
            if (ask) {
                asked++;
                if (yes("Did it click? [y/n] ")) clicked++;
            }
        }
        if (listen) {
            if (clicked == asked) ok("all %d band changes clicked", asked);
            else bad("%d of %d band changes did not click: check the band relays and their pulse capacitors", asked - clicked, asked);
        } else {
            ok("band commands accepted");
        }
        info("with no LO1 running the band filters carry no signal, so the levels above are only the IF noise floor;");
        info("passband checks need a sweep (next step of the port)");
    }

    private void restore() {
        section("8. Restore defaults: band 1, video Wide, forward, transmission, PDM normal");
        selectSwitches(VideoFilter.WIDE, 1);
        SlimCommands.run(msa, SlimCommands.commandPdm(0));
        ok("restored");
    }

    // ---------------------------------------------------------------- helpers

    private void selectSwitches(VideoFilter v, int band) {
        SlimCommands.run(msa, SlimCommands.commandSwitches(SlimCommands.switchData(v, band, false, false)));
        sleep(settleMs);
    }

    private Stats measure(AdcConfig cfg) {
        long[] v = new long[n];
        int fw = 0;
        for (int i = 0; i < n; i++) {
            AdcResult r = msa.readAdcs(cfg);
            v[i] = r.adc1();
            fw = r.readsDone();
        }
        return Stats.of(v, fw);
    }

    private void prompt(String text) {
        out.print("      " + text);
        out.flush();
        readLine();
    }

    private boolean yes(String q) {
        out.print("      " + q);
        out.flush();
        String a = readLine();
        return a != null && a.trim().toLowerCase().startsWith("y");
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void section(String title) { out.println(); out.println(title); }
    private void info(String f, Object... a) { out.println("  INFO " + String.format(f, a)); }
    private void ok(String f, Object... a) { pass++; out.println("  PASS " + String.format(f, a)); }
    private void caution(String f, Object... a) { warn++; out.println("  WARN " + String.format(f, a)); }
    private void bad(String f, Object... a) { fail++; out.println("  FAIL " + String.format(f, a)); }
}
