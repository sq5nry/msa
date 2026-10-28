package msa.synth;

import static msa.hw.SlimCommands.*;

import java.util.ArrayList;
import java.util.List;

import msa.config.MsaConfig;

/**
 * [InitializeHardware] for the SLIM control board over USB (cb = 3), as one list of commands:
 * a. path filter, b. PDM, c. ADC mux, d. latch 4 switches,
 * 6. DDS3 serial reset, 7. PLL3 init + R, 8. PLL2 init + R + N, 9. PLL1 init + R, 10. DDS1 serial reset.
 * No frequency is commanded for PLL1/PLL3/DDS1/DDS3; the first sweep step does that.
 */
public final class HardwareInit {
    private HardwareInit() {}

    /** Latch 4 state for the init sequence. */
    public record Switches(VideoFilter video, int band, boolean reverse, boolean reflection) {
        public static Switches defaults() { return new Switches(VideoFilter.WIDE, 1, false, false); }
    }

    public static List<Op> sequence(SynthPlanner p, Switches sw) {
        MsaConfig cfg = p.config();
        int fb = filterBank(p.path());
        int pdm = 0;
        List<Op> ops = new ArrayList<>();
        ops.addAll(commandFilter(fb));
        ops.addAll(commandPdm(pdm));
        ops.addAll(setAdcMux(0));
        ops.addAll(commandSwitches(switchData(sw.video(), sw.band(), sw.reverse(), sw.reflection())));

        if (cfg.tgTop() > 0) {
            ops.addAll(resetSerialDds(3, fb, pdm));
            ops.addAll(commandPll(Lmx2326.initWord(cfg.pll3().phasePolarity(), Lmx2326.FoLD.TRISTATE), L1_PLL3_DATA, fb, L2_LE3, pdm));
            ops.addAll(commandPll(Lmx2326.rWord(p.rcounter3()), L1_PLL3_DATA, fb, L2_LE3, pdm));
        }

        ops.addAll(commandPll(Lmx2326.initWord(cfg.pll2().phasePolarity(), Lmx2326.FoLD.TRISTATE), L1_PLL2_DATA, fb, L2_LE2, pdm));
        ops.addAll(commandPll(Lmx2326.rWord(p.rcounter2()), L1_PLL2_DATA, fb, L2_LE2, pdm));
        ops.addAll(commandPll(p.pll2N().bits(), L1_PLL2_DATA, fb, L2_LE2, pdm));

        ops.addAll(commandPll(Lmx2326.initWord(cfg.pll1().phasePolarity(), Lmx2326.FoLD.TRISTATE), L1_PLL1_DATA, fb, L2_LE1, pdm));
        ops.addAll(commandPll(Lmx2326.rWord(p.rcounter1()), L1_PLL1_DATA, fb, L2_LE1, pdm));

        ops.addAll(resetSerialDds(1, fb, pdm));
        return ops;
    }
}
