package msa.hw;

import java.util.Objects;

/**
 * The msadll.dll replacement: MSA command/ADC logic on top of any {@link MsaTransport}.
 *
 * <p>Behaviour follows msadll.cpp, with two deliberate changes:
 * <ul>
 *   <li>every transfer has a timeout, and a timed-out ADC read is retried before an exception
 *       is raised (msadll could block for ever, then gave up silently and BASIC kept stale data);</li>
 *   <li>stale IN data is drained when the device is opened and before each retry.</li>
 * </ul>
 */
public final class UsbMsa implements MsaHardware {

    /** msadll reads at most 5 replies while waiting for ADC data. */
    public static final int MAX_READS_PER_ADC = 5;

    private final MsaTransport transport;
    private final int writeTimeoutMs;
    private final int readTimeoutMs;
    private final int adcRetries;
    private final byte[] rx = new byte[MsaProtocol.RX_BUFFER_SIZE];
    private long adcReads, skippedFrames;

    public UsbMsa(MsaTransport transport) {
        this(transport, 500, 500, 1);
    }

    public UsbMsa(MsaTransport transport, int writeTimeoutMs, int readTimeoutMs, int adcRetries) {
        this.transport = Objects.requireNonNull(transport);
        this.writeTimeoutMs = writeTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.adcRetries = adcRetries;
        drain();
    }

    public MsaTransport transport() { return transport; }

    @Override
    public int bcdDevice() { return transport.bcdDevice(); }

    @Override
    public int versions() {
        return (transport.bcdDevice() & 0xFF) + (MsaProtocol.DLL_VERSION << 8);
    }

    @Override
    public void write(byte[] command) {
        if (command.length < 2 || command.length > MsaProtocol.MAX_MESSAGE)
            throw new IllegalArgumentException("bad command length " + command.length);
        transport.write(command, writeTimeoutMs);
    }

    @Override
    public AdcResult readAdcs(AdcConfig config) {
        byte[] cmd = MsaProtocol.adcCommand(config);
        MsaException last = null;
        for (int attempt = 0; attempt <= adcRetries; attempt++) {
            if (attempt > 0) drain();
            try {
                return readAdcsOnce(cmd);
            } catch (MsaTimeoutException e) {
                last = e;
            }
        }
        throw last;
    }

    private AdcResult readAdcsOnce(byte[] cmd) {
        transport.write(cmd, writeTimeoutMs);
        for (int i = 0; i < MAX_READS_PER_ADC; i++) {
            int n = transport.read(rx, readTimeoutMs);
            if (n < 8) continue; // not a valid MSA_RXBUFFER; msadll would have used it anyway
            MsaProtocol.RxFrame f = MsaProtocol.parseRx(rx, n);
            if (f.adcReadsDone() == 0) skippedFrames++;
            if (f.adcReadsDone() != 0) {
                adcReads++;
                // msadll: pResults[1] = data[0..3]; pResults[2] = data[4..7] only if reads_done > 1
                long adc2 = f.adcReadsDone() > 1 ? f.word(1) : 0;
                return new AdcResult(f.adcReadsDone(), f.word(0), adc2, f.status());
            }
        }
        throw new MsaException("no ADC data after " + MAX_READS_PER_ADC + " replies");
    }

    /** ADC reads completed since open. */
    public long adcReads() { return adcReads; }

    /** Status-only frames (reads_done = 0) skipped while waiting for ADC data, since open. */
    public long skippedFrames() { return skippedFrames; }

    /** Discards replies left over from an interrupted transaction. */
    private void drain() {
        for (int i = 0; i < 8; i++) {
            try {
                transport.read(rx, 20);
            } catch (MsaTimeoutException e) {
                return;
            }
        }
    }

    @Override
    public void close() { transport.close(); }
}
