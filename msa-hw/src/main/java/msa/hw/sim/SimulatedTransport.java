package msa.hw.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import msa.hw.MsaException;
import msa.hw.MsaProtocol;
import msa.hw.MsaTimeoutException;
import msa.hw.MsaTransport;
import msa.hw.SlimCommands;

/**
 * A stand-in for the FX2 + SLIM control board, good enough to develop the sweep engine and UI
 * without hardware. It accepts the same byte stream as the real firmware, remembers the latch
 * states, decodes "A7" step commands back into DDS/PLL words, and answers "B0..B2" ADC reads
 * with a reply laid out exactly like MSA_RXBUFFER.
 *
 * <p>What it does NOT model: real RF, timing, or any firmware behaviour not visible in
 * msadll.cpp / spectrumanalyzer.bas.
 */
public final class SimulatedTransport implements MsaTransport {

    /** Produces {adc1, adc2} for a read, given the current simulated hardware state. */
    @FunctionalInterface
    public interface AdcModel {
        long[] sample(State state);
    }

    /** Latch and module state as last commanded. */
    public static final class State {
        public int latch1, latch2, latch3;
        /** Latch 4 starts with the pulse line high and band 1, like a board after start-up. */
        public int latch4 = 128 + 4;
        public boolean pdmInverted;
        public long dds1Word, dds3Word;
        public int pll1Bits, pll3Bits;
        public int stepCommands;
        /** Averaging requested by the last ADC command. */
        public int average = 1;

        /** DDS1 frequency for a given DDS clock, from the last step command. */
        public double dds1Frequency(double ddsClock) {
            return SlimCommands.ddsOutput(dds1Word & 0xFFFFFFFFL, ddsClock);
        }
    }

    private final State state = new State();
    private final Deque<byte[]> replies = new ArrayDeque<>();
    private final List<byte[]> received = new ArrayList<>();
    private final AdcModel model;
    private final int bcdDevice;
    private final int readsDoneTag;
    private int pendingRepliesBeforeData;
    private int trailingStatusFrames;
    private boolean closed;

    /** Default: noise around mid-scale, firmware descriptor bcdDevice 0x1024 (msa-fw-cb-v33.hex). */
    public SimulatedTransport() {
        this(noise(new Random(1), 20000, 40), 0x1024, 33, 0);
    }

    /**
     * @param readsDoneTag            value put in ADC_reads_done; firmware v21 and later return their
     *                                version number here (spectrumanalyzer.bas change note 44), so 33 for v33
     * @param pendingRepliesBeforeData number of "no data yet" replies sent before each real one,
     *                                to exercise msadll's read-up-to-5-times loop
     */
    public SimulatedTransport(AdcModel model, int bcdDevice, int readsDoneTag, int pendingRepliesBeforeData) {
        this.model = model;
        this.bcdDevice = bcdDevice;
        this.readsDoneTag = readsDoneTag;
        this.pendingRepliesBeforeData = pendingRepliesBeforeData;
    }

    /**
     * Noise-floor model: Gaussian noise whose spread shrinks with FX2 averaging and with narrower
     * video filters (latch 4 bits 0-1), plus a small made-up level offset per band so band switching
     * is visible in the simulator. The numbers are invented, not measured.
     */
    public static AdcModel noise(Random rnd, int magLevel, int sigma) {
        double[] videoFactor = {1, 3, 10, 30};
        int[] bandOffset = {0, 0, -150, -300};
        return s -> {
            double sd = sigma / Math.sqrt(s.average) / videoFactor[s.latch4 & 3];
            int level = magLevel + bandOffset[(s.latch4 >> 2) & 3];
            return new long[] {
                clamp(Math.round(level + rnd.nextGaussian() * sd)),
                clamp(Math.round((s.pdmInverted ? 49152 : 16384) + rnd.nextGaussian() * sd))
            };
        };
    }

    private static long clamp(long v) { return Math.max(0, Math.min(65535, v)); }

    public State state() { return state; }

    /** Every command received, in order (copies). */
    public List<byte[]> received() { return Collections.unmodifiableList(received); }

    public void setPendingRepliesBeforeData(int n) { pendingRepliesBeforeData = n; }

    /** Status-only frames queued after each ADC data frame (seen on a real board: "B2 00 30 00 00 00 00 00"). */
    public void setTrailingStatusFrames(int n) { trailingStatusFrames = n; }

