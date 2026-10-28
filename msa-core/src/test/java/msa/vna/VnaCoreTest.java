package msa.vna;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import msa.config.MsaConfig;
import msa.hw.SlimCommands.VideoFilter;
import msa.sweep.PhaseDetector;
import msa.sweep.SweepSettings;
import msa.trace.AxisScale;
import msa.trace.GraphData;
import msa.trace.SweepData;
import msa.model.DisplaySettings.AxisRange;

/** Phase conversion, transmission calibration and S21 values; uses the MSA's config.txt and baseline cal. */
class VnaCoreTest {

    private static Path res(String name) throws Exception {
        return Path.of(VnaCoreTest.class.getResource("/msa-info/" + name).toURI());
    }

    private static MsaConfig cfg() throws Exception { return MsaConfig.read(res("config.txt")); }

    // ---------------------------------------------------------------- phase detector

    @Test
    void phaseFromAdc() throws Exception {
        PhaseDetector d = PhaseDetector.of(cfg());
        assertEquals(65535, d.maxPdmOut());
        assertEquals(197.35, d.invDeg(), 1e-12);
        assertEquals(90, d.degrees(65535 / 4.0, 0, 10.7, 0, 1), 1e-9, "quarter scale = 90 degrees lead");
        assertEquals(-90, d.degrees(3 * 65535 / 4.0, 0, 10.7, 0, 1), 1e-9, "270 = -90");
        // inverted: reading = true + 180 + (invdeg - 180) at 10.7 MHz
        double inv = (10 + 197.35) / 360 * 65535;
        assertEquals(10, d.degrees(inv, 1, 10.7, 0, 1), 0.01);
        // the deviation scales with the final IF
        assertEquals(17.35 * 10.71125 / 10.7, d.inversionDeviation(10.71125), 1e-12);
        assertEquals(5, d.degrees(65535 * 15 / 360.0, 0, 10.7, 10, 1), 0.01, "minus the level correction");
        assertEquals(-90, d.degrees(65535 / 4.0, 0, 10.7, 0, 3), 1e-9, "3G band: negated");
        assertEquals(0.01, PhaseDetector.normalise(360.01), 1e-9);
        assertEquals(180, PhaseDetector.normalise(-180), 1e-12);
    }

    @Test
    void deadZoneAndSettling() throws Exception {
        MsaConfig c = cfg();
        PhaseDetector d = PhaseDetector.of(c);
        assertTrue(d.inDeadZone(1000));
        assertTrue(d.inDeadZone(60000));
        assertFalse(d.inDeadZone(30000));
        assertFalse(d.inDeadZone(0.2 * 65535));
        // glitchpdm = int(10 kohm x phase cap x 10): 0.011, 0.2, 2.2, 10 uF
        assertEquals(1, PhaseDetector.glitchMs(VideoFilter.WIDE, c));
        assertEquals(20, PhaseDetector.glitchMs(VideoFilter.MID, c));
        assertEquals(220, PhaseDetector.glitchMs(VideoFilter.NARROW, c));
        assertEquals(1000, PhaseDetector.glitchMs(VideoFilter.XNARROW, c));
    }

    @Test
    void inversionCalibration() {
        PhaseDetector d = new PhaseDetector(65535, 180);
        double normal = 65535 * 90 / 360.0, inverted = 65535 * (90 + 196) / 360.0;
        assertEquals(196, d.inversionFrom(normal, inverted, 10.7), 0.01);
        assertEquals(180 + 16 * 10.7 / 21.4, d.inversionFrom(normal, inverted, 21.4), 0.01, "normalised to 10.7 MHz");
        assertEquals(196, d.inversionFrom(65535 * 270 / 360.0, 65535 * (270 + 196 - 360) / 360.0, 10.7), 0.01, "wraps");
    }

    // ---------------------------------------------------------------- line calibration

    @Test
    void readsTheBaselineFileOfTheBasicProgram() throws Exception {
        LineCal c = LineCal.read(res("OperatingCal/BaseLineCal.txt"));
        assertEquals(400, c.steps());
        assertEquals(650, c.startMHz());
        assertEquals(850, c.stopMHz());
        assertEquals(3, c.path());
        assertTrue(c.linear());
        assertEquals("Series", c.jigAttach());
        assertEquals(50, c.jigR0());
        assertEquals("09/27/26; 18:56:56", c.timeStamp());
        assertEquals(-10.54109, c.db()[0], 1e-12);
        assertEquals(-64.65, c.deg()[0], 1e-12);
        assertEquals("Performed 09/27/26; 18:56:56; Path 3; 400 linear steps, 650 to 850 MHz.", c.info());
    }

