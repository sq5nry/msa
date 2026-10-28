package msa.hw.usb;

import msa.hw.MsaException;
import msa.hw.MsaProtocol;
import msa.hw.MsaTimeoutException;
import msa.hw.MsaTransport;

/**
 * Real hardware: the MSA's FX2 over libusb (WinUSB driver on Windows, no driver on Linux/macOS).
 * Replaces msadll's CyUSB / ezusb DeviceIoControl paths.
 */
public final class LibUsbTransport implements MsaTransport {
    private final LibUsb.Context ctx;
    private final boolean ownsContext;
    private final LibUsb.Handle handle;
    private final int bcd;

    private LibUsbTransport(LibUsb.Context ctx, boolean ownsContext, LibUsb.Handle handle) {
        this.ctx = ctx;
        this.ownsContext = ownsContext;
        this.handle = handle;
        try {
            handle.claim(MsaProtocol.INTERFACE);
            this.bcd = handle.bcdDevice();
        } catch (RuntimeException e) {
            handle.close();
            throw e;
        }
    }

    private static volatile String libraryError;

    /**
     * Loads libusb-1.0 now and turns a failure into an {@link MsaException} with the reason, every time it
     * is asked (a failed class initialiser would otherwise give an ExceptionInInitializerError once and a
     * bare NoClassDefFoundError after that, which callers expecting MsaException do not catch).
     */
    public static void requireLibrary() {
        if (libraryError != null) throw new MsaException(libraryError);
        try {
            Class.forName("msa.hw.usb.LibUsb", true, LibUsbTransport.class.getClassLoader());
        } catch (ExceptionInInitializerError e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            libraryError = c.getMessage() == null ? c.toString() : c.getMessage();
            throw new MsaException(libraryError, c);
        } catch (ClassNotFoundException | LinkageError e) {
            libraryError = "libusb-1.0 could not be loaded: " + e;
            throw new MsaException(libraryError, e);
        }
    }

    /** Opens the first MSA with running firmware (SLIM board 0547:1015, else original board 0547:1013). */
    public static LibUsbTransport open() {
        requireLibrary();
        LibUsb.Context ctx = new LibUsb.Context();
        try {
            LibUsb.Handle h = find(ctx);
            if (h == null) throw new MsaException(loaderHint(ctx));
            return new LibUsbTransport(ctx, true, h);
        } catch (RuntimeException e) {
            ctx.close();
            throw e;
        }
    }

    /** @return the transport, or null if no MSA with firmware is present. The context stays owned by the caller. */
    public static LibUsbTransport open(LibUsb.Context ctx) {
        LibUsb.Handle h = find(ctx);
        return h == null ? null : new LibUsbTransport(ctx, false, h);
    }

    private static LibUsb.Handle find(LibUsb.Context ctx) {
        for (int pid : new int[] {MsaProtocol.PID_SLIM, MsaProtocol.PID_ORIG}) {
            LibUsb.Handle h = ctx.open(MsaProtocol.VID, pid);
            if (h != null) return h;
        }
        return null;
    }

    private static String loaderHint(LibUsb.Context ctx) {
        for (int pid : new int[] {MsaProtocol.PID_SLIM_LOADER, MsaProtocol.PID_ORIG_LOADER}) {
            LibUsb.Handle h = ctx.open(MsaProtocol.VID, pid);
            if (h != null) {
                h.close();
                return String.format("MSA found as 0547:%04x (no firmware running) - run 'load-firmware' first", pid);
            }
        }
        return "no MSA found (0547:1015/1013): is it plugged in and powered? On Windows the MSA needs the WinUSB"
                + " driver, installed once per PC with Zadig (Options > List All Devices, pick the MSA, WinUSB, Replace Driver).";
    }

    @Override
    public void write(byte[] data, int timeoutMs) {
        int rc = handle.bulk(MsaProtocol.EP_OUT, data, data.length, timeoutMs);
        if (rc == LibUsb.ERROR_TIMEOUT) throw new MsaTimeoutException("bulk OUT timed out");
        if (rc == LibUsb.ERROR_PIPE) handle.clearHalt(MsaProtocol.EP_OUT);
        LibUsb.check(rc, "bulk OUT");
        if (handle.lastTransferred() != data.length)
            throw new MsaException("short write: " + handle.lastTransferred() + " of " + data.length);
    }

    @Override
    public int read(byte[] buf, int timeoutMs) {
        int rc = handle.bulk(MsaProtocol.EP_IN, buf, buf.length, timeoutMs);
        if (rc == LibUsb.ERROR_TIMEOUT) throw new MsaTimeoutException("bulk IN timed out");
        if (rc == LibUsb.ERROR_PIPE) handle.clearHalt(MsaProtocol.EP_IN);
        LibUsb.check(rc, "bulk IN");
        return handle.lastTransferred();
    }

    @Override public int bcdDevice() { return bcd; }

    @Override
    public String description() {
        return String.format("USB %04x:%04x bcdDevice %04x", handle.vid, handle.pid, bcd);
    }

    @Override
    public void close() {
        try {
            handle.release(MsaProtocol.INTERFACE);
        } finally {
            handle.close();
            if (ownsContext) ctx.close();
        }
    }
}
