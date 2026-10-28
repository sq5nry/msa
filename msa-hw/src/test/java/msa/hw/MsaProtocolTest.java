package msa.hw;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MsaProtocolTest {

    @Test
    void hexConversionMatchesWriteString() {
        // "A10100" + ToHex$(filtbank + 1) with filtbank = 32
        assertArrayEquals(new byte[] {(byte) 0xA1, 0x01, 0x00, 0x21}, MsaProtocol.fromHex("A10100" + MsaProtocol.hex2(33)));
        assertArrayEquals(MsaProtocol.fromHex("a10100ff"), MsaProtocol.fromHex("A10100FF"), "case-insensitive like isxdigit/toupper");
        assertThrows(IllegalArgumentException.class, () -> MsaProtocol.fromHex("A1010"));
        assertThrows(IllegalArgumentException.class, () -> MsaProtocol.fromHex("A10100G1"));
    }

    @Test
    void commandLayoutIsCodeCountModeData() {
        assertEquals("A30200" + "4240", MsaProtocol.toHex(MsaProtocol.command(0xA3, 0, 0x42, 0x40)));
    }

    @Test
    void adcCommandIsB2WithBasicDefaults() {
        // UsbAdcControl = 3, 1, 4, 16, 1  ->  B2 01 04 10 01
        assertEquals("B201041001", MsaProtocol.toHex(MsaProtocol.adcCommand(AdcConfig.defaults())));
        assertEquals("B2010410FF", MsaProtocol.toHex(MsaProtocol.adcCommand(AdcConfig.defaults().withAverage(255))));
        assertThrows(IllegalArgumentException.class, () -> new AdcConfig(4, 1, 4, 16, 1));
    }

    @Test
    void parsesRxBufferLittleEndian() {
        byte[] b = MsaProtocol.fromHex("B2200102030405" + "02" + "34120000" + "FEFF0000");
        MsaProtocol.RxFrame f = MsaProtocol.parseRx(b, b.length);
        assertEquals(0xB2, f.lastCommand());
        assertEquals(0x20, f.status());
        assertEquals(5, f.portE());
        assertEquals(2, f.adcReadsDone());
        assertEquals(0x1234, f.word(0));
        assertEquals(65534, f.word(1));
        assertEquals(0, f.word(5));
    }
}