    @Override
    public synchronized void write(byte[] data, int timeoutMs) {
        if (closed) throw new MsaException("closed");
        if (data.length < 2) throw new MsaException("runt command");
        received.add(data.clone());
        int code = data[0] & 0xFF;
        if (code >= MsaProtocol.CMD_READ_ADC_BASE && code <= MsaProtocol.CMD_READ_ADC_BASE + 2) {
            if (data.length >= 5) state.average = Math.max(1, data[4] & 0xFF);
            for (int i = 0; i < pendingRepliesBeforeData; i++) replies.add(reply(code, 0, 0, 0, 0));
            long[] v = model.sample(state);
            replies.add(reply(code, MsaProtocol.RxFrame.STATUS_ADC_DATA_READY, readsDoneTag, v[0], v[1]));
            for (int i = 0; i < trailingStatusFrames; i++) replies.add(Arrays.copyOf(reply(code, 0, 0, 0, 0), 8));
            return;
        }
        int count = data[1] & 0xFF;
        if (data.length != count + 3 && code != MsaProtocol.CMD_RAW_PORTS)
            throw new MsaException(String.format("command %02X: count byte %d but %d bytes sent", code, count, data.length));
        int last = data.length > 3 ? data[data.length - 1] & 0xFF : 0;
        switch (code) {
            case MsaProtocol.CMD_LATCH1 -> state.latch1 = last;
            case MsaProtocol.CMD_LATCH2 -> {
                state.latch2 = last;
                state.pdmInverted = (last & SlimCommands.L2_PDM) != 0;
            }
            case MsaProtocol.CMD_LATCH3 -> state.latch3 = last;
            case MsaProtocol.CMD_LATCH4 -> state.latch4 = last;
            case MsaProtocol.CMD_STEP -> decodeStep(data);
            default -> { /* A6 raw port writes etc.: accepted, ignored */ }
        }
    }

    /** Inverse of {@link SlimCommands#stepCommand}. */
    private void decodeStep(byte[] d) {
        if (d.length != 43) throw new MsaException("A7 step command must be 43 bytes");
        long dds1 = 0, dds3 = 0;
        int pll1 = 0, pll3 = 0;
        for (int c = 0; c < 40; c++) {
            int v = d[3 + c] & 0xFF;
            if ((v & SlimCommands.L1_DDS1_DATA) != 0) dds1 |= 1L << c;
            if ((v & SlimCommands.L1_DDS3_DATA) != 0) dds3 |= 1L << c;
            if (c >= 16) {
                int n = 23 - (c - 16);
                if ((v & SlimCommands.L1_PLL1_DATA) != 0) pll1 |= 1 << n;
                if ((v & SlimCommands.L1_PLL3_DATA) != 0) pll3 |= 1 << n;
            }
        }
        state.dds1Word = dds1;
        state.dds3Word = dds3;
        state.pll1Bits = pll1;
        state.pll3Bits = pll3;
        state.pdmInverted = (d[2] & SlimCommands.L2_PDM) != 0;
        state.latch1 = d[3 + 39] & 0xFF;
        state.stepCommands++;
    }

    private byte[] reply(int cmd, int status, int readsDone, long w0, long w1) {
        byte[] r = new byte[16];
        r[0] = (byte) cmd;
        r[1] = (byte) status;
        r[2] = (byte) state.latch1; // port values: not known for the real board, reported for debugging only
        r[3] = (byte) state.latch2;
        r[7] = (byte) readsDone;
        putLe(r, 8, w0);
        putLe(r, 12, w1);
        return r;
    }

    private static void putLe(byte[] b, int o, long v) {
        for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >>> (8 * i));
    }

    @Override
    public synchronized int read(byte[] buf, int timeoutMs) {
        if (closed) throw new MsaException("closed");
        byte[] r = replies.poll();
        if (r == null) throw new MsaTimeoutException("no reply queued");
        System.arraycopy(r, 0, buf, 0, r.length);
        return r.length;
    }

    @Override public int bcdDevice() { return bcdDevice; }
    private String description = "simulated MSA";

    public void setDescription(String d) { description = d; }

    @Override public String description() { return description; }
    @Override public synchronized void close() { closed = true; }
}
