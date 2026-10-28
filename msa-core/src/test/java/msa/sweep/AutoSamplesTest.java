package msa.sweep;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import msa.config.MsaConfig;
import msa.config.Preferences;
import msa.hw.SlimCommands.VideoFilter;
import msa.model.MsaSettings;

class AutoSamplesTest {

    private static MsaConfig cfg() throws Exception {
        return MsaConfig.read(Path.of(AutoSamplesTest.class.getResource("/msa-info/config.txt").toURI()));
    }

    @Test
    void averagingOnlyWithTheWideVideoFilter() throws Exception {
        MsaConfig c = cfg();
        SweepSettings s = SweepSettings.defaults();
        assertEquals(AutoSamples.WIDE_SAMPLES, AutoSamples.of(s.withVideo(VideoFilter.WIDE), c));
        assertEquals(1, AutoSamples.of(s.withVideo(VideoFilter.MID), c));
        assertEquals(1, AutoSamples.of(s.withVideo(VideoFilter.NARROW), c));
        assertEquals(1, AutoSamples.of(s.withVideo(VideoFilter.XNARROW), c));
    }

    @Test
    void flagKeptInJavaPrefs() throws Exception {
        Properties jp = new Properties();
        assertTrue(MsaSettings.from(Preferences.empty(), jp, cfg()).settings().autoSamples(), "default: automatic");
        jp.setProperty("autoSamples", "false");
        MsaSettings m = MsaSettings.from(Preferences.empty(), jp, cfg()).settings();
        assertFalse(m.autoSamples());
        assertEquals("false", m.javaPrefs().getProperty("autoSamples"));
    }
}
