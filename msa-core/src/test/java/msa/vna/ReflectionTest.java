package msa.vna;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import msa.sweep.SweepSettings;
import msa.trace.GraphData;
import msa.trace.SweepData;

class ReflectionTest {

    /** A bridge with directivity, tracking and source-match errors: raw reading of a load with reflection s. */
    static double[] bridge(Cx s, double fMHz) {
        Cx e00 = Cx.polar(0.02, 40 + fMHz / 10);                 // -34 dB directivity
        Cx e01e10 = Cx.polar(0.16, -1.2 * fMHz);                   // -16 dB, with cable delay
        Cx e11 = Cx.polar(0.08, 120 - fMHz / 5);
        Cx m = e00.plus(e01e10.times(s).div(Cx.ONE.minus(e11.times(s))));
        return new double[] {-10 + m.db(), m.deg()};
    }

    static OslCal.Reading read(double[] f, RlcSpec std) {
        double[] db = new double[f.length], deg = new double[f.length];
        for (int i = 0; i < f.length; i++) {
            double[] r = bridge(std.s11(f[i], 50), f[i]);
            db[i] = r[0];
            deg[i] = r[1];
        }
        return new OslCal.Reading(db, deg);
    }

    static final CalStandards KIT = new CalStandards("Kit", "", "RLC[S, C0, D0]", "RLC[P, R0, L0, D0]", "RLC[P, R48.68, C0]", null);

    @Test
    void rlcSpecs() {
        assertEquals(1e-9 * 10, RlcSpec.value("10n"), 1e-20);
        assertEquals(2200, RlcSpec.value("2.2k"), 1e-9);
        assertEquals(48.68, RlcSpec.parse("RLC[P,R48.68, C0]").impedance(1e8).re(), 1e-9);
        Cx open = RlcSpec.parse("RLC[S, C0, D0]").s11(100, 50);
        assertEquals(1, open.re(), 1e-6);
        Cx shortG = RlcSpec.parse("RLC[P, R0, L0, D0]").s11(100, 50);
        assertEquals(-1, shortG.re(), 1e-9);
        // an open behind 100 ps: angle -2 * 360 * f * t
        Cx delayed = RlcSpec.parse("RLC[S, C0, D100p]").s11(500, 50);
        assertEquals(-36, delayed.deg(), 1e-3);
        // series R-L: 10 ohm + j 2 pi 100 MHz 100 nH
        Cx z = RlcSpec.parse("RLC[S, R10, L100n]").impedance(100e6);
        assertEquals(10, z.re(), 1e-3);                                      // plus X / QL (QL 100000)
        assertEquals(2 * Math.PI * 10, z.im(), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> RlcSpec.parse("RLC[X5]"));
    }

    @Test
    void fullOslRemovesTheBridgeErrors() {
        double[] f = {1, 50, 100, 200, 400};
        OslCal cal = OslCal.compute(f, read(f, KIT.openSpec()), read(f, KIT.shortSpecParsed()), read(f, KIT.loadSpec()),
                KIT, OslCal.Fixture.BRIDGE, 50, 0, true, 3);
        assertEquals("Open", cal.refType());
        Cx zDut = new Cx(30, 20);
        OslCal.Point[] p = cal.points();
        for (int i = 0; i < f.length; i++) {
            double[] raw = bridge(Impedance.toRefco(50, zDut), f[i]);
            Cx z = Impedance.fromRefco(50, p[i].correct(raw[0], raw[1]));
            assertEquals(30, z.re(), 1e-6, "R at " + f[i]);
            assertEquals(20, z.im(), 1e-6, "X at " + f[i]);
            // the 48.68 ohm load reads as 48.68 ohms, not 50
            double[] rl = bridge(Impedance.toRefco(50, new Cx(48.68, 0)), f[i]);
            assertEquals(48.68, Impedance.fromRefco(50, p[i].correct(rl[0], rl[1])).re(), 1e-6);
        }
    }

