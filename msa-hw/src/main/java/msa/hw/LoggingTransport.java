package msa.hw;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Wraps a transport and logs every transfer in the same text format as msadll.cpp's
 * LOG_COMMANDS option (c:\vnalog.txt), so logs from the old and the new program can be diffed:
 * <pre>
 * Write Length 4: Message = a1 01 00 21
 * Read command, count = 16 Data =b2 20 00 00 00 00 00 02 34 12 00 00 78 56 00 00
 * </pre>
 * (Each byte is followed by a space; the trailing spaces are kept for fidelity.)
 */
public final class LoggingTransport implements MsaTransport {
    private final MsaTransport inner;
    private final Writer out;

    public LoggingTransport(MsaTransport inner, Writer out) {
        this.inner = inner;
        this.out = out;
    }

    public static LoggingTransport toFile(MsaTransport inner, Path file) {
        try {
            return new LoggingTransport(inner, Files.newBufferedWriter(file, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void write(byte[] data, int timeoutMs) {
        StringBuilder sb = new StringBuilder("Write Length ").append(data.length).append(": Message = ");
        for (byte b : data) sb.append(String.format("%02x ", b & 0xFF));
        line(sb);
        inner.write(data, timeoutMs);
    }

    @Override
    public int read(byte[] buf, int timeoutMs) {
        int n;
        try {
            n = inner.read(buf, timeoutMs);
        } catch (MsaTimeoutException e) {
            // msadll never logged this; '#' lets you filter it out before diffing
            line(new StringBuilder("# read timeout (").append(timeoutMs).append(" ms)"));
            throw e;
        }
        StringBuilder sb = new StringBuilder("Read command, count = ").append(n).append(" Data =");
        if (n > 0 && n < 255) for (int i = 0; i < n; i++) sb.append(String.format("%02x ", buf[i] & 0xFF));
        line(sb);
        return n;
    }

    private void line(CharSequence s) {
        try {
            out.append(s).append('\n');
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override public int bcdDevice() { return inner.bcdDevice(); }
    @Override public String description() { return inner.description() + " (logged)"; }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException ignored) {
            // closing the device matters more than the log
        }
        inner.close();
    }
}
