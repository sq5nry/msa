package msa.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import msa.config.MsaConfig;
import msa.config.Preferences;
import msa.hw.SlimCommands.VideoFilter;
import msa.sweep.FrequencyRange;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;

/** Uses the real Prefs.txt and config.txt of this MSA. */
class MsaSettingsTest {

    private static Path info() throws Exception {
        return Path.of(MsaSettingsTest.class.getResource("/msa-info/config.txt").toURI()).getParent();
    }

    private static MsaConfig cfg() throws Exception { return MsaConfig.read(info().resolve("config.txt")); }

    private static String prefsText() throws Exception {
        return Files.readString(Preferences.defaultFile(info()), StandardCharsets.ISO_8859_1);
    }

    @Test
    void prefsRoundTripIsByteIdentical() throws Exception {
        String text = prefsText();
        assertTrue(text.contains("EndContext\r\n") && text.contains("NumSteps=400\r"),
                "the BASIC program ends lines in a context with CR and each context with CR LF");
        Preferences p = Preferences.parse(text);
        assertEquals(List.of("Grid", "Trace", "Sweep"), p.contextNames());
        assertEquals(text, p.format());
    }

    @Test
    void loadsThisMsasSavedSetup() throws Exception {
        MsaSettings.Loaded l = MsaSettings.load(info(), cfg());
        assertEquals(List.of(), l.warnings());
        SweepSettings s = l.settings().sweep();
        assertEquals(new FrequencyRange(-0.075, 0.075), s.range());
        assertEquals(0, s.range().centerMHz(), 1e-12);
        assertEquals(0.15, s.range().spanMHz(), 1e-12);
        assertEquals(400, s.steps());
        assertEquals(SweepSettings.Scale.LINEAR, s.scale());
        assertEquals(SweepSettings.Direction.LEFT_TO_RIGHT, s.direction());
        assertEquals(0, s.waitMs());
        assertEquals(1, s.samples());
        assertEquals(3, s.path());
        assertEquals(1, s.band());
        assertEquals(VideoFilter.WIDE, s.video());
        assertEquals(new SynthPlanner.SignalGen(10), s.generator(), "IsTG=0: plain SA, generator at SGPreset");

        DisplaySettings d = l.settings().display();
        assertEquals(DisplaySettings.DATA_NONE, d.y1Data());
        assertEquals(DisplaySettings.DATA_DBM, d.y2Data());
        assertEquals(new DisplaySettings.AxisRange(-100, 0), d.y2Axis());
        assertFalse(d.autoscaleY2());
        assertTrue(d.refreshEachScan());
        assertEquals(10, d.gridDivX());
        assertEquals(new DisplaySettings.Rgb(0, 0, 0), d.background());
        assertEquals(new DisplaySettings.Rgb(150, 150, 140), d.gridLine());
        assertEquals(new DisplaySettings.Rgb(0, 255, 255), d.trace1());
        assertEquals(new DisplaySettings.Rgb(255, 200, 90), d.trace2());
        assertEquals(1, d.y2Mode());                         // TraceModes=1,1: normal, erase
    }

    @Test
    void savingChangesOnlyTheChangedLines() throws Exception {
        MsaSettings.Loaded l = MsaSettings.load(info(), cfg());
        MsaSettings changed = l.settings().withSweep(l.settings().sweep()
                .withRange(100, 200).withSteps(200).withVideo(VideoFilter.NARROW)
                .withGenerator(new SynthPlanner.Tracking(0)));
        changed.writeTo(l.prefs(), l.settings(), cfg());
        String[] before = prefsText().split("\r\n|\r", -1);
        String[] after = l.prefs().format().split("\r\n|\r", -1);
        assertEquals(before.length, after.length, "no lines added or removed");
        List<String> diff = new java.util.ArrayList<>();
        for (int i = 0; i < before.length; i++) if (!before[i].equals(after[i])) diff.add(after[i]);
        assertEquals(List.of("IsTG=1", "VideoFilter=Narrow", "MinMaxXAxis=100 200", "NumSteps=200"), diff);
    }

    @Test
    void saveAndReloadIncludingJavaOnlyValues(@TempDir Path tmp) throws Exception {
        Path info = tmp.resolve("MSA_Info");
        Files.createDirectories(info.resolve("MSA_Prefs"));
        Files.copy(Preferences.defaultFile(info()), Preferences.defaultFile(info));
        MsaConfig cfg = cfg();
        MsaSettings.Loaded l = MsaSettings.load(info, cfg);
        MsaSettings s2 = l.settings().withSweep(l.settings().sweep().withSamples(16)
                .withDirection(SweepSettings.Direction.ALTERNATE).withWait(10))
                .withDisplay(l.settings().display().withY2Axis(new DisplaySettings.AxisRange(-120, 10)).withTraceModes(1, 2));
        MsaSettings.Loaded saved = s2.save(info, l, cfg);
        assertTrue(Files.exists(info.resolve("MSA_Prefs/Prefs.txt.bak")), "previous file kept");
        assertEquals(prefsText(), Files.readString(info.resolve("MSA_Prefs/Prefs.txt.bak"), StandardCharsets.ISO_8859_1));

        MsaSettings.Loaded back = MsaSettings.load(info, cfg);
        assertEquals(s2, back.settings());
        assertEquals(List.of(), back.warnings());
        // saving again without changes leaves the file as it is
        String once = Files.readString(info.resolve("MSA_Prefs/Prefs.txt"), StandardCharsets.ISO_8859_1);
        back.settings().save(info, back, cfg);
        assertEquals(once, Files.readString(info.resolve("MSA_Prefs/Prefs.txt"), StandardCharsets.ISO_8859_1));
        assertEquals(s2, saved.settings());
    }

