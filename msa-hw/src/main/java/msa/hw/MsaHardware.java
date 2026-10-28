package msa.hw;

/**
 * Everything spectrumanalyzer.bas v118 asks of msadll.dll, reduced to what it actually uses:
 * UsbMSAInitialise / UsbMSAGetVersions / UsbMSADeviceWriteString / UsbMSADeviceReadAdcsStruct / UsbMSARelease.
 */
public interface MsaHardware extends AutoCloseable {
    /** Same value as UsbMSAGetVersions: (bcdDevice & 0xFF) + (DLL_VERSION << 8). */
    int versions();

    /** bcdDevice of the running firmware. */
    int bcdDevice();

    /** Sends one command (UsbMSADeviceWriteString). */
    void write(byte[] command);

    /** Sends a command given as the hex string used in the BASIC code, e.g. "A10100" + hex2(filtbank). */
    default void write(String hex) { write(MsaProtocol.fromHex(hex)); }

    /** Triggers and reads the ADCs (UsbMSADeviceReadAdcsStruct). */
    AdcResult readAdcs(AdcConfig config);

    @Override
    void close();
}
