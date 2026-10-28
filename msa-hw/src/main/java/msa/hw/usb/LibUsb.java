package msa.hw.usb;

import static java.lang.foreign.ValueLayout.*;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import msa.hw.MsaException;

/**
 * Minimal hand-written bindings to libusb-1.0 through the Foreign Function & Memory API (JDK 22+).
 * uint16_t parameters are mapped to JAVA_CHAR (unsigned 16-bit) so values such as 0xE600 are
 * zero-extended as the C ABI expects.
 * Only the dozen functions the MSA needs; no jextract or third-party jar.
 *
 * <p>The library is found via system property {@code msa.libusb}, then the usual names:
 * Windows {@code libusb-1.0.dll} (next to the jar or on PATH), Linux {@code libusb-1.0.so.0},
 * macOS {@code libusb-1.0.0.dylib} (Homebrew).
 */
public final class LibUsb {
    public static final int SUCCESS = 0;
    public static final int ERROR_TIMEOUT = -7;
    public static final int ERROR_PIPE = -9;
    public static final int ERROR_NOT_SUPPORTED = -12;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = load();

    private static final MethodHandle INIT = fn("libusb_init", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle EXIT = fn("libusb_exit", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle OPEN_VID_PID = fn("libusb_open_device_with_vid_pid",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_CHAR, JAVA_CHAR));
    private static final MethodHandle CLOSE = fn("libusb_close", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GET_DEVICE = fn("libusb_get_device", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_DEVICE_DESCRIPTOR = fn("libusb_get_device_descriptor",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SET_AUTO_DETACH = fn("libusb_set_auto_detach_kernel_driver",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CLAIM = fn("libusb_claim_interface", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle RELEASE = fn("libusb_release_interface", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CLEAR_HALT = fn("libusb_clear_halt", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_BYTE));
    private static final MethodHandle BULK = fn("libusb_bulk_transfer",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_BYTE, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CONTROL = fn("libusb_control_transfer",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_BYTE, JAVA_BYTE, JAVA_CHAR, JAVA_CHAR, ADDRESS, JAVA_CHAR, JAVA_INT));
    private static final MethodHandle ERROR_NAME = fn("libusb_error_name", FunctionDescriptor.of(ADDRESS, JAVA_INT));

    private LibUsb() {}

    private static SymbolLookup load() {
        List<String> candidates = new ArrayList<>();
        String explicit = System.getProperty("msa.libusb");
        if (explicit != null) candidates.add(explicit);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            candidates.add("libusb-1.0.dll");
        } else if (os.contains("mac")) {
            candidates.addAll(List.of("/opt/homebrew/lib/libusb-1.0.0.dylib", "/usr/local/lib/libusb-1.0.0.dylib", "libusb-1.0.0.dylib"));
        } else {
            candidates.addAll(List.of("libusb-1.0.so.0", "libusb-1.0.so"));
        }
        Throwable last = null;
        for (String c : candidates) {
            try {
                return SymbolLookup.libraryLookup(c, Arena.global());
            } catch (IllegalArgumentException e) {
                last = e;
            }
        }
        throw new MsaException("libusb-1.0 not found (tried " + candidates + "); set -Dmsa.libusb=<path>", last);
    }

    private static MethodHandle fn(String name, FunctionDescriptor fd) {
        MemorySegment sym = LIB.find(name).orElseThrow(() -> new MsaException("libusb symbol missing: " + name));
        return LINKER.downcallHandle(sym, fd);
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new MsaException("native call failed", t);
    }

    public static String errorName(int code) {
        try {
            MemorySegment s = (MemorySegment) ERROR_NAME.invokeExact(code);
            return s.reinterpret(64).getString(0);
        } catch (Throwable t) {
            return "LIBUSB_ERROR(" + code + ")";
        }
    }

    static void check(int rc, String what) {
        if (rc < 0) throw new MsaException(what + " failed: " + errorName(rc));
    }

    /** A libusb context; one per program is enough. */
    public static final class Context implements AutoCloseable {
        private final MemorySegment ctx;

        public Context() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment pp = a.allocate(ADDRESS);
                check((int) INIT.invokeExact(pp), "libusb_init");
                ctx = pp.get(ADDRESS, 0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** @return an open handle, or {@code null} if no device with this VID:PID is present. */
        public Handle open(int vid, int pid) {
            try {
                MemorySegment h = (MemorySegment) OPEN_VID_PID.invokeExact(ctx, (char) vid, (char) pid);
                return h.equals(MemorySegment.NULL) ? null : new Handle(h, vid, pid);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        @Override
        public void close() {
            try {
                EXIT.invokeExact(ctx);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }
    }

    /** An open device. Not thread-safe: use it from the sweep thread only. */
    public static final class Handle implements AutoCloseable {
        private final MemorySegment h;
        public final int vid, pid;
        private final Arena arena = Arena.ofShared();
        private final MemorySegment buf = arena.allocate(4096);
        private final MemorySegment transferred = arena.allocate(JAVA_INT);
        private boolean closed;

        Handle(MemorySegment h, int vid, int pid) {
            this.h = h;
            this.vid = vid;
            this.pid = pid;
        }

        /** bcdDevice from the cached device descriptor (offset 12 of the 18-byte struct). */
        public int bcdDevice() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment dev = (MemorySegment) GET_DEVICE.invokeExact(h);
                MemorySegment desc = a.allocate(18, 2);
                check((int) GET_DEVICE_DESCRIPTOR.invokeExact(dev, desc), "libusb_get_device_descriptor");
                return desc.get(JAVA_SHORT_UNALIGNED, 12) & 0xFFFF;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Detaches a Linux kernel driver if needed, then claims the interface. */
        public void claim(int iface) {
            try {
                int rc = (int) SET_AUTO_DETACH.invokeExact(h, 1);
                if (rc < 0 && rc != ERROR_NOT_SUPPORTED) check(rc, "libusb_set_auto_detach_kernel_driver");
                check((int) CLAIM.invokeExact(h, iface), "libusb_claim_interface");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public void release(int iface) {
            try {
                int ignored = (int) RELEASE.invokeExact(h, iface);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public void clearHalt(byte endpoint) {
            try {
                int ignored = (int) CLEAR_HALT.invokeExact(h, endpoint);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * One bulk transfer. For OUT endpoints {@code data} is sent; for IN endpoints it is filled.
         *
         * @return libusb return code (0 or negative error); {@link #lastTransferred()} gives the byte count
         */
        public int bulk(byte endpoint, byte[] data, int len, int timeoutMs) {
            if (len > buf.byteSize()) throw new IllegalArgumentException("transfer too large");
            boolean in = (endpoint & 0x80) != 0;
            if (!in) MemorySegment.copy(data, 0, buf, JAVA_BYTE, 0, len);
            try {
                int rc = (int) BULK.invokeExact(h, endpoint, buf, len, transferred, timeoutMs);
                if (in) MemorySegment.copy(buf, JAVA_BYTE, 0, data, 0, lastTransferred());
                return rc;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public int lastTransferred() { return transferred.get(JAVA_INT, 0); }

        /** Host-to-device control transfer; returns bytes sent or a negative libusb error. */
        public int controlOut(int requestType, int request, int value, int index, byte[] data, int timeoutMs) {
            if (data.length > buf.byteSize()) throw new IllegalArgumentException("transfer too large");
            MemorySegment.copy(data, 0, buf, JAVA_BYTE, 0, data.length);
            try {
                return (int) CONTROL.invokeExact(h, (byte) requestType, (byte) request, (char) value, (char) index,
                        buf, (char) data.length, timeoutMs);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                CLOSE.invokeExact(h);
            } catch (Throwable t) {
                throw rethrow(t);
            } finally {
                arena.close();
            }
        }
    }
}
