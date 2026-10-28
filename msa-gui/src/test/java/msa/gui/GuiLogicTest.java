package msa.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import msa.config.MsaConfig;
import msa.hw.SlimCommands.VideoFilter;
import msa.model.DisplaySettings;
import msa.sweep.FrequencyRange;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;
import msa.trace.GraphData;
import msa.trace.Markers;
import msa.trace.Reference;
import msa.trace.SweepData;

/** The parts of the window that are rules rather than layout; no JavaFX needed. */
class GuiLogicTest {

    private static MsaConfig cfg() throws Exception {
        return MsaConfig.read(Path.of(GuiLogicTest.class.getResource("/config.txt").toURI()));
    }

    private static SweepForm.Input in(boolean cs, String a, String b, boolean linear) {
        return new SweepForm.Input(cs, a, b, a, b, "400", "5", "1", 2, VideoFilter.MID, 0, 10, linear,
                SweepSettings.Direction.LEFT_TO_RIGHT, "10", false, true);
    }

    @Test
    void sweepFormCentreSpanAndClamping() throws Exception {
        SweepSettings old = SweepSettings.defaults();
        SweepForm.Result r = SweepForm.apply(in(true, "100", "20", true), old, DisplaySettings.defaults(), cfg(), false);
        assertEquals(new FrequencyRange(90, 110), r.sweep().range());
        assertEquals(2, r.sweep().path());
        assertEquals(VideoFilter.MID, r.sweep().video());
        assertEquals(new SynthPlanner.SignalGen(10), r.sweep().generator());

        SweepForm.Input odd = new SweepForm.Input(false, "", "", "50", "10", "0", "-3", "999", 1, VideoFilter.WIDE, 1, 7,
                true, SweepSettings.Direction.ALTERNATE, "2.5", true, false);
        r = SweepForm.apply(odd, old, DisplaySettings.defaults(), cfg(), true);
        assertEquals(new FrequencyRange(10, 50), r.sweep().range(), "start/stop put in order");
        assertEquals(1, r.sweep().steps());
        assertEquals(0, r.sweep().waitMs());
        assertEquals(255, r.sweep().samples());
        assertEquals(6, r.display().gridDivX(), "divisions made even");
        assertEquals(new SynthPlanner.Tracking(2.5, true), r.sweep().generator());

        assertThrows(IllegalArgumentException.class,
                () -> SweepForm.apply(in(false, "abc", "10", true), old, DisplaySettings.defaults(), cfg(), false));
    }

    @Test
    void sweepFormLogRules() throws Exception {
        SweepSettings old = SweepSettings.defaults();
        SweepForm.Result r = SweepForm.apply(in(false, "0", "100", false), old, DisplaySettings.defaults(), cfg(), false);
        assertEquals(SweepSettings.Scale.LOG, r.sweep().scale());
        assertEquals(0.0001, r.sweep().startMHz());
        assertTrue(r.notices().get(0).startsWith("0 Hz not allowed"));
        r = SweepForm.apply(in(false, "100", "200", false), old, DisplaySettings.defaults(), cfg(), false);
        assertEquals(SweepSettings.Scale.LOG, r.sweep().scale(), "any ratio above 1 can be swept logarithmically");
        r = SweepForm.apply(in(false, "1", "3", false), old, DisplaySettings.defaults(), cfg(), false);
        assertEquals(SweepSettings.Scale.LOG, r.sweep().scale());
        assertTrue(r.notices().isEmpty());
        r = SweepForm.apply(in(false, "10", "10", false), old, DisplaySettings.defaults(), cfg(), false);
        assertEquals(SweepSettings.Scale.LINEAR, r.sweep().scale(), "zero span");
        r = SweepForm.apply(in(false, "-10", "100", false), old, DisplaySettings.defaults(), cfg(), false);
        assertEquals(0.001, r.sweep().startMHz(), 1e-12);
        assertTrue(r.notices().contains("Log sweep cannot cross zero Hz. Range changed."));
    }

    private static SweepData sweep(double... dBm) {
        double[] f = new double[dBm.length];
        for (int i = 0; i < f.length; i++) f[i] = 100 + 10 * i;
        SweepData d = new SweepData(1, f);
        for (int i = 0; i < dBm.length; i++) d.set(i, dBm[i]);
        return d;
    }

