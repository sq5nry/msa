package msa.hw.usb;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class Fx2LoaderTest {

    private static Path firmware() throws Exception {
        return Path.of(Fx2LoaderTest.class.getResource("/msa-fw-cb-v33.hex").toURI());
    }

    @Test
    void parsesMsaFirmware() throws Exception {
        List<IntelHex.Chunk> img = IntelHex.read(firmware());
        int end = img.stream().mapToInt(c -> c.address() + c.data().length).max().orElseThrow();
        assertEquals(0x1F82, end);
        assertTrue(img.stream().allMatch(c -> c.data().length <= 1023));
        // device descriptor at 0x1F00: VID 0547, PID 1015, bcdDevice 1024
        byte[] mem = new byte[0x2000];
        for (IntelHex.Chunk c : img) System.arraycopy(c.data(), 0, mem, c.address(), c.data().length);
        assertEquals(0x0547, (mem[0x1F08] & 0xFF) | (mem[0x1F09] & 0xFF) << 8);
        assertEquals(0x1015, (mem[0x1F0A] & 0xFF) | (mem[0x1F0B] & 0xFF) << 8);
        assertEquals(0x1024, (mem[0x1F0C] & 0xFF) | (mem[0x1F0D] & 0xFF) << 8);
    }

    @Test
    void rejectsBadChecksum() {
        assertThrows(IllegalArgumentException.class, () -> IntelHex.parse(List.of(":0400000002005C326D")));
    }

    @Test
    void loadSequenceHoldsResetWritesAndReleases() throws Exception {
        List<IntelHex.Chunk> img = IntelHex.read(firmware());
        record Call(int type, int req, int value, int len, byte first) {}
        List<Call> calls = new ArrayList<>();
        Fx2Loader.load((t, r, v, i, d, to) -> {
            calls.add(new Call(t, r, v, d.length, d[0]));
            return d.length;
        }, img);
        assertEquals(new Call(0x40, 0xA0, 0xE600, 1, (byte) 1), calls.get(0));
        assertEquals(new Call(0x40, 0xA0, 0xE600, 1, (byte) 0), calls.get(calls.size() - 1));
        int written = calls.subList(1, calls.size() - 1).stream().mapToInt(Call::len).sum();
        assertEquals(img.stream().mapToInt(c -> c.data().length).sum(), written);
    }
}
