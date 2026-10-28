package msa.hw;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Expected strings are written out by hand from the BASIC source, so a mistake in the port shows up
 * as a mismatch with the original program, not with itself.
 */
class SlimCommandsTest {

    private static List<String> hex(List<SlimCommands.Op> ops) {
        return ops.stream().map(SlimCommands.Op::hex).toList();
    }

    @Test
    void resetDds1MatchesResetEitherDDSserUSB() {
        // filtbank = 64 (path 3), pdm = 1  ->  pdmcmd*64 = 0x40, FQUD1 = 2
        String fqud = "A30200" + "42" + "40";
        String flush = "A12801" + "40".repeat(40);
        assertEquals(List.of(
                "A10100" + "41",          // WCLK high + filtbank   (4 bytes)
                fqud,                     // FQUD pulse             (5)
                "A10100" + "40",          // WCLK low               (4)
                "A10200" + "41" + "40",   // WCLK pulse             (5)
                fqud,                     //                        (5)
                flush,                    // 40 zero bits, autoclock (43)
                fqud),                    //                        (5)
                hex(SlimCommands.resetSerialDds(1, 64, 1)));
        int[] lengths = SlimCommands.resetSerialDds(1, 64, 1).stream().mapToInt(o -> o.command().length).toArray();
        assertArrayEquals(new int[] {4, 5, 4, 5, 5, 43, 5}, lengths, "message_size values passed by the BASIC code");
    }

    @Test
    void resetDds3UsesFqud3() {
        assertEquals("A30200" + "08" + "00", hex(SlimCommands.resetSerialDds(3, 0, 0)).get(1));
    }

    @Test
    void pllCommandMatchesCreateUSBwrbuf() {
        // PLL2: datavalue = 16, levalue = 16; word 0x800001 -> N23 = 1 ... N0 = 1; filtbank 32
        List<String> h = hex(SlimCommands.commandPll(0x800001, SlimCommands.L1_PLL2_DATA, 32, SlimCommands.L2_LE2, 0));
        assertEquals("A11801" + "30" + "20".repeat(22) + "30", h.get(0));
        assertEquals(27, h.get(0).length() / 2, "message_size 27 in [CommandPLLslimUSB]");
        assertEquals("A30200" + "10" + "00", h.get(1));
    }

    @Test
    void filterAndSwitchesAndPdm() {
        assertEquals(0, SlimCommands.filterBank(1));
        assertEquals(32, SlimCommands.filterBank(2));
        assertEquals(64, SlimCommands.filterBank(3));
        assertEquals(96, SlimCommands.filterBank(4));
        assertEquals(List.of("A10200" + "60" + "E0", "A10100" + "60"), hex(SlimCommands.commandFilter(96)));
        assertEquals(500, SlimCommands.commandFilter(96).get(0).sleepAfterMs());
        assertEquals(List.of("A20100" + "85", "A20100" + "05", "A20100" + "85"), hex(SlimCommands.commandSwitches(0x85)));
        assertEquals(List.of("A30300" + "40" + "60" + "40"), hex(SlimCommands.commandPdm(1)));
        assertEquals(List.of("A00100" + "01"), hex(SlimCommands.setAdcMux(1)));
    }

    @Test
    void switchByteMatchesSelectLatchedSwitches() {
        // switchData = videoFilterAddress + 4*desiredFreqBand + 16*switchFR + 32*switchTR + 128
        assertEquals(0 + 4 + 128, SlimCommands.switchData(SlimCommands.VideoFilter.WIDE, 1, false, false));
        assertEquals(3 + 12 + 16 + 32 + 128, SlimCommands.switchData(SlimCommands.VideoFilter.XNARROW, 3, true, true));
        assertEquals(List.of("A20100" + "89", "A20100" + "09", "A20100" + "89"),
                hex(SlimCommands.commandSwitches(SlimCommands.switchData(SlimCommands.VideoFilter.MID, 2, false, false))));
        assertEquals(2, SlimCommands.bandOf(0x89));
        assertEquals(SlimCommands.VideoFilter.MID, SlimCommands.videoOf(0x89));
        assertThrows(IllegalArgumentException.class, () -> SlimCommands.switchData(SlimCommands.VideoFilter.WIDE, 0, false, false));
    }

    @Test
    void stepCommandBitOrder() {
        // DDS1 word bit 0 set -> clock 0 carries DDS1 data (4); PLL1 bit 23 -> clock 16 carries PLL1 data (2)
        SlimCommands.Op op = SlimCommands.stepCommand(1L, 1L << 39, 1 << 23, 1, 32, 1);
        byte[] c = op.command();
        assertEquals(43, c.length, "message_size 43 in [CommandAllSlimsUSB]");
        assertEquals("A72840", MsaProtocol.toHex(java.util.Arrays.copyOf(c, 3)));
        assertEquals(4 + 32, c[3]);           // clock 0
        assertEquals(2 + 32, c[3 + 16]);      // clock 16: N23 of PLL1
        assertEquals(16 + 8 + 32, c[3 + 39]); // clock 39: DDS3 W39 and PLL3 N0
    }

    @Test
    void ddsTuningWordRoundsLikeBasic() {
        // 10.7 MHz from 64 MHz master clock
        long base = SlimCommands.ddsTuningWord(10.7, 64.0);
        assertEquals(Math.round(10.7 * 4294967296.0 / 64.0), base);
        assertEquals(10.7, SlimCommands.ddsOutput(base, 64.0), 64.0 / 4294967296.0);
        assertThrows(IllegalArgumentException.class, () -> SlimCommands.ddsTuningWord(32.0, 64.0));
    }
}
