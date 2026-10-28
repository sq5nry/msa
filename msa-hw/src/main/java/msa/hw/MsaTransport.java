package msa.hw;

/**
 * Raw byte pipe to the FX2: bulk OUT on EP2, bulk IN on EP6.
 * Implemented by {@link msa.hw.usb.LibUsbTransport} (real hardware) and
 * {@link msa.hw.sim.SimulatedTransport} (no hardware).
 */
public interface MsaTransport extends AutoCloseable {
    /** Sends one command as one bulk transfer. */
    void write(byte[] data, int timeoutMs);

    /**
     * Reads one reply into {@code buf}.
     *
     * @return number of bytes received
     * @throws MsaTimeoutException if nothing arrived within {@code timeoutMs}
     */
    int read(byte[] buf, int timeoutMs);

    /** bcdDevice from the USB device descriptor. */
    int bcdDevice();

    /** Human-readable name, e.g. "USB 0547:1015". */
    String description();

    @Override
    void close();
}
