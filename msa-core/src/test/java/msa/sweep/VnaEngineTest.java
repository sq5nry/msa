package msa.sweep;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import msa.config.MsaConfig;
import msa.hw.MsaProtocol;
import msa.hw.UsbMsa;
import msa.hw.sim.SimulatedTransport;

/** The sweep engine in vector transmission: phase reading, PDM dead zone and inversion. */
class VnaEngineTest {

    private SweepEngine engine;
    private SimulatedTransport sim;
    private final List<Sweeper.Point> points = Collections.synchronizedList(new ArrayList<>());

    /** A detector seeing a fixed phase lead; the inverted PDM adds 180 + 17.35 degrees (invdeg 197.35, 10.7 MHz). */
    private SweepEngine engine(double phaseDeg) throws Exception {
        MsaConfig cfg = MsaConfig.read(Path.of(VnaEngineTest.class.getResource("/msa-info/config.txt").toURI()));
        sim = new SimulatedTransport(st -> {
            double deg = phaseDeg + (st.pdmInverted ? 197.35 : 0);
            long adc2 = Math.round(Math.floorMod(Math.round(deg * 100), 36000) / 36000.0 * 65535);
            return new long[] {30000, adc2};
        }, 0x1024, 33, 0);
        engine = new SweepEngine(cfg, path -> null, () -> new UsbMsa(sim, 200, 200, 0));
        engine.addListener(new SweepEngine.Listener() {
            @Override public void point(int n, int index, Sweeper.Point p) { points.add(p); }
        }, Runnable::run);
        return engine;
    }

    private void sweepOnce(SweepSettings s) throws Exception {
        engine.start(s);
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (points.size() < s.points()) {
            if (System.nanoTime() > end) fail("timed out");
            Thread.sleep(5);
        }
        engine.halt();
        assertTrue(engine.awaitState(SweepEngine.State.HALTED, Duration.ofSeconds(10)));
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
    }

    private static SweepSettings vna() {
        return SweepSettings.defaults().withRange(100, 103).withSteps(3).withWait(0).withMode(MsaMode.VECTOR_TRANS);
    }

    @Test
    void phaseInTheDeadZoneInvertsThePdm() throws Exception {
        engine(10);                                     // 10 degrees: 2.8 % of full scale, in the dead zone
        sweepOnce(vna());
        Sweeper.Point first = points.get(0);
        assertEquals(1, first.pdm(), "read again with the PDM inverted");
        assertEquals(10, first.phaseDeg(), 0.02);
        for (int i = 1; i < 4; i++) assertEquals(1, points.get(i).pdm(), "the rest of the sweep stays inverted");
        assertTrue(sim.state().pdmInverted);
        long pdmLatches = sim.received().stream().filter(c -> (c[0] & 0xFF) == MsaProtocol.CMD_LATCH2).count();
        assertTrue(pdmLatches >= 2, "init (normal) and the inversion");
    }

    @Test
    void phaseOutsideTheDeadZoneKeepsThePdmNormal() throws Exception {
        engine(-90);
        sweepOnce(vna());
        for (Sweeper.Point p : points) {
            assertEquals(0, p.pdm());
            assertEquals(-90, p.phaseDeg(), 0.02);
        }
        assertFalse(sim.state().pdmInverted);
    }

    @Test
    void spectrumAnalyserReadsNoPhase() throws Exception {
        engine(10);
        sweepOnce(vna().withMode(MsaMode.SA));
        for (Sweeper.Point p : points) {
            assertEquals(0, p.pdm());
            assertTrue(Double.isNaN(p.phaseDeg()));
        }
        assertFalse(sim.state().pdmInverted);
    }

    @Test
    void pdmCalibrationMeasuresTheInversion() throws Exception {
        engine(-90);
        sweepOnce(vna());
        long t0 = System.nanoTime();
        SweepEngine.PdmCalibration c = engine.calibratePdm().get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - t0 >= 2 * PhaseDetector.CAL_SETTLE_MS * 1_000_000L, "settles 5 s per reading");
        assertEquals(197.35, c.invDeg(), 0.02);
        assertEquals(270, c.phaseNormalDeg(), 0.02);
        assertFalse(sim.state().pdmInverted, "PDM put back");
    }
}