    @Test
    void writesWhatItReads(@TempDir Path dir) throws Exception {
        LineCal c = LineCal.read(res("OperatingCal/BaseLineCal.txt"));
        Path f = dir.resolve("BaseLineCal.txt");
        c.write(f, "BaseLine Calibration Data");
        LineCal back = LineCal.read(f);
        assertArrayEquals(c.freq(), back.freq());
        assertArrayEquals(c.db(), back.db());
        assertArrayEquals(c.deg(), back.deg());
        assertEquals(c.info(), back.info());
        // same text as the BASIC program wrote, apart from line endings
        assertEquals(Files.readString(res("OperatingCal/BaseLineCal.txt")).replace("\r\n", "\n").strip(),
                Files.readString(f).replace("\r\n", "\n").strip());
    }

    @Test
    void interpolationTakesTheShortWayRound() {
        LineCal c = new LineCal(new double[] {10, 20, 30}, new double[] {-1, -3, -5}, new double[] {170, -170, -150},
                "", true, 1, "Series", 50);
        assertArrayEquals(new double[] {-2, 180}, c.interpolate(15), 1e-9, "170 -> -170 through 180");
        assertArrayEquals(new double[] {-4, -160}, c.interpolate(25), 1e-9);
        assertArrayEquals(new double[] {-1, 170}, c.interpolate(5), 1e-9, "below: first entry");
        assertArrayEquals(new double[] {-5, -150}, c.interpolate(31), 1e-9, "above: last entry");
        assertArrayEquals(new double[] {-3, -170}, c.interpolate(20), 1e-9, "exact");
    }

    @Test
    void bandCalibrationFromASweepRemovesTheThroughDelay() {
        SweepSettings s = SweepSettings.defaults().withRange(100, 300).withSteps(2).withPath(3);
        SweepData d = new SweepData(1, s.frequencies());
        d.set(0, -10, 20);
        d.set(1, -11, 30);
        d.set(2, -12, 40);
        LineCal c = LineCal.fromSweep(d, s, true, 1, "Series", 50);     // 1 ns: 0.36 degree per MHz
        assertArrayEquals(new double[] {100, 200, 300}, c.freq());
        assertArrayEquals(new double[] {-10, -11, -12}, c.db());
        assertArrayEquals(new double[] {56, 102, 148}, c.deg(), 1e-9);
        assertTrue(c.matches(s));
        assertFalse(c.matches(s.withSteps(3)));
        assertFalse(c.matches(s.withPath(1)), "another path");
        assertFalse(c.matches(s.withRange(100, 301)));
        LineCal scalar = LineCal.fromSweep(d, s, false, 1, "Series", 50);
        assertArrayEquals(new double[] {0, 0, 0}, scalar.deg());
        SweepData partial = new SweepData(2, s.frequencies());
        partial.set(0, -10, 0);
        assertThrows(IllegalArgumentException.class, () -> LineCal.fromSweep(partial, s, true, 0, "Series", 50));
    }

    @Test
    void installPicksBandThenBase(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("OperatingCal"));
        Files.copy(res("OperatingCal/BaseLineCal.txt"), dir.resolve("OperatingCal/BaseLineCal.txt"));
        TransmissionCal tc = new TransmissionCal(dir.resolve("OperatingCal"));
        assertNull(tc.loadProblem());
        assertNotNull(tc.base());

        SweepSettings s = SweepSettings.defaults().withRange(700, 800).withSteps(4).withPath(3);
        double[] f = s.frequencies();
        assertEquals(TransmissionCal.Level.NONE, tc.install(TransmissionCal.Level.NONE, s, "Series", 50, f).level());
        TransmissionCal.Installed base = tc.install(TransmissionCal.Level.BAND, s, "Series", 50, f);
        assertEquals(TransmissionCal.Level.BASE, base.level(), "no band cal: base");
        assertEquals(tc.base().interpolate(725)[0], base.db()[1], 1e-12);
        assertEquals(TransmissionCal.Level.NONE, tc.install(TransmissionCal.Level.BASE, s.withPath(1), "Series", 50, f).level(),
                "base cal of another path");
        assertEquals(TransmissionCal.Level.NONE, tc.install(TransmissionCal.Level.BASE, s, "Shunt", 50, f).level());

        SweepData d = new SweepData(1, f);
        for (int i = 0; i < f.length; i++) d.set(i, -20 - i, 10 * i);
        tc.setBand(LineCal.fromSweep(d, s, true, 0, "Series", 50));
        TransmissionCal.Installed band = tc.install(TransmissionCal.Level.BAND, s, "Series", 50, f);
        assertEquals(TransmissionCal.Level.BAND, band.level());
        assertEquals(-22, band.db()[2], 1e-12);
        assertEquals(TransmissionCal.Level.BASE, tc.install(TransmissionCal.Level.BASE, s, "Series", 50, f).level(),
                "base wanted: base even with a band cal");
        assertEquals(TransmissionCal.Level.BASE, tc.install(TransmissionCal.Level.BAND, s.withSteps(5), "Series", 50,
                s.withSteps(5).frequencies()).level(), "band cal not current: base");

