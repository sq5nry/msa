package msa.replay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import msa.config.MsaConfig;
import msa.hw.MsaTransport;
import msa.hw.SlimCommands;
import msa.hw.sim.SimulatedTransport;
import msa.synth.SynthPlanner;

/**
 * A stand-in MSA that answers with recorded data: the {@link SimulatedTransport} decodes the commands
 * (latches, A7 step words), this class works out which frequency the analyser is tuned to and returns
 * the readings recorded there. Any sweep inside the recorded span replays real levels and real noise,
 * whatever its step size or order; outside the span it returns the recorded noise floor.
 *
 * <p>Tuned frequency: LO1 = N1 x DDS1 / R1 from the last A7 command, equivalent 1G frequency =
 * LO1 - LO2 + final IF of the selected path (latch 1 filter bits), true frequency per the band
 * (latch 4 band bits). Phase readings are moved to the PDM state the replaying analyser commands (by the
 * inversion, config.txt invdeg), so the dead-zone handling works on replays. What is not modelled: the
 * tracking-generator offset or SG frequency (the replay shows what was recorded), video filter and wait time.
 */
public final class ReplayTransport {
    private ReplayTransport() {}

    public static SimulatedTransport open(Path recordingFile, MsaConfig cfg) throws IOException {
        Recording rec = Recording.read(recordingFile);
        SimulatedTransport t = new SimulatedTransport(new Model(rec, cfg), 0x1024, 33, 0);
        t.setTrailingStatusFrames(1);                 // as the real board does
        t.setDescription("replay of " + recordingFile.getFileName() + " (" + rec.points() + " points)");
        return t;
    }

    /** Frequency the simulated analyser is tuned to, from its latch and step-command state; NaN before the first step. */
    public static double tunedFrequency(SimulatedTransport.State s, MsaConfig cfg, Map<Integer, SynthPlanner> planners) {
        int b = (s.pll1Bits >> 7) & 0x1FFF, a = (s.pll1Bits >> 2) & 31;
        int n1 = b * 32 + a;
        if (n1 == 0 || s.dds1Word == 0) return Double.NaN;
        int path = pathOf(s, cfg);
        SynthPlanner p = planners.computeIfAbsent(path, k -> new SynthPlanner(cfg, k));
        double dds1 = SlimCommands.ddsOutput(s.dds1Word & 0xFFFFFFFFL, cfg.masterClock());
        double lo1 = n1 * dds1 / p.rcounter1();
        double equiv = lo1 - p.lo2() + p.finalFreq();
        int band = (s.latch4 >> 2) & 3;
        return p.actualSignal(equiv, band == 0 ? 1 : band);
    }

    /** Path from the filter-bank bits in latch 1 (FiltA1*64 + FiltA0*32), limited to the configured paths. */
    public static int pathOf(SimulatedTransport.State s, MsaConfig cfg) {
        int path = ((s.latch1 >> 5) & 3) + 1;
        return Math.min(path, Math.max(1, cfg.filters().size()));
    }

    private static final class Model implements SimulatedTransport.AdcModel {
        private final Recording rec;
        private final MsaConfig cfg;
        private final Map<Integer, SynthPlanner> planners = new HashMap<>();
        private final Map<Long, Integer> visits = new HashMap<>();

        Model(Recording rec, MsaConfig cfg) {
            this.rec = rec;
            this.cfg = cfg;
        }

        @Override
        public long[] sample(SimulatedTransport.State s) {
            int path = pathOf(s, cfg);
            double f = tunedFrequency(s, cfg, planners);
            Recording.PdmShift pdm = pdmShift(s, path);
            if (Double.isNaN(f)) return rec.sample(path, Double.NEGATIVE_INFINITY, 0, 1, pdm);   // noise floor
            long key = Math.round(f * 1e6) * 8 + path;                                  // per frequency and path
            int k = visits.merge(key, 1, Integer::sum) - 1;                              // next recorded sweep each time
            return rec.sample(path, f, k, s.average, pdm);
        }

        /** The commanded PDM state and what an inversion does to the phase reading on this path. */
        private Recording.PdmShift pdmShift(SimulatedTransport.State s, int path) {
            msa.sweep.PhaseDetector d = msa.sweep.PhaseDetector.of(cfg);
            double deg = 180 + d.inversionDeviation(cfg.filter(path).freqMHz());
            return new Recording.PdmShift(s.pdmInverted ? 1 : 0, deg / 360 * d.maxPdmOut(), d.maxPdmOut());
        }
    }

    /** Convenience for tools: the transport as an {@link MsaTransport}. */
    public static MsaTransport transport(Path recordingFile, MsaConfig cfg) throws IOException {
        return open(recordingFile, cfg);
    }
}
