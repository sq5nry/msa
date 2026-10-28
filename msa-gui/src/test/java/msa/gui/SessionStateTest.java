package msa.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import msa.hw.SlimCommands.VideoFilter;
import msa.model.DisplaySettings;
import msa.model.MsaSettings;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;
import msa.trace.Markers;
import msa.trace.SweepData;

class SessionStateTest {

    private static SessionState sample() {
        SweepSettings s = SweepSettings.defaults().withRange(0.5, 160).withSteps(1234).withWait(7).withSamples(3)
                .withPath(3).withVideo(VideoFilter.NARROW).withBand(0).withScale(SweepSettings.Scale.LOG)
                .withDirection(SweepSettings.Direction.ALTERNATE).withGenerator(new SynthPlanner.Tracking(1.5, true));
        DisplaySettings d = DisplaySettings.defaults().withGraphData(DisplaySettings.DATA_WATTS, DisplaySettings.DATA_DBM)
                .withY2Axis(new DisplaySettings.AxisRange(-120, -20)).withAutoscale(false, true).withTraceModes(2, 3)
                .withGridDivisions(8, 6).withRefreshEachScan(false);
        return new SessionState(new MsaSettings(s, d, false, false), 12.5, 1.5, true,
                Map.of(Markers.Id.L, 10.0, Markers.Id.M3, 100.25, Markers.Id.PEAK_POS, Double.NaN),
                Markers.Id.M3, false, Markers.LrMode.PEAKS_BOUNDED, 2, 3, true,
                new SessionState.WindowGeometry(-1200.5, 40, 1500, 820, true));
    }

    @Test
    void roundTrip(@TempDir Path dir) throws Exception {
        SessionState st = sample();
        Path f = dir.resolve("MSA_Prefs").resolve(SessionState.FILE);
        st.save(f);
        SessionState.Loaded back = SessionState.load(f, MsaSettings.defaults(), 4).orElseThrow();
        assertEquals(java.util.List.of(), back.warnings());
        SessionState b = back.state();
        assertEquals(st.settings(), b.settings());
        assertEquals(12.5, b.sgoutMHz());
        assertEquals(st.markerFreqs().get(Markers.Id.M3), b.markerFreqs().get(Markers.Id.M3));
        assertTrue(Double.isNaN(b.markerFreqs().get(Markers.Id.PEAK_POS)));
        assertEquals(Markers.Id.M3, b.selected());
        assertFalse(b.showMarkers());
        assertEquals(Markers.LrMode.PEAKS_BOUNDED, b.lrMode());
        assertEquals(3, b.traceWidth2());
        assertTrue(b.displaySweepTime());
        assertEquals(st.window(), b.window(), "position on a left-hand monitor (negative x) kept");
        assertTrue(SessionState.load(dir.resolve("none"), MsaSettings.defaults(), 4).isEmpty());
    }

    @Test
    void badValuesKeepTheBase() {
        Properties p = sample().toProperties();
        p.setProperty("hw.path", "9");
        p.setProperty("sweep.steps", "lots");
        p.setProperty("sweep.start", "200");          // start > stop: range rejected as a whole
        p.setProperty("markers.lrMode", "SIDEWAYS");
        p.setProperty("window.width", "50");         // too small to be a real window: ignored
        MsaSettings base = MsaSettings.defaults();
        SessionState.Loaded l = SessionState.fromProperties(p, base, 4);
        assertEquals(base.sweep().path(), l.state().settings().sweep().path());
        assertEquals(base.sweep().steps(), l.state().settings().sweep().steps());
        assertEquals(base.sweep().range(), l.state().settings().sweep().range());
        assertEquals(Markers.LrMode.INDEPENDENT, l.state().lrMode());
        assertEquals(VideoFilter.NARROW, l.state().settings().sweep().video(), "valid keys still used");
        assertEquals(SweepSettings.Scale.LINEAR, l.state().settings().sweep().scale(), "log needs start > 0: not with the base range");
        assertEquals(5, l.warnings().size(), l.warnings().toString());
        assertNull(l.state().window(), "no window geometry: the default size is used");
    }

    @Test
    void restoredMarkersArePlacedOnTheFirstSweep() {
        GraphState g = new GraphState(1);
        g.restoreMarkers(Map.of(Markers.Id.L, 120.0, Markers.Id.R, 999.0, Markers.Id.PEAK_POS, Double.NaN));
        assertEquals(120.0, g.markerFrequencies().get(Markers.Id.L), "not placed yet: kept as saved");
        g.followAxis(new SweepData(1, new double[] {100, 110, 120, 130}));
        assertEquals(2.0, g.markers.index(Markers.Id.L).orElseThrow());
        assertFalse(g.markers.has(Markers.Id.R), "outside the sweep");
        assertTrue(g.markers.has(Markers.Id.PEAK_POS));
        assertEquals(120.0, g.markerFrequencies().get(Markers.Id.L));
    }

    @Test
    void vectorTransmissionIsRestored() {
        SessionState st = sample();
        SweepSettings vs = st.settings().sweep().withMode(msa.sweep.MsaMode.VECTOR_TRANS)
                .withGenerator(new SynthPlanner.Tracking(0, false)).withScale(SweepSettings.Scale.LINEAR);
        DisplaySettings vd = st.settings().display().withGraphData(22, 20);
        MsaSettings m = new MsaSettings(vs, vd, false, false).withVna(msa.model.VnaSettings.defaults().withPlaneExt(1.25)
                .withCalLevel(msa.vna.TransmissionCal.Level.BASE).withThroughDelay(0.5));
        Properties p = new SessionState(m, 12.5, 1.5, true, Map.of(), null, true, Markers.LrMode.INDEPENDENT, 1, 1, false, null)
                .toProperties();
        SessionState.Loaded l = SessionState.fromProperties(p, MsaSettings.defaults(), 4);
        assertEquals(java.util.List.of(), l.warnings());
        MsaSettings b = l.state().settings();
        assertEquals(msa.sweep.MsaMode.VECTOR_TRANS, b.sweep().mode());
        assertEquals(new SynthPlanner.Tracking(0, false), b.sweep().generator(), "VNA: TG at offset 0");
        assertEquals(1.5, l.state().tgOffsetMHz(), "the SA-with-TG offset is kept for later");
        assertEquals(1.25, b.vna().planeExtNs());
        assertEquals(msa.vna.TransmissionCal.Level.BASE, b.vna().calLevel());
        assertEquals(0.5, b.vna().throughDelayNs());
        assertEquals(22, b.display().y1Data());

        SessionState.Loaded noVna = SessionState.fromProperties(p, MsaSettings.defaults(), 4, true, false);
        assertEquals(msa.sweep.MsaMode.SA, noVna.state().settings().sweep().mode());
        assertEquals(40, noVna.state().settings().display().y1Data(), "phase is not a spectrum analyser graph");
        assertEquals(17, noVna.state().settings().display().y2Data());
        assertEquals(3, noVna.warnings().size(), noVna.warnings().toString());
    }
}
