package msa.trace;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.Test;

import msa.model.DisplaySettings.AxisRange;

class GuiSupportTest {

    private static SweepData sweep(double... dBm) {
        double[] f = new double[dBm.length];
        for (int i = 0; i < f.length; i++) f[i] = 100 + 10 * i;
        SweepData d = new SweepData(1, f);
        for (int i = 0; i < dBm.length; i++) if (!Double.isNaN(dBm[i])) d.set(i, dBm[i]);
        return d;
    }

    @Test
    void lrMinus3dBAroundPeak() {
        //                 0    1    2    3   4    5    6
        SweepData d = sweep(-40, -30, -12, -10, -14, -20, -40);
        Markers m = new Markers();
        m.setLrMode(Markers.LrMode.MINUS_3DB_FROM_PEAK);
        m.place(Markers.Id.L, 0);
        m.update(d, GraphData.DBM);
        assertFalse(m.has(Markers.Id.L), "without P+ the relative L and R are removed");
        m.place(Markers.Id.PEAK_POS, 0);
        m.update(d, GraphData.DBM);
        assertEquals(3, m.index(Markers.Id.PEAK_POS).orElseThrow());
        // target -13 dBm: left between index 2 (-12) and 1 (-30): 2 + (-13+12)/(-30+12) * (1-2)
        assertEquals(2 - 1.0 / 18, m.index(Markers.Id.L).orElseThrow(), 1e-12);
        // right between index 3 (-10) and 4 (-14): 4 + (-13+14)/(-10+14) * (3-4) = 3.75
        assertEquals(3.75, m.index(Markers.Id.R).orElseThrow(), 1e-12);
    }

    @Test
    void lrPlus3dBAroundDip() {
        SweepData d = sweep(-10, -20, -30, -20, -10);
        double[] lr = Markers.findDbOffset(d, GraphData.DBM, 2, 3);
        assertEquals(2 - 0.3, lr[0], 1e-12);
        assertEquals(2 + 0.3, lr[1], 1e-12);
        assertTrue(Double.isNaN(Markers.findDbOffset(d, GraphData.DBM, 0, 30)[0]), "never reached -> NaN");
    }

    @Test
    void autoscaleRules() {
        assertEquals(new AxisRange(-75, -10), AxisScale.autoscale(sweep(-72.3, -10.6, -40), GraphData.DBM));
        assertEquals(new AxisRange(-80, -5), AxisScale.autoscale(sweep(-74.6, -10), GraphData.DBM), "0.5 dB headroom");
        AxisRange w = AxisScale.autoscale(sweep(-30, -10), GraphData.WATTS);   // 1 uW .. 100 uW
        assertEquals(0, w.min());
        assertEquals(1e-4, w.max(), 1e-18);
        assertNull(AxisScale.autoscale(sweep(Double.NaN, Double.NaN), GraphData.DBM));
        assertEquals(new AxisRange(-100, 0), AxisScale.startingLimits(GraphData.DBM, new AxisRange(0, 1)));
        assertEquals(1e-3, AxisScale.roundUpToPower(0.00042, 10), 1e-15);
        assertEquals(1e-4, AxisScale.roundDownToPower(0.00042, 10), 1e-15);
        assertEquals(-1e-3, AxisScale.roundDownToPower(-0.00042, 10), 1e-15);
        assertEquals(100, AxisScale.roundUpToPower(100, 10), 1e-12);
    }

    @Test
    void dataFile() throws Exception {
        SweepData d = sweep(-10.5, Double.NaN, -30);
        StringWriter w = new StringWriter();
        DataFile.write(w, d, List.of("MSA spectrum analyser"));
        String[] lines = w.toString().split("\\R");
        assertEquals("! MSA spectrum analyser", lines[0]);
        assertEquals("0, 100.000000, -10.50000, 0", lines[2]);
        assertEquals("2, 120.000000, -30.00000, 0", lines[3]);
        assertEquals(4, lines.length, "unmeasured point skipped");
    }

    @Test
    void eraseTraceShowsOldDataAheadOfTheSweep() {
        SweepData old = sweep(-1, -2, -3);
        SweepData now = new SweepData(2, old.frequencies());
        now.set(0, -10.0);
        SweepData m = now.mergedOver(old);
        assertEquals(-10, m.dBm(0));
        assertEquals(-2, m.dBm(1));
        assertEquals(3, m.measuredCount());
        assertEquals(1, now.measuredCount(), "original untouched");
        assertEquals(1, now.mergedOver(sweep(-1, -2)).measuredCount(), "different sweep length: no merge");
    }
}
