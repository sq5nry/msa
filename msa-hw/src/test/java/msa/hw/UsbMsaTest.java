package msa.hw;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import msa.hw.sim.SimulatedTransport;

class UsbMsaTest {

    private static SimulatedTransport sim(int pending) {
        return new SimulatedTransport(s -> new long[] {12345, s.pdmInverted ? 50000 : 16000}, 0x1024, 33, pending);
    }

    @Test
    void versionsLikeUsbMSAGetVersions() {
        try (UsbMsa msa = new UsbMsa(sim(0))) {
            assertEquals(0x24 + (2 << 8), msa.versions());
        }
    }

    @Test
    void readsAdcAfterPendingReplies() {
        try (UsbMsa msa = new UsbMsa(sim(3))) {
            AdcResult r = msa.readAdcs(AdcConfig.defaults());
            assertEquals(12345, r.adc1());
            assertEquals(16000, r.adc2());
            assertEquals(33, r.readsDone());
        }
    }

    @Test
    void trailingStatusFrameDoesNotShiftMeasurements() {
        SimulatedTransport t = new SimulatedTransport(new SimulatedTransport.AdcModel() {
            int k;
            public long[] sample(SimulatedTransport.State s) { k++; return new long[] {1000 + k, 0}; }
        }, 0x1024, 33, 0);
        t.setTrailingStatusFrames(1);
        try (UsbMsa msa = new UsbMsa(t)) {
            assertEquals(1001, msa.readAdcs(AdcConfig.defaults()).adc1());
            assertEquals(1002, msa.readAdcs(AdcConfig.defaults()).adc1(), "leftover status frame must be skipped, not returned");
            assertEquals(1003, msa.readAdcs(AdcConfig.defaults()).adc1());
            assertEquals(2, msa.skippedFrames());
        }
    }

    @Test
    void givesUpAfterFiveEmptyReplies() {
        try (UsbMsa msa = new UsbMsa(sim(5))) {
            assertThrows(MsaException.class, () -> msa.readAdcs(AdcConfig.defaults()));
        }
    }

    @Test
    void simulatorFollowsPdmAndStepCommands() {
        SimulatedTransport t = sim(0);
        try (UsbMsa msa = new UsbMsa(t)) {
            SlimCommands.run(msa, SlimCommands.commandPdm(1));
            assertEquals(50000, msa.readAdcs(AdcConfig.defaults()).adc2());
            long w = SlimCommands.ddsTuningWord(10.7, 64.0);
            msa.write(SlimCommands.stepCommand(w, 0, 0x123456, 0x654321, 64, 0).command());
            assertEquals(w, t.state().dds1Word);
            assertEquals(0x123456, t.state().pll1Bits);
            assertEquals(0x654321, t.state().pll3Bits);
            assertEquals(10.7, t.state().dds1Frequency(64.0), 1e-6);
            assertFalse(t.state().pdmInverted);
        }
    }

    @Test
    void logFormatMatchesMsadll() {
        StringWriter w = new StringWriter();
        try (UsbMsa msa = new UsbMsa(new LoggingTransport(sim(0), w))) {
            msa.write("A1010021");
            msa.readAdcs(AdcConfig.defaults());
        }
        String[] lines = w.toString().lines().filter(l -> !l.startsWith("#")).toArray(String[]::new);
        assertEquals("Write Length 4: Message = a1 01 00 21 ", lines[0]);
        assertEquals("Write Length 5: Message = b2 01 04 10 01 ", lines[1]);
        assertTrue(lines[2].startsWith("Read command, count = 16 Data =b2 20 "), lines[2]);
    }
}