    @Test
    void markerButtons() {
        GraphState st = new GraphState(1);
        SweepData d = sweep(-50, -40, -30, -20, -10);
        assertEquals(Markers.Id.L, st.placeAtClick(1.4, 5), "no selection: L, at the nearest step");
        assertEquals(1.0, st.markers.index(Markers.Id.L).orElseThrow());
        st.select(Markers.Id.M2);
        assertEquals(Optional.of(130.0), st.enterFrequency(128, d));
        st.stepSelected(1, 5);
        assertEquals(4.0, st.markers.index(Markers.Id.M2).orElseThrow());
        st.stepSelected(1, 5);
        assertEquals(4.0, st.markers.index(Markers.Id.M2).orElseThrow(), "clamped at the last step");
        st.setQueryIndex(2);
        assertTrue(st.placeFromKey("r", 5));
        assertFalse(st.placeFromKey("x", 5));
        assertEquals(new FrequencyRange(110, 120), st.expandLR(d).orElseThrow());
        assertEquals(new FrequencyRange(130, 150), st.markToCenter(d, new FrequencyRange(100, 120)).orElseThrow());
        st.deleteSelected();
        assertFalse(st.markers.has(Markers.Id.M2));
        st.select(Markers.Id.PEAK_POS);
        assertTrue(st.enterFrequency(120, d).isEmpty(), "P+ is placed automatically");
    }

    @Test
    void referenceMath() {
        GraphState st = new GraphState(1);
        DisplaySettings ds = DisplaySettings.defaults();   // Y2 = dBm
        SweepData d = sweep(-50, -40);
        st.setReference(new GraphState.ReferenceSpec(GraphState.ReferenceSpec.Kind.FIXED, false, true, 0, -45,
                Reference.TraceMath.SUBTRACT_REFERENCE), false, ds);
        assertEquals(-5, st.value(d, GraphData.DBM, 2, 0), 1e-12);
        assertTrue(Double.isNaN(st.referenceLine(2, 100)), "with math only the result is drawn");
        assertEquals("Data-Ref", st.referenceSpec().mathLabel());
        st.setReference(new GraphState.ReferenceSpec(GraphState.ReferenceSpec.Kind.FIXED, false, true, 0, -45,
                Reference.TraceMath.NONE), false, ds);
        assertEquals(-50, st.value(d, GraphData.DBM, 2, 0), 1e-12);
        assertEquals(-45, st.referenceLine(2, 100));
        assertTrue(Double.isNaN(st.referenceLine(1, 100)), "not on Y1");
    }

    @Test
    void markersKeepTheirFrequencyWhenTheAxisChanges() {
        GraphState st = new GraphState(1);
        SweepData a = new SweepData(1, new double[] {100, 110, 120, 130, 140});
        assertFalse(st.followAxis(a), "first sweep: nothing to move");
        st.markers.place(Markers.Id.L, 1);       // 110 MHz
        st.markers.place(Markers.Id.M1, 4);      // 140 MHz
        st.markers.place(Markers.Id.PEAK_POS, 0);
        // same span, twice the steps (e.g. another filter with automatic steps)
        double[] f = new double[9];
        for (int i = 0; i < 9; i++) f[i] = 100 + 5 * i;
        assertTrue(st.followAxis(new SweepData(2, f)));
        assertEquals(2.0, st.markers.index(Markers.Id.L).orElseThrow(), "still 110 MHz");
        assertEquals(8.0, st.markers.index(Markers.Id.M1).orElseThrow(), "still 140 MHz");
        assertTrue(st.markers.has(Markers.Id.PEAK_POS));
        // narrower span: 140 MHz is outside and its marker goes
        assertTrue(st.followAxis(new SweepData(3, new double[] {100, 105, 110, 115, 120})));
        assertEquals(2.0, st.markers.index(Markers.Id.L).orElseThrow());
        assertFalse(st.markers.has(Markers.Id.M1));
        assertFalse(st.followAxis(new SweepData(4, new double[] {100, 105, 110, 115, 120})), "same axis: untouched");
    }
}
