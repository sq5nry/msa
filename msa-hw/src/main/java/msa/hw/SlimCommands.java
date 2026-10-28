package msa.hw;

import static msa.hw.MsaProtocol.*;

import java.util.ArrayList;
import java.util.List;

/**
 * USB command sequences for the SLIM control board (cb = 3), ported 1:1 from spectrumanalyzer.bas v118.
 * Each builder returns the exact bytes the BASIC code passes to UsbMSADeviceWriteString, in order,
 * so they can be checked against a msadll/USBPcap log before they ever touch hardware.
 * The BASIC routine each one comes from is named in its Javadoc.
 */
public final class SlimCommands {
    private SlimCommands() {}

    // ---- Latch 1 ("P1") bits
    /** Common serial clock (WCLK) for all modules. */
    public static final int L1_CLOCK = 1;
    public static final int L1_PLL1_DATA = 2, L1_DDS1_DATA = 4, L1_PLL3_DATA = 8, L1_DDS3_DATA = 16, L1_PLL2_DATA = 16;
    /** Filter bank address bits FiltA0 / FiltA1 and the filter latch pulse. */
    public static final int L1_FILT_A0 = 32, L1_FILT_A1 = 64, L1_FILT_LATCH = 128;

    // ---- Latch 2 ("P2") bits (cb=3 values: le1=1, le2=16, le3=4, fqud1=2, fqud3=8)
    public static final int L2_LE1 = 1, L2_FQUD1 = 2, L2_LE3 = 4, L2_FQUD3 = 8, L2_LE2 = 16;
    /** Latch signal for the original PDM module. */
    public static final int L2_PDM_LATCH = 32;
    /** PDM state: 0 = normal, 1 = inverted (180 degrees). */
    public static final int L2_PDM = 64;

    /** One USB write, optionally followed by a pause (BASIC "call uSleep"). */
    public record Op(byte[] command, int sleepAfterMs) {
        public String hex() { return toHex(command); }
    }

