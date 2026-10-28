package msa.synth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import msa.config.MsaConfig;
import msa.hw.SlimCommands;

/**
 * Uses the MSA's real config.txt. Expected values are worked out by hand from the BASIC formulas,
 * e.g. rcounter1 = round(10.695 / 0.974) = round(10.98) = 11.
 */
class SynthPlannerTest {

    private static SynthPlanner planner() throws Exception {
        Path cfg = Path.of(SynthPlannerTest.class.getResource("/msa-info/config.txt").toURI());
        return new SynthPlanner(MsaConfig.read(cfg), 1);
    }

    @Test
    void lmx2326Words() {
        assertEquals(0x83, Lmx2326.initWord(1, Lmx2326.FoLD.TRISTATE));
        assertEquals(0x03 | 0x10 | 0x20, Lmx2326.initWord(0, Lmx2326.FoLD.HIGH));
        assertEquals(11 << 2, Lmx2326.rWord(11));
        Lmx2326.NWord n = Lmx2326.nWord(1145);
        assertEquals(35, n.bcounter());
        assertEquals(25, n.acounter());
        assertEquals(1 | 25 << 2 | 35 << 7 | 1 << 20, n.bits());
        assertThrows(SynthException.class, () -> Lmx2326.nWord(3 * 32 + 31)); // B < A
        assertThrows(SynthException.class, () -> Lmx2326.rWord(2));
    }

    @Test
    void fixedPartsFromConfig() throws Exception {
        SynthPlanner p = planner();
        assertEquals(8, p.rcounter2());                 // round(63.9996 / 8)
        assertEquals(128, p.ncounter2());               // round(1024 / 7.99995)
        assertEquals(128 * 63.9996 / 8, p.lo2(), 1e-9); // 1023.9936
        assertEquals(11, p.rcounter1());
        assertEquals(11, p.rcounter3());                // round(10.6955 / 0.974)
        assertEquals(10.7, p.finalFreq());
    }

    @Test
    void stepAt100MHz() throws Exception {
        SynthPlanner p = planner();
        SynthPlanner.StepPlan s = p.plan(100, 1, new SynthPlanner.Tracking(0));
        // LO1 approx = 100 + 1023.9936 - 10.7 = 1113.2936; N = round(1113.2936 / (10.695/11)) = 1145
        assertEquals(1145, s.ncounter1());
        assertEquals(1156, s.ncounter3());
        assertEquals(1113.2936, s.lo1(), 1e-5);
        assertEquals(100, s.generatorOut(), 1e-5);
        assertEquals(10.7, s.realFinalIf(), 1e-5);
        assertEquals(10.695, s.dds1Output(), 0.04);   // inside the DDS1 crystal filter
        // exact LO1 is N * dds1 / R
        assertEquals(1145 * s.dds1Output() / 11, s.lo1(), 1e-9);
        byte[] a7 = s.command(0, 0).command();
        assertEquals(43, a7.length);
        assertEquals((byte) 0xA7, a7[0]);
    }

    @Test
    void bandsConvertBothWays() throws Exception {
        SynthPlanner p = planner();
        for (int band = 1; band <= 3; band++) {
            double f = 500 + 1000 * (band - 1);
            assertEquals(f, p.actualSignal(p.equiv1G(f, band), band), 1e-9);
        }
        assertEquals(1500 - p.lo2(), p.equiv1G(1500, 2), 1e-9);
        assertEquals(2, SynthPlanner.autoBand(1500));
        assertEquals(3, SynthPlanner.autoBand(2500));
        SynthPlanner.StepPlan s = p.plan(2500, 0, new SynthPlanner.Tracking(0));
        assertEquals(3, s.band());
        assertEquals(10.7, s.realFinalIf(), 1e-5);
    }

    @Test
    void sweepRangeIsPlannableAcrossBand1() throws Exception {
        SynthPlanner p = planner();
        for (double f = 0; f <= 1000; f += 0.25) {
            SynthPlanner.StepPlan s = p.plan(f, 1, new SynthPlanner.Tracking(0));
            assertEquals(10.7, s.realFinalIf(), 2e-5, "at " + f);
            assertEquals(f, s.generatorOut(), 2e-5, "TG at " + f);
        }
    }

    @Test
    void initSequenceOrder() throws Exception {
        SynthPlanner p = planner();
        List<SlimCommands.Op> ops = HardwareInit.sequence(p, HardwareInit.Switches.defaults());
        assertEquals("A102000080", ops.get(0).hex());                         // filter path 1, latch pulse
        assertEquals("A2010084", ops.get(4).hex());                           // latch 4: band 1, Wide
        // PLL2 N = 128: B = 4, A = 0 -> word 1 | 4 << 7 | 1 << 20, clocked on the PLL2 data line (16)
        String pll2n = SlimCommands.commandPll(Lmx2326.nWord(128).bits(), SlimCommands.L1_PLL2_DATA, 0, SlimCommands.L2_LE2, 0).get(0).hex();
        assertTrue(ops.stream().anyMatch(o -> o.hex().equals(pll2n)));
        assertEquals("A302000200", ops.get(ops.size() - 1).hex());            // ends with the DDS1 reset FQUD
        assertEquals(35, ops.size());
    }

    @Test
    void reverseTrackingFollowsTheMirroredFrequency() throws Exception {
        SynthPlanner p = planner();
        SynthPlanner.Tracking rev = new SynthPlanner.Tracking(0, true);
        SynthPlanner.StepPlan normalAt30 = p.plan(30, 1, new SynthPlanner.Tracking(0));
        SynthPlanner.StepPlan revAt10 = p.plan(10, 1, rev, 30);   // step at 10 MHz, TG at the mirrored 30 MHz
        assertEquals(normalAt30.lo3(), revAt10.lo3(), 1e-9, "LO3 is set for the mirrored frequency");
        assertEquals(p.plan(10, 1, new SynthPlanner.Tracking(0)).lo1(), revAt10.lo1(), 1e-12, "LO1 still tunes 10 MHz");
        assertEquals(new SynthPlanner.Tracking(5), new SynthPlanner.Tracking(5, false));
    }
}