    @Test
    void referenceCalWithAnIdealBridge() {
        // ideal bridge: reading = -10 dBm x S; the open as the reference gives S directly
        double[] f = {10, 20};
        OslCal.Reading open = new OslCal.Reading(new double[] {-10, -10}, new double[] {5, 7});
        OslCal cal = OslCal.compute(f, open, null, null, CalStandards.ideal50(), OslCal.Fixture.BRIDGE, 50, 0, true, 1);
        Cx g = Impedance.toRefco(50, new Cx(100, 0));                        // 1/3 at 0 degrees
        Cx s = cal.points()[0].correct(-10 + g.db(), 5 + g.deg());
        assertEquals(1.0 / 3, s.abs(), 1e-4);                               // the made-up load reads -99 dB, not -infinity
        assertEquals(0, s.deg(), 1e-3);
        assertThrows(IllegalArgumentException.class, () -> OslCal.compute(f, open, open, null, CalStandards.ideal50(),
                OslCal.Fixture.BRIDGE, 50, 0, true, 1), "two standards");
    }

    @Test
    void derivedValues() {
        // 25 ohm + j 25 at 10 MHz re 50 ohms
        Cx g = Impedance.toRefco(50, new Cx(25, 25));
        ReflectionValues v = ReflectionValues.of(g, 10, 50);
        assertEquals(25, v.rs(), 1e-9);
        assertEquals(25, v.xs(), 1e-9);
        assertEquals(25 / (2 * Math.PI * 10e6), v.ls(), 1e-15);
        assertEquals(50, v.rp(), 1e-9);                                       // (25^2 + 25^2) / 25
        assertEquals(50, v.xp(), 1e-9);
        assertEquals((1 + g.abs()) / (1 - g.abs()), v.swr(), 1e-9);
        assertEquals(-g.db(), v.value(GraphData.RETURN_LOSS), 1e-9);
        assertEquals(1, v.value(GraphData.COMPONENT_Q), 1e-9);
        assertEquals(45, v.zDeg(), 1e-9);
        // a capacitor: -j 100 ohm at 1 MHz is 1.59 nF
        ReflectionValues c = ReflectionValues.of(Impedance.toRefco(50, new Cx(0, -100)), 1, 50);
        assertEquals(1 / (2 * Math.PI * 1e6 * 100), c.cs(), 1e-15);
    }

    @Test
    void sweepDataAppliesCalPlaneAndR0(@TempDir Path dir) throws Exception {
        double[] f = {1, 50, 100, 200, 400};
        OslCal cal = OslCal.compute(f, read(f, KIT.openSpec()), read(f, KIT.shortSpecParsed()), read(f, KIT.loadSpec()),
                KIT, OslCal.Fixture.BRIDGE, 50, 0, true, 3);
        SweepData d = new SweepData(1, f);
        for (int i = 0; i < f.length; i++) {
            double[] raw = bridge(Impedance.toRefco(50, new Cx(75, 0)), f[i]);
            d.set(i, raw[0], raw[1]);
        }
        d.setCorrection(new SweepData.Correction(null, null, 0, new SweepData.Reflection(cal.points(), 50, 50, true)));
        assertEquals(75, d.value(GraphData.RS, 2), 1e-6);
        assertEquals(1.5, d.value(GraphData.SWR, 2), 1e-6);
        // graph R0 75: a perfect match
        d.setCorrection(new SweepData.Correction(null, null, 0, new SweepData.Reflection(cal.points(), 50, 75, true)));
        assertTrue(d.value(GraphData.S11_DB, 2) < -100);
        assertEquals(75, d.value(GraphData.RS, 2), 1e-6);
        // plane extension: twice the delay; a pure resistance turns by 2 x 360 x f x t
        d.setCorrection(new SweepData.Correction(null, null, 1, new SweepData.Reflection(cal.points(), 50, 50, true)));
        assertEquals(72, d.value(GraphData.S11_ANGLE, 2), 1e-6);           // 0 deg + 2 x 360 x 100 MHz x 1 ns
        // base calibration: interpolated, and written and read back
        Path file = dir.resolve(ReflectionCal.BASE_FILE);
        cal.write(file);
        OslCal back = OslCal.read(file);
        assertEquals(cal.fixture(), back.fixture());
        assertEquals(cal.refType(), back.refType());
        assertEquals(cal.a()[3].re(), back.a()[3].re(), 1e-12);
        OslCal.Point[] at = back.at(new double[] {100});
        assertEquals(cal.points()[2].a().im(), at[0].a().im(), 1e-12);
        SweepSettings s = SweepSettings.defaults();
        assertFalse(cal.matches(s));
    }
}
