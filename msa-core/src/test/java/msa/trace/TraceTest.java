package msa.trace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import msa.config.MsaConfig;
import msa.hw.UsbMsa;
import msa.hw.sim.SimulatedTransport;
import msa.sweep.SweepEngine;
import msa.sweep.SweepSettings;

class TraceTest {

    private static SweepData sweep(double... dBm) {
        double[] f = new double[dBm.length];
        for (int i = 0; i < f.length; i++) f[i] = 100 + 10 * i;
        SweepData d = new SweepData(1, f);
        for (int i = 0; i < dBm.length; i++) if (!Double.isNaN(dBm[i])) d.set(i, dBm[i]);
        return d;
    }

    @Test
    void graphDataConversionsLikeCalcGraphData() {
        assertEquals(-30, GraphData.DBM.fromDbm(-30));
        assertEquals(0.001, GraphData.WATTS.fromDbm(0), 1e-15);
        assertEquals(1e-6, GraphData.WATTS.fromDbm(-30), 1e-18);
        assertEquals(Math.sqrt(0.05), GraphData.VOLTS.fromDbm(0), 1e-12);   // 1 mW into 50 ohm = 0.2236 V
        assertEquals(0, GraphData.NONE.fromDbm(-30));
        assertEquals(GraphData.DBM, GraphData.ofCode(17));
        assertEquals(GraphData.NONE, GraphData.ofCode(1));                  // VNA type, not ported
        assertTrue(Double.isNaN(GraphData.WATTS.fromDbm(Double.NaN)));
    }

    @Test
    void fractionalStepsInterpolateInGraphUnits() {
        SweepData d = sweep(-20, -10, 0);
        assertEquals(-15, d.value(GraphData.DBM, 0.5), 1e-12);
        // watts are interpolated after conversion, not converted after interpolating dB
        double w = (GraphData.WATTS.fromDbm(-20) + GraphData.WATTS.fromDbm(-10)) / 2;
        assertEquals(w, d.value(GraphData.WATTS, 0.5), 1e-15);
        assertEquals(105, d.frequency(0.5), 1e-12);
        assertEquals(1.5, d.indexOf(115), 1e-12);
        assertEquals(0, d.indexOf(50));
        assertEquals(2, d.indexOf(500));
    }

    @Test
    void peakSearchUsesMiddleOfAPlateau() {
        SweepData d = sweep(-50, -20, -10, -10, -10, -30, -60, -60, -40);
        Markers.Peaks p = Markers.findPeaks(d, GraphData.DBM, 0, d.size() - 1);
        assertEquals(3, p.maxIndex());          // plateau 2..4 -> int((2+4)/2)
        assertEquals(-10, p.max());
        assertEquals(6, p.minIndex());          // plateau 6..7 -> int((6+7)/2) = 6
        assertEquals(-60, p.min());
        // a plateau at the very first point is not extended (maxPeakEnded starts at 1)
        assertEquals(0, Markers.findPeaks(sweep(-5, -5, -9), GraphData.DBM, 0, 2).maxIndex());
    }

    @Test
    void peakMarkersRespectLAndR() {
        SweepData d = sweep(0, -30, -20, -25, -40, -35, -5);
        Markers m = new Markers();
        m.setLrMode(Markers.LrMode.PEAKS_BOUNDED);
        m.place(Markers.Id.PEAK_POS, 0);
        m.place(Markers.Id.PEAK_NEG, 0);
        m.update(d, GraphData.DBM);
        assertEquals(0, m.index(Markers.Id.PEAK_POS).orElseThrow());
        m.place(Markers.Id.L, 1);
        m.place(Markers.Id.R, 5);
        m.update(d, GraphData.DBM);
        assertEquals(2, m.index(Markers.Id.PEAK_POS).orElseThrow());
        assertEquals(4, m.index(Markers.Id.PEAK_NEG).orElseThrow());
        m.setBoundedByLR(false);
        m.update(d, GraphData.DBM);
        assertEquals(0, m.index(Markers.Id.PEAK_POS).orElseThrow());

        m.placeAtFrequency(Markers.Id.M1, 125, d);
        Markers.Readout r = m.readouts(d, GraphData.DBM).stream().filter(x -> x.id() == Markers.Id.M1).findFirst().orElseThrow();
        assertEquals(2.5, r.index(), 1e-12);
        assertEquals(125, r.frequencyMHz(), 1e-12);
        assertEquals(-22.5, r.value(), 1e-12);
    }

    @Test
    void storedReferenceAndTraceMath() {
        SweepData d = sweep(-10, -20, -30);
        Reference.Stored ref = Reference.Stored.of(d, GraphData.DBM);
        assertEquals(-15, ref.at(105), 1e-12);
        assertEquals(-10, ref.at(0));               // held beyond the ends
        assertEquals(-30, ref.at(999));
        assertEquals(3, Reference.TraceMath.SUBTRACT_REFERENCE.apply(ref.at(110), -17), 1e-12);
        assertEquals(-17, Reference.TraceMath.NONE.apply(ref.at(110), -17), 1e-12);
        assertEquals(-50, new Reference.Fixed(-50).at(123));
        SweepData partial = sweep(-10, Double.NaN, -30);
        assertEquals(2, Reference.Stored.of(partial, GraphData.DBM).freq().length, "unmeasured points skipped");
    }

    @Test
    void storeFollowsTheEngineAndKeepsHistory() throws Exception {
        Path cfg = Path.of(TraceTest.class.getResource("/msa-info/config.txt").toURI());
        TraceStore store = new TraceStore(3);
        List<Integer> changes = new java.util.concurrent.CopyOnWriteArrayList<>();
        store.addChangeListener((cur, i) -> changes.add(i));
        try (SweepEngine e = new SweepEngine(MsaConfig.read(cfg), p -> null, () -> new UsbMsa(new SimulatedTransport()))) {
            e.addListener(store, Runnable::run);   // a GUI would pass its UI-thread executor
            SweepSettings s = SweepSettings.defaults().withRange(100, 200).withSteps(10).withWait(0);
            e.start(s);
            long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (store.history().size() < 3 && System.nanoTime() < end) Thread.sleep(10);
            e.halt();
            assertTrue(e.awaitState(SweepEngine.State.HALTED, Duration.ofSeconds(5)));
            List<SweepData> h = store.history();
            assertEquals(3, h.size());
            assertTrue(h.get(0).number() > h.get(1).number(), "newest first");
            assertTrue(h.get(0).complete());
            assertEquals(11, h.get(0).measuredCount());
            assertEquals(100, h.get(0).frequency(0), 1e-9);
            assertTrue(h.get(0).adc(5) > 0);
            assertTrue(changes.size() > 33);

            e.update(x -> x.withRange(300, 400));    // new axis: history must not mix frequency grids
            e.resume();
            end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while ((store.current() == null || store.current().frequency(0) != 300) && System.nanoTime() < end) Thread.sleep(10);
            assertEquals(300, store.current().frequency(0), 1e-9);
            assertTrue(store.history().isEmpty());
        }
    }

    @Test
    void displayModes() {
        assertTrue(TraceStore.DisplayMode.ofCode(2).stick());
        assertTrue(TraceStore.DisplayMode.ofCode(3).histogram());
        assertFalse(TraceStore.DisplayMode.ofCode(1).stick());
        assertEquals(TraceStore.DisplayMode.NORMAL_ERASE, TraceStore.DisplayMode.ofCode(99));
    }
}
