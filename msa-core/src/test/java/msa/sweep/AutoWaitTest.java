package msa.sweep;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import msa.config.MsaConfig;
import msa.config.Preferences;
import msa.hw.SlimCommands.VideoFilter;
import msa.model.MsaSettings;

/** Uses the MSA's config.txt: VideoFilters=Wide 0.002, Mid 0.2, Narrow 10, XNarrow 100 uF; RBW 0.4/4/31/200 kHz. */
class AutoWaitTest {

    private static MsaConfig cfg() throws Exception {
        return MsaConfig.read(Path.of(AutoWaitTest.class.getResource("/msa-info/config.txt").toURI()));
    }

    @Test
    void timeConstantsFromConfig() throws Exception {
        MsaConfig c = cfg();
        assertEquals(0.0054, AutoWait.videoTimeConstantMs(VideoFilter.WIDE, c), 1e-12);
        assertEquals(0.54, AutoWait.videoTimeConstantMs(VideoFilter.MID, c), 1e-12);
        assertEquals(27, AutoWait.videoTimeConstantMs(VideoFilter.NARROW, c), 1e-12);
        assertEquals(270, AutoWait.videoTimeConstantMs(VideoFilter.XNARROW, c), 1e-12);
    }

    @Test
    void waitPerVideoFilterAndPath() throws Exception {
        MsaConfig c = cfg();
        SweepSettings s = SweepSettings.defaults().withPath(3);                 // 31 kHz
        assertEquals(1, AutoWait.of(s.withVideo(VideoFilter.WIDE), c), "0.027 + 0.065 ms -> minimum 1 ms");
        assertEquals(3, AutoWait.of(s.withVideo(VideoFilter.MID), c), "2.7 + 0.065 ms");
        assertEquals(136, AutoWait.of(s.withVideo(VideoFilter.NARROW), c), "135 + 0.065 ms");
        assertEquals(1351, AutoWait.of(s.withVideo(VideoFilter.XNARROW), c));
        assertEquals(6, AutoWait.of(s.withPath(1).withVideo(VideoFilter.WIDE), c), "0.4 kHz filter: 5 ms build-up");
    }

    @Test
    void vnaWaitsForThePhaseFilterToo() throws Exception {
        MsaConfig c = cfg();
        SweepSettings s = SweepSettings.defaults().withPath(3).withMode(MsaMode.VECTOR_TRANS);
        assertEquals(1, AutoWait.of(s.withVideo(VideoFilter.WIDE), c), "0.11 ms phase");
        assertEquals(11, AutoWait.of(s.withVideo(VideoFilter.MID), c), "5 x 2 ms phase + 0.065");
        assertEquals(136, AutoWait.of(s.withVideo(VideoFilter.NARROW), c), "magnitude 27 ms is slower than phase 22 ms");
        assertEquals(1351, AutoWait.of(s.withVideo(VideoFilter.XNARROW), c));
    }

    @Test
    void flagKeptInJavaPrefs() throws Exception {
        Properties jp = new Properties();
        assertTrue(MsaSettings.from(Preferences.empty(), jp, cfg()).settings().autoWait(), "default: automatic");
        jp.setProperty("autoWait", "false");
        MsaSettings m = MsaSettings.from(Preferences.empty(), jp, cfg()).settings();
        assertFalse(m.autoWait());
        assertEquals("false", m.javaPrefs().getProperty("autoWait"));
    }
}
