package msa.autotest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import msa.autotest.AutotestModel.Limits;
import msa.autotest.AutotestModel.Result;
import msa.autotest.AutotestModel.Setup;
import msa.autotest.AutotestModel.Status;
import msa.config.MsaConfig;
import msa.config.PathCalibration;
import msa.hw.UsbMsa;
import msa.replay.DemoSignals;
import msa.sweep.SweepEngine;

/** The self test against the demo MSA (simulated hardware with this MSA's config.txt and path calibrations). */
class AutotestTest {

    private SweepEngine engine;
    private EngineContext ctx;
    private volatile DemoSignals.Dut dut = DemoSignals.Dut.THROUGH;

    private static Path info() throws Exception {
        return Path.of(AutotestTest.class.getResource("/msa-info/config.txt").toURI()).getParent();
    }

    private EngineContext context(ReferenceStore ref) throws Exception {
        MsaConfig cfg = MsaConfig.read(info().resolve("config.txt"));
        engine = new SweepEngine(cfg, p -> cal(p), () -> new UsbMsa(DemoSignals.create(cfg,
                () -> engine.settings() == null ? msa.sweep.MsaMode.SA : engine.settings().mode(), () -> dut, AutotestTest::cal)));
        ctx = new EngineContext(engine, cfg, p -> cal(p) != null, ref, s -> {});
        return ctx;
    }

    private static PathCalibration cal(int path) {
        try {
            return PathCalibration.load(info(), path);
        } catch (Exception e) {
            return null;
        }
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.detach();
        if (engine != null) engine.close();
    }

    private static List<AutoTest> pick(String... ids) {
        Set<String> want = Set.of(ids);
        return Tests.all().stream().filter(t -> want.contains(t.id())).toList();
    }

    @Test
    void healthyDemoPasses(@TempDir Path dir) throws Exception {
        EngineContext c = context(ReferenceStore.empty());
        List<Setup> asked = new ArrayList<>();
        List<Result> rs = AutotestRun.run(pick("zero", "adc", "noise", "speed", "tgrange", "paths", "stability", "phase"),
                c, s -> { asked.add(s); return true; }, r -> {});
        assertEquals(List.of(Setup.NONE, Setup.TG_LOOP), asked, "asked before the first test and when the wiring changes");
        for (Result r : rs) {
            assertTrue(r.status() == Status.PASS || r.status() == Status.INFO,
                    AutotestRun.report(List.of(r), List.of()));
        }
        Result zero = rs.get(0);
        assertEquals(4 * 5 + 1, zero.measurements().size(), "5 values per path, plus the spread");
        assertEquals(4, zero.traces().size());

        // keep as reference, then a changed MSA (band-pass instead of the through) is caught
        ReferenceStore ref = new ReferenceStore(dir);
        ref.update(rs, AutotestRun.curves(rs));
        ReferenceStore back = ReferenceStore.load(dir);
        assertFalse(Double.isNaN(back.value("tg.mean")));
        assertNotNull(back.trace("zero.p3"));
        ctx.detach();
        engine.close();
        dut = DemoSignals.Dut.BANDPASS;
        EngineContext c2 = context(back);
        Result tg = AutotestRun.run(pick("tgrange"), c2, s -> true, r -> {}).get(0);
        assertEquals(Status.FAIL, tg.status(), AutotestRun.report(List.of(tg), List.of()));
        assertTrue(tg.measurements().stream().anyMatch(m -> m.note().contains("since the reference")));
        assertNotNull(tg.traces().get(0).reference(), "the reference curve is shown");
    }

    @Test
    void inputSwitchTests() throws Exception {
        EngineContext c = context(ReferenceStore.empty());
        List<AutoTest> tests = pick("isoT", "rpath", "isoR", "phaseR");
        List<Result> without = AutotestRun.run(tests, c, s -> true, r -> {});
        assertTrue(without.stream().allMatch(r -> r.status() == Status.SKIP), "no switch: all skipped");
        c.setInputSwitch(true);
        List<Setup> asked = new ArrayList<>();
        List<Result> rs = AutotestRun.run(tests, c, s -> {
            asked.add(s);
            dut = s == Setup.TG_REFLECT ? DemoSignals.Dut.THROUGH_R : DemoSignals.Dut.THROUGH;   // the demo's cable
            return true;
        }, r -> {});
        assertEquals(List.of(Setup.TG_LOOP, Setup.TG_REFLECT), asked);
        for (Result r : rs)
            assertTrue(r.status() == Status.PASS || r.status() == Status.INFO, AutotestRun.report(List.of(r), List.of()));
        double iso = rs.get(0).measurements().stream().filter(m -> m.key() != null && m.key().equals("isoT.worst"))
                .findFirst().orElseThrow().value();
        assertTrue(iso > 70 && iso < 110, "isolation down to the noise floor: " + iso);
    }

    @Test
    void notConfirmedSetupSkipsItsTests() throws Exception {
        EngineContext c = context(ReferenceStore.empty());
        List<Result> rs = AutotestRun.run(pick("adc", "stability"), c, s -> s == Setup.NONE, r -> {});
        assertNotEquals(Status.SKIP, rs.get(0).status());
        assertEquals(Status.SKIP, rs.get(1).status());
    }

    @Test
    void limitsAndStatus() {
        Limits l = new Limits(-45, -5, -60, 0);
        assertEquals(Status.PASS, l.judge(-20));
        assertEquals(Status.WARN, l.judge(-50));
        assertEquals(Status.FAIL, l.judge(3));
        assertEquals(Status.FAIL, l.judge(Double.NaN));
        assertEquals(Status.INFO, Limits.info().judge(1));
        assertEquals(Status.PASS, Limits.atMost(3, 6).judge(3));
        assertEquals(Status.FAIL, Status.PASS.worst(Status.FAIL));
        assertEquals(Status.WARN, Status.SKIP.worst(Status.WARN));
        assertEquals("-45 .. -5 dBm", l.text("dBm"));
        assertEquals("<= 3 dB", Limits.atMost(3, 6).text("dB"));
    }

    @Test
    void analysis() {
        double[] x = {-2, -1, 0, 1, 2}, y = {-10, -3, 0, -3, -10};
        assertArrayEquals(new double[] {-1, 1}, Analysis.crossings(x, y, -3), 1e-12);
        assertArrayEquals(new double[] {0, 10, 20, 30}, Analysis.unwrap(new double[] {0, 10, 20, 30}), 1e-12);
        assertArrayEquals(new double[] {170, 190, 210}, Analysis.unwrap(new double[] {170, -170, -150}), 1e-12);
        double[] line = Analysis.fitLine(new double[] {0, 1, 2}, new double[] {1, 3, 5});
        assertArrayEquals(new double[] {1, 2}, line, 1e-12);
        assertEquals(2, Analysis.median(new double[] {3, 1, 2, Double.NaN}));
        assertArrayEquals(new double[] {5, Double.NaN}, Analysis.resample(new double[][] {{0, 10}, {0, 10}}, new double[] {5, 11}), 1e-12);
    }
}
