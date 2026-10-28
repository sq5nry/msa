package msa.hw.usb;

import java.util.List;

import msa.hw.MsaException;

/**
 * Downloads firmware into the FX2's internal RAM, the same job the CyFwLoad driver (".spt" script)
 * does on Windows when the board enumerates as 0547:1014 / 1012 ("before download").
 * Standard Cypress procedure: hold the 8051 in reset (CPUCS = 1), write RAM with vendor request 0xA0,
 * release reset (CPUCS = 0). The device then re-enumerates with the MSA firmware's own PID.
 *
 * <p>Only internal RAM (0x0000-0x3FFF) can be written this way; msa-fw-cb-v33.hex ends at 0x1F82.
 * Not needed if the board's EEPROM already boots the firmware (it then appears as 0547:1015 directly).
 */
public final class Fx2Loader {
    /** Anything that can do a host-to-device vendor control transfer (lets the loader be unit-tested). */
    @FunctionalInterface
    public interface ControlOut {
        int send(int requestType, int request, int value, int index, byte[] data, int timeoutMs);
    }

    public static final int REQ_FIRMWARE_LOAD = 0xA0;
    /** CPUCS register (FX2 / FX2LP). */
    public static final int CPUCS = 0xE600;
    private static final int VENDOR_OUT = 0x40; // LIBUSB_REQUEST_TYPE_VENDOR | RECIPIENT_DEVICE | ENDPOINT_OUT
    private static final int MAX_INTERNAL_RAM = 0x4000;

    private Fx2Loader() {}

    public static void load(ControlOut dev, List<IntelHex.Chunk> image) {
        for (IntelHex.Chunk c : image) {
            if (c.address() + c.data().length > MAX_INTERNAL_RAM)
                throw new MsaException(String.format("firmware writes 0x%04x: outside FX2 internal RAM", c.address()));
        }
        cpuReset(dev, true);
        for (IntelHex.Chunk c : image) {
            int rc = dev.send(VENDOR_OUT, REQ_FIRMWARE_LOAD, c.address(), 0, c.data(), 1000);
            if (rc != c.data().length)
                throw new MsaException(String.format("RAM write at 0x%04x failed: %s", c.address(), LibUsb.errorName(rc)));
        }
        cpuReset(dev, false);
    }

    private static void cpuReset(ControlOut dev, boolean hold) {
        int rc = dev.send(VENDOR_OUT, REQ_FIRMWARE_LOAD, CPUCS, 0, new byte[] {(byte) (hold ? 1 : 0)}, 1000);
        // releasing reset may legitimately fail: the device can drop off the bus as it re-enumerates
        if (rc != 1 && hold) throw new MsaException("could not hold 8051 in reset: " + LibUsb.errorName(rc));
    }
}
