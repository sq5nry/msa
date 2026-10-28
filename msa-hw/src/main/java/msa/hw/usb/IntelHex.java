package msa.hw.usb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Intel HEX reader (record types 00, 01, 02, 04) producing address-contiguous chunks. */
public final class IntelHex {
    public record Chunk(int address, byte[] data) {}

    private IntelHex() {}

    public static List<Chunk> read(Path file) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.US_ASCII));
    }

    /**
     * Parses and merges adjacent records into chunks of at most {@code maxChunk} bytes.
     */
    public static List<Chunk> parse(List<String> lines, int maxChunk) {
        List<Chunk> records = new ArrayList<>();
        int upper = 0;
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String l = raw.strip();
            if (l.isEmpty()) continue;
            if (l.charAt(0) != ':') throw new IllegalArgumentException("line " + lineNo + ": missing ':'");
            byte[] r = java.util.HexFormat.of().parseHex(l.substring(1));
            int n = r[0] & 0xFF;
            if (r.length != n + 5) throw new IllegalArgumentException("line " + lineNo + ": bad length");
            int sum = 0;
            for (byte b : r) sum += b & 0xFF;
            if ((sum & 0xFF) != 0) throw new IllegalArgumentException("line " + lineNo + ": checksum error");
            int addr = (r[1] & 0xFF) << 8 | (r[2] & 0xFF);
            int type = r[3] & 0xFF;
            switch (type) {
                case 0x00 -> {
                    byte[] d = new byte[n];
                    System.arraycopy(r, 4, d, 0, n);
                    records.add(new Chunk(upper + addr, d));
                }
                case 0x01 -> { return merge(records, maxChunk); }
                case 0x02 -> upper = ((r[4] & 0xFF) << 8 | (r[5] & 0xFF)) << 4;
                case 0x04 -> upper = ((r[4] & 0xFF) << 8 | (r[5] & 0xFF)) << 16;
                default -> { /* 03/05 start addresses: irrelevant for the FX2 */ }
            }
        }
        return merge(records, maxChunk);
    }

    public static List<Chunk> parse(List<String> lines) { return parse(lines, 1023); }

    private static List<Chunk> merge(List<Chunk> recs, int maxChunk) {
        List<Chunk> out = new ArrayList<>();
        int start = -1;
        java.io.ByteArrayOutputStream cur = new java.io.ByteArrayOutputStream();
        for (Chunk c : recs) {
            boolean contiguous = start >= 0 && start + cur.size() == c.address();
            if (!contiguous || cur.size() + c.data().length > maxChunk) {
                if (start >= 0) out.add(new Chunk(start, cur.toByteArray()));
                cur.reset();
                start = c.address();
            }
            cur.writeBytes(c.data());
        }
        if (start >= 0) out.add(new Chunk(start, cur.toByteArray()));
        return out;
    }
}
