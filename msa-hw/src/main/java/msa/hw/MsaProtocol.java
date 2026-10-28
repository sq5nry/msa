package msa.hw;

import java.util.HexFormat;

/**
 * Wire protocol between the PC and the MSA's Cypress FX2 USB interface (G8KBB firmware),
 * as implemented by msadll.dll (msadll_source_18-08-2010) and used by spectrumanalyzer.bas v118.
 *
 * <p>Every command is one bulk OUT transfer on EP2; replies arrive as one bulk IN transfer on EP6.
 * Command layout: {@code [code, count, mode, data[count]]} (the DLL's MSA_TXBUFFER_SET), so the
 * message is {@code count + 3} bytes long. The hex strings in the BASIC code ("A10100xx" ...)
 * are exactly these bytes.
 */
public final class MsaProtocol {
    private MsaProtocol() {}

    // ---- USB identity (from the msa-usb-0.03-*.inf files and the firmware's device descriptor)
    public static final int VID = 0x0547;
    /** Original control board, before / after firmware download. */
    public static final int PID_ORIG_LOADER = 0x1012, PID_ORIG = 0x1013;
    /** SLIM control board, before / after firmware download. */
    public static final int PID_SLIM_LOADER = 0x1014, PID_SLIM = 0x1015;

    public static final int INTERFACE = 0;
    public static final byte EP_OUT = 0x02;
    public static final byte EP_IN = (byte) 0x86;
    /** Largest IN packet at high speed; the reply itself is much shorter. */
    public static final int RX_BUFFER_SIZE = 512;

    /** msadll.h: {@code #define DLL_VERSION 02}; UsbMSAGetVersions returns (bcdDevice & 0xFF) + (DLL_VERSION << 8). */
    public static final int DLL_VERSION = 2;

    /** msadll caps a WriteString message at 254 bytes. */
    public static final int MAX_MESSAGE = 254;

    // ---- Command codes as used by spectrumanalyzer.bas v118 (SLIM control board, EEPROM firmware v33)
    /** Control board latch 3: ADC multiplexer ([SetADCmux]). */
    public static final int CMD_LATCH3 = 0xA0;
    /** Latch 1 ("P1"): bit0 = common serial clock, bits 1..4 = module data lines, bits 5..7 = filter bank. */
    public static final int CMD_LATCH1 = 0xA1;
    /** Latch 4: switches (RBW / path / TR-REFL / FR-REV). */
    public static final int CMD_LATCH4 = 0xA2;
    /** Latch 2 ("P2"): LE / FQUD strobes and PDM state (bit 6). */
    public static final int CMD_LATCH2 = 0xA3;
    /** Raw Cypress port write, only used by the LPT/port test window. */
    public static final int CMD_RAW_PORTS = 0xA6;
    /** Firmware >= v28: load 40 bits into all modules, then latch them (LE/FQUD) — one command per sweep step. */
    public static final int CMD_STEP = 0xA7;
    /** ADC read: 0xB0 + (adcs - 1); BASIC always uses adcs = 3, i.e. 0xB2. */
    public static final int CMD_READ_ADC_BASE = 0xB0;

    /** Mode byte: data bytes written to the latch as-is. */
    public static final int MODE_RAW = 0x00;
    /** Mode byte: firmware pulses the common clock (latch 1 bit 0) after each byte. */
    public static final int MODE_AUTOCLOCK = 0x01;

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /**
     * Same conversion as UsbMSADeviceWriteString: pairs of hex digits, case-insensitive.
     * Unlike the DLL, the length comes from the string (BASIC passed it separately and
     * a mismatch would silently send garbage).
     */
    public static byte[] fromHex(String hex) {
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("odd number of hex digits: " + hex);
        if (hex.length() / 2 > MAX_MESSAGE) throw new IllegalArgumentException("message longer than " + MAX_MESSAGE + " bytes");
        if (hex.length() < 4) throw new IllegalArgumentException("message needs at least code and count bytes");
        return HexFormat.of().parseHex(hex);
    }

    public static String toHex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    /** Equivalent of BASIC's ToHex$(value): two upper-case hex digits. */
    public static String hex2(int value) {
        return HEX.toHexDigits((byte) value);
    }

    /** Builds {@code [code, n, mode, data...]}. */
    public static byte[] command(int code, int mode, int... data) {
        if (data.length + 3 > MAX_MESSAGE) throw new IllegalArgumentException("too many data bytes: " + data.length);
        byte[] m = new byte[data.length + 3];
        m[0] = (byte) code;
        m[1] = (byte) data.length;
        m[2] = (byte) mode;
        for (int i = 0; i < data.length; i++) m[i + 3] = (byte) data[i];
        return m;
    }

    /** UsbMSADeviceReadAdcsStruct: {@code [0xB0+adcs-1, clocking, delay, bits, average]}. */
    public static byte[] adcCommand(AdcConfig c) {
        return new byte[] {
            (byte) (CMD_READ_ADC_BASE + c.adcs() - 1),
            (byte) c.clocking(), (byte) c.delay(), (byte) c.bits(), (byte) c.average()
        };
    }

    /** One reply from the FX2 (msadll's MSA_RXBUFFER). */
    public record RxFrame(int lastCommand, int status, int portA, int portB, int portC, int portD, int portE,
                          int adcReadsDone, long[] words) {
        /** MSA_RXBUFFER.return_status bit: data[] holds ADC data. */
        public static final int STATUS_ADC_DATA_READY = 0x20;

        public long word(int i) { return i < words.length ? words[i] : 0; }
    }

    /** Parses a reply: 8 header bytes followed by 32-bit little-endian ADC words. */
    public static RxFrame parseRx(byte[] buf, int len) {
        if (len < 8) throw new IllegalArgumentException("short reply: " + len + " bytes");
        int u0 = buf[0] & 0xFF, u1 = buf[1] & 0xFF, readsDone = buf[7] & 0xFF;
        int nWords = Math.min((len - 8) / 4, 60); // data[240]
        long[] words = new long[nWords];
        for (int i = 0; i < nWords; i++) {
            int o = 8 + 4 * i;
            words[i] = (buf[o] & 0xFFL) | (buf[o + 1] & 0xFFL) << 8 | (buf[o + 2] & 0xFFL) << 16 | (buf[o + 3] & 0xFFL) << 24;
        }
        return new RxFrame(u0, u1, buf[2] & 0xFF, buf[3] & 0xFF, buf[4] & 0xFF, buf[5] & 0xFF, buf[6] & 0xFF,
                readsDone, words);
    }
}