        tc.saveBandAsBase();
        assertEquals(4, LineCal.read(tc.baseFile()).steps());
        tc.clearBase();
        assertFalse(Files.exists(tc.baseFile()));
        assertNull(tc.base());
    }

    @Test
    void unreadableBaseFileIsReported(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(TransmissionCal.BASE_FILE), "! nothing\n");
        TransmissionCal tc = new TransmissionCal(dir);
        assertNull(tc.base());
        assertTrue(tc.loadProblem().contains("no calibration data"));
    }

    // ---------------------------------------------------------------- S21 values

    private static SweepData s21Sweep() {
        SweepData d = new SweepData(1, new double[] {100, 101, 102});
        d.set(0, -10, 170);
        d.set(1, -12, -175);
        d.set(2, -14, -160);
        return d;
    }

    @Test
    void s21IsReferencedToTheCalibration() {
        SweepData d = s21Sweep();
        assertEquals(-10, d.value(GraphData.DB, 0), 1e-12, "no reference: dB = dBm");
        assertEquals(170, d.value(GraphData.ANGLE, 0), 1e-12);
        d.setCorrection(new SweepData.Correction(new double[] {-1, -2, -3}, new double[] {-20, 10, 30}, 0));
        assertEquals(-9, d.value(GraphData.DB, 0), 1e-12);
        assertEquals(-10, d.value(GraphData.DBM, 0), 1e-12, "raw power stays");
        assertEquals(-170, d.value(GraphData.ANGLE, 0), 1e-12, "170 + 20 wraps");
        assertEquals(170, d.value(GraphData.RAW_ANGLE, 0), 1e-12);
        assertEquals(9, d.value(GraphData.INSERTION_LOSS, 0), 1e-12);
        assertEquals(Math.pow(10, -9 / 20.0), d.value(GraphData.RATIO, 0), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> d.setCorrection(new SweepData.Correction(new double[2], null, 0)));
    }

    @Test
    void planeExtensionAndGroupDelay() {
        SweepData d = new SweepData(1, new double[] {100, 101, 102});
        for (int i = 0; i < 3; i++) d.set(i, -10, PhaseDetector.normalise(-0.36 * 10 * (100 + i)));   // 10 ns of delay
        assertEquals(10e-9, d.value(GraphData.GROUP_DELAY, 1), 1e-15);
        assertEquals(10e-9, d.value(GraphData.GROUP_DELAY, 0), 1e-15, "first step uses the next one");
        d.setCorrection(new SweepData.Correction(null, null, 10));          // extend the plane by the 10 ns
        assertEquals(0, d.value(GraphData.ANGLE, 2), 1e-9);
        assertEquals(0, d.value(GraphData.GROUP_DELAY, 1), 1e-15);
        assertEquals(0, d.value(GraphData.RAW_ANGLE, 2), 1e-9, "raw angle includes the extension, as in BASIC");
    }

    @Test
    void anglesInterpolateAcrossTheWrap() {
        SweepData d = s21Sweep();
        assertEquals(177.5, d.value(GraphData.ANGLE, 0.5), 1e-9);
        assertEquals(-11, d.value(GraphData.DB, 0.5), 1e-12);
    }

    @Test
    void axisRulesForTransmission() {
        SweepData d = s21Sweep();
        assertEquals(new AxisRange(-15, -5), AxisScale.autoscale(d, GraphData.DB));
        assertEquals(new AxisRange(-180, 180), AxisScale.autoscale(d, GraphData.ANGLE), "spread over 120 degrees");
        SweepData narrow = new SweepData(1, new double[] {1, 2});
        narrow.set(0, -10, 20);
        narrow.set(1, -10, 40);
        assertEquals(new AxisRange(15, 45), AxisScale.autoscale(narrow, GraphData.ANGLE));
        assertEquals(new AxisRange(0, 60), AxisScale.startingLimits(GraphData.INSERTION_LOSS, new AxisRange(0, 1)));
        assertEquals(new AxisRange(-180, 180), AxisScale.startingLimits(GraphData.ANGLE, new AxisRange(0, 1)));
        assertEquals(new AxisRange(1, 2), AxisScale.startingLimits(GraphData.GROUP_DELAY, new AxisRange(1, 2)));
    }

    @Test
    void graphChoicesPerMode() {
        assertTrue(GraphData.ANGLE.validFor(msa.sweep.MsaMode.VECTOR_TRANS));
        assertFalse(GraphData.ANGLE.validFor(msa.sweep.MsaMode.SCALAR_TRANS));
        assertFalse(GraphData.DB.validFor(msa.sweep.MsaMode.SA));
        assertTrue(GraphData.DBM.validFor(msa.sweep.MsaMode.VECTOR_TRANS), "raw power");
        assertEquals(GraphData.DB, GraphData.defaultFor(msa.sweep.MsaMode.VECTOR_TRANS, true));
        assertEquals(GraphData.ANGLE, GraphData.defaultFor(msa.sweep.MsaMode.VECTOR_TRANS, false));
        assertEquals(GraphData.NONE, GraphData.defaultFor(msa.sweep.MsaMode.SCALAR_TRANS, false));
        assertEquals(GraphData.GROUP_DELAY, GraphData.ofCode(24));
    }
}
