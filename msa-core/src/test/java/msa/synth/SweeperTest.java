package msa.synth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import msa.config.MsaConfig;
import msa.hw.AdcConfig;
import msa.hw.MsaProtocol;
import msa.hw.UsbMsa;
import msa.hw.sim.SimulatedTransport;
import msa.sweep.Sweeper;

class SweeperTest {

    @Test
    void powerIsRoundedToFiveDecimalsHalfAwayFromZero() throws Exception {
        java.lang.reflect.Method m = msa.sweep.Sweeper.class.getDeclaredMethod("round5", double.class);
        m.setAccessible(true);
        assertEquals(-12.34568, (double) m.invoke(null, -12.345675), 1e-12);
        assertEquals(12.34568, (double) m.invoke(null, 12.345675), 1e-12);
        assertEquals(-0.00001, (double) m.invoke(null, -0.000005), 1e-12);
    }

    @Test
    void sendsA7OnlyWhenSomethingChangesAndDecodesBack() throws Exception {
        Path cfg = Path.of(SweeperTest.class.getResource("/msa-info/config.txt").toURI());
        SynthPlanner p = new SynthPlanner(MsaConfig.read(cfg), 1);
        SimulatedTransport sim = new SimulatedTransport();
        try (UsbMsa msa = new UsbMsa(sim)) {
            Sweeper sw = new Sweeper(msa, p, new SynthPlanner.Tracking(0));
            sw.initialise(HardwareInit.Switches.defaults(), 0);
            Sweeper.Point a = sw.measure(100, 1, 0, AdcConfig.defaults(), null);
            sw.measure(100, 1, 0, AdcConfig.defaults(), null);
            long a7 = sim.received().stream().filter(c -> (c[0] & 0xFF) == MsaProtocol.CMD_STEP).count();
            assertEquals(1, a7, "same frequency twice -> one A7");
            assertEquals(a.plan().dds1Base(), sim.state().dds1Word);
            assertEquals(a.plan().pll1Bits(), sim.state().pll1Bits);
            assertEquals(a.plan().pll3Bits(), sim.state().pll3Bits);
            assertTrue(Double.isNaN(a.dBm()));
        }
    }
}