    @Test
    void badOrUnsupportedValuesBecomeDefaultsWithWarnings() throws Exception {
        String text = "StartContext Sweep\rmsaMode=Reflection\rPath=9\rVideoFilter=Foo\rNumSteps=abc\r"
                + "NormRev=1\rBaseFreq=10\rMinMaxXAxis=200 100\rIsLinear=0 1 1\rEndContext\r";
        MsaSettings.Loaded l = MsaSettings.from(Preferences.parse(text), new Properties(), cfg());
        SweepSettings s = l.settings().sweep();
        assertEquals(1, s.path());
        assertEquals(VideoFilter.WIDE, s.video());
        assertEquals(new FrequencyRange(100, 200), s.range(), "min/max swapped into order");
        assertEquals(SweepSettings.Scale.LOG, s.scale());
        assertEquals(new SynthPlanner.Tracking(0, true), s.generator(), "NormRev=1 is reverse tracking");
        String all = String.join("\n", l.warnings());
        for (String w : Arrays.asList("Reflection", "Path=9", "VideoFilter=Foo", "NumSteps=abc", "BaseFreq"))
            assertTrue(all.contains(w), "warning about " + w + " in:\n" + all);
    }

    @Test
    void missingFileGivesDefaults(@TempDir Path tmp) throws Exception {
        MsaSettings.Loaded l = MsaSettings.load(tmp, cfg());
        assertEquals(MsaSettings.defaults(), l.settings());
    }

    @Test
    void centerSpanView() {
        FrequencyRange r = FrequencyRange.ofCenterSpan(100, 20);
        assertEquals(new FrequencyRange(90, 110), r);
        assertEquals(new FrequencyRange(95, 105), r.withSpan(10));
        assertEquals(new FrequencyRange(190, 210), r.withCenter(200));
        assertThrows(IllegalArgumentException.class, () -> new FrequencyRange(2, 1));
    }

    private static MsaSettings.Loaded withSweepKeys(String... keyValues) throws Exception {
        Preferences p = Preferences.parse(prefsText());
        Preferences.Context sw = p.context("Sweep").orElseThrow();
        for (int i = 0; i < keyValues.length; i += 2) sw.set(keyValues[i], keyValues[i + 1]);
        return MsaSettings.from(p, new Properties(), cfg());
    }

    @Test
    void loadsVectorTransmission() throws Exception {
        MsaSettings.Loaded l = withSweepKeys("msaMode", "VectorTrans", "PlaneAdj", "2.5", "GraphData", "22 20",
                "S21Jig", "Shunt 75 0.3");
        assertEquals(List.of(), l.warnings());
        MsaSettings m = l.settings();
        assertEquals(msa.sweep.MsaMode.VECTOR_TRANS, m.sweep().mode());
        assertInstanceOf(SynthPlanner.Tracking.class, m.sweep().generator(), "IsTG=0 in the file, but VNA tracks");
        assertEquals(2.5, m.vna().planeExtNs());
        assertEquals("Shunt", m.vna().s21JigAttach());
        assertEquals(75, m.vna().s21JigR0());
        assertEquals(0.3, m.vna().s21ShuntDelay());
        assertEquals(22, m.display().y1Data());
        assertEquals(20, m.display().y2Data());

        Preferences p = Preferences.parse(prefsText());
        MsaSettings.Loaded base = MsaSettings.from(p, new Properties(), cfg());
        m.withVna(m.vna().withPlaneExt(1)).writeTo(p, base.settings(), cfg());
        assertEquals("VectorTrans", p.context("Sweep").orElseThrow().get("msaMode").orElseThrow());
        assertEquals("1", p.context("Sweep").orElseThrow().get("PlaneAdj").orElseThrow());
    }

    @Test
    void graphDataOfAnotherModeFallsBackToTheDefault() throws Exception {
        MsaSettings.Loaded l = withSweepKeys("GraphData", "22 20");      // SA mode with transmission types
        assertEquals(40, l.settings().display().y1Data());
        assertEquals(17, l.settings().display().y2Data());
        assertEquals(2, l.warnings().size(), l.warnings().toString());
        MsaSettings.Loaded r = withSweepKeys("msaMode", "Reflection");
        assertEquals(msa.sweep.MsaMode.VECTOR_TRANS, r.settings().sweep().mode());
        assertTrue(r.warnings().get(0).contains("not ported"));
    }

    @Test
    void calLevelAndThroughDelayInJavaPrefs() throws Exception {
        Properties jp = new Properties();
        jp.setProperty("vna.calLevel", "BAND");
        jp.setProperty("vna.throughDelayNs", "0.25");
        MsaSettings m = MsaSettings.from(Preferences.empty(), jp, cfg()).settings();
        assertEquals(msa.vna.TransmissionCal.Level.BAND, m.vna().calLevel());
        assertEquals(0.25, m.vna().throughDelayNs());
        assertEquals("BAND", m.javaPrefs().getProperty("vna.calLevel"));
        assertEquals("0.25", m.javaPrefs().getProperty("vna.throughDelayNs"));
    }
}