    /** Sends a sequence. */
    public static void run(MsaHardware hw, List<Op> ops) {
        for (Op op : ops) {
            hw.write(op.command());
            if (op.sleepAfterMs() > 0) {
                try {
                    Thread.sleep(op.sleepAfterMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MsaException("interrupted", e);
                }
            }
        }
    }

    private static Op op(byte[] c) { return new Op(c, 0); }

    /** filtIndex 1..4 -> fbank (sub SelectFilter: FiltA1*64 + FiltA0*32). */
    public static int filterBank(int filtIndex) {
        if (filtIndex < 1 || filtIndex > 4) throw new IllegalArgumentException("filter path must be 1..4");
        int a1 = filtIndex <= 2 ? 0 : 1;
        int a0 = (filtIndex == 1 || filtIndex == 3) ? 0 : 1;
        return a1 * L1_FILT_A1 + a0 * L1_FILT_A0;
    }

    /** sub CommandFilterSlimCBUSB: latch pulse high (held 500 ms, as in v118), then low. */
    public static List<Op> commandFilter(int fbank) {
        return List.of(
            new Op(command(CMD_LATCH1, MODE_RAW, fbank, fbank + L1_FILT_LATCH), 500),
            op(command(CMD_LATCH1, MODE_RAW, fbank)));
    }

    /**
     * [ResetDDS1serUSB] / [ResetDDS3serUSB] -> [ResetEitherDDSserUSB]: put an AD9850/51 into serial
     * mode and command it to 0 Hz, without disturbing the filter bank or PDM state.
     *
     * @param dds 1 or 3
     */
    public static List<Op> resetSerialDds(int dds, int filtbank, int pdm) {
        int fqud = switch (dds) {
            case 1 -> L2_FQUD1;
            case 3 -> L2_FQUD3;
            default -> throw new IllegalArgumentException("DDS must be 1 or 3");
        };
        int p = pdm * L2_PDM;
        byte[] fqudPulse = command(CMD_LATCH2, MODE_RAW, p + fqud, p);   // USBwrbuf2$
        List<Op> ops = new ArrayList<>();
        // reset DDS to parallel: WCLK up, FQUD pulse, WCLK down
        ops.add(op(command(CMD_LATCH1, MODE_RAW, filtbank + L1_CLOCK)));
        ops.add(op(fqudPulse));
        ops.add(op(command(CMD_LATCH1, MODE_RAW, filtbank)));
        // invoke serial mode: WCLK pulse, FQUD pulse
        ops.add(op(command(CMD_LATCH1, MODE_RAW, filtbank + L1_CLOCK, filtbank)));
        ops.add(op(fqudPulse));
        // flush: clock 40 zero bits into the modules, then FQUD -> 0 Hz
        int[] zeros = new int[40];
        java.util.Arrays.fill(zeros, filtbank);
        ops.add(op(command(CMD_LATCH1, MODE_AUTOCLOCK, zeros)));
        ops.add(op(fqudPulse));
        return ops;
    }

    /**
     * [CommandPLLslimUSB] with [CreateUSBwrbuf]: shift 24 bits (N23 first) into one PLL, then pulse its LE.
     * Used for the PLL R initialisations and the PLL2 N command.
     *
     * @param bits24    the 24-bit PLL word (bit 23 is sent first, bit 0 is the address/control bit)
     * @param dataValue L1_PLL1_DATA, L1_PLL2_DATA or L1_PLL3_DATA
     * @param leValue   L2_LE1, L2_LE2 or L2_LE3
     */
    public static List<Op> commandPll(int bits24, int dataValue, int filtbank, int leValue, int pdm) {
        int[] d = new int[24];
        for (int i = 0; i < 24; i++) d[i] = ((bits24 >>> (23 - i)) & 1) * dataValue + filtbank;
        int p = pdm * L2_PDM;
        return List.of(
            op(command(CMD_LATCH1, MODE_AUTOCLOCK, d)),
            op(command(CMD_LATCH2, MODE_RAW, p + leValue, p)));
    }

    /** [CommandPDMSlimUSB]: set PDM state and pulse the original-PDM latch line. */
    public static List<Op> commandPdm(int pdm) {
        int i = pdm * L2_PDM;
        return List.of(op(command(CMD_LATCH2, MODE_RAW, i, i + L2_PDM_LATCH, i)));
    }

    /** [SetADCmux]: select channel 0..7 of the SLIM_ADCMUX (0 = magnitude, 1 = phase). */
    public static List<Op> setAdcMux(int channel) {
        return List.of(op(command(CMD_LATCH3, MODE_RAW, channel)));
    }

    // ---- Latch 4: latching relays, all switched by one pulse (function switchLatchBits, v116)
    //   bit 0-1  VS0/VS1  video filter address (videoFilterNum - 1: 0 Wide, 1 Mid, 2 Narrow, 3 XNarrow)
    //   bit 2-3  BS0/BS1  band: 1 = 1G (0-1 GHz), 2 = 2G (1-2 GHz), 3 = 3G (2-3 GHz)
    //   bit 4    FR       DUT direction: 0 forward, 1 reverse
    //   bit 5    TR       VNA: 0 transmission, 1 reflection
    //   bit 6    spare
    //   bit 7    PS       latch pulse, normally high; pulsed low to make the relays latch
    public static final int L4_BAND_SHIFT = 2, L4_FR = 16, L4_TR = 32, L4_PULSE = 128;

    /** Video filters in Latch 4 address order (config.txt "VideoFilters=" order). */
    public enum VideoFilter { WIDE, MID, NARROW, XNARROW }

    /** sub SelectLatchedSwitches: switchData = videoFilterAddress + 4*band + 16*FR + 32*TR + 128. */
    public static int switchData(VideoFilter video, int band, boolean reverse, boolean reflection) {
        if (band < 1 || band > 3) throw new IllegalArgumentException("band must be 1..3 (0 = auto is resolved by the sweep)");
        return video.ordinal() + (band << L4_BAND_SHIFT) + (reverse ? L4_FR : 0) + (reflection ? L4_TR : 0) + L4_PULSE;
    }

    /** Band (1..3) held in a switch byte. */
    public static int bandOf(int switchData) { return (switchData >> L4_BAND_SHIFT) & 3; }

    /** Video filter held in a switch byte. */
    public static VideoFilter videoOf(int switchData) { return VideoFilter.values()[switchData & 3]; }

    /** sub SelectLatchedSwitches (case 3): data with pulse high, 500 ms, pulse low, pulse high. */
    public static List<Op> commandSwitches(int switchData) {
        if (switchData < 128 || switchData > 255)
            throw new IllegalArgumentException("switchData must include the latch-pulse bit (128)");
        byte[] high = command(CMD_LATCH4, MODE_RAW, switchData);
        byte[] low = command(CMD_LATCH4, MODE_RAW, switchData - 128);
        return List.of(new Op(high, 500), op(low), op(high));
    }

    /**
     * [CreateCmdAllArray] + [CommandAllSlimsUSB] (firmware >= v28): one "A7" command that loads
     * DDS1, DDS3, PLL1 and PLL3 for a sweep step and latches them.
     *
     * <p>Clock 0..39 carries DDS word bits W0..W39 (LSB first); PLL bits N23..N0 ride on clocks 16..39.
     *
     * @param dds1Word 40-bit serial word: tuning word in bits 0..31, control/phase in bits 32..39
     * @param pll1Bits 24-bit PLL1 word, or 0 if not commanded this step
     */
    public static Op stepCommand(long dds1Word, long dds3Word, int pll1Bits, int pll3Bits, int filtbank, int pdm) {
        int[] d = new int[40];
        for (int c = 0; c < 40; c++) {
            int v = (int) ((dds1Word >>> c) & 1) * L1_DDS1_DATA + (int) ((dds3Word >>> c) & 1) * L1_DDS3_DATA;
            if (c >= 16) {
                int n = 23 - (c - 16);
                v += ((pll1Bits >>> n) & 1) * L1_PLL1_DATA + ((pll3Bits >>> n) & 1) * L1_PLL3_DATA;
            }
            d[c] = v + filtbank;
        }
        // "A7" + ToHex$(40) + ToHex$(pdmcommand) + 40 bytes: the mode byte position carries the PDM state
        return op(command(CMD_STEP, pdm * L2_PDM, d));
    }

    /** DDS tuning word, rounded to nearest exactly as the BASIC code does. Frequencies in any one unit. */
    public static long ddsTuningWord(double fOut, double ddsClock) {
        if (fOut < 0 || fOut >= ddsClock / 2) throw new IllegalArgumentException("ddsoutput must be < ddsclock/2");
        double full = fOut * 4294967296.0 / ddsClock;
        long base = (long) Math.floor(full);
        if (full - base >= 0.5) base++;
        return base;
    }

    /** Actual DDS output for a tuning word: base * ddsclock / 2^32. */
    public static double ddsOutput(long base, double ddsClock) {
        return base * ddsClock / 4294967296.0;
    }
}
