package msa.sweep;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import msa.config.Preferences;
import msa.model.MsaSettings;

class AutoStepsTest {

    private static int lin(double start, double stop, double rbwKHz) {
        return AutoSteps.of(new FrequencyRange(start, stop), SweepSettings.Scale.LINEAR, rbwKHz, false);
    }

    @Test
    void stepIsHalfTheRbw() {
        assertEquals(1000, lin(0, 15.5, 31));             // 15.5 MHz / 15.5 kHz
        assertEquals(1000, lin(0, 15.49, 31), "rounded up to a multiple of 10");
        assertEquals(1010, lin(0, 15.6, 31));
        assertEquals(AutoSteps.MIN_STEPS, lin(-0.075, 0.075, 31), "narrow span: the minimum");
        assertEquals(64520, lin(0, 1000, 31), "wide span: not capped");
        assertTrue(AutoSteps.aboveRecommended(AutoSteps.RECOMMENDED_MAX + 1));
        assertFalse(AutoSteps.aboveRecommended(AutoSteps.RECOMMENDED_MAX));
        assertEquals(SweepSettings.MAX_STEPS, lin(0, 3000, 0.4), "only the technical limit");
        assertEquals(AutoSteps.MIN_STEPS, lin(10, 10, 31), "zero span");
        assertEquals(AutoSteps.TRACKING_STEPS,
                AutoSteps.of(new FrequencyRange(0, 1000), SweepSettings.Scale.LINEAR, 0.4, true));
    }

    @Test
    void logSweepUsesTheStepAtTheStop() {
        // 1..10 MHz with 200 kHz RBW: last step at most 0.1 MHz -> ln(10)/-ln(1-0.01) = 229.1 -> 230
        assertEquals(230, AutoSteps.of(new FrequencyRange(1, 10), SweepSettings.Scale.LOG, 200, false));
        // 0.01..1 MHz with 200 kHz RBW: last step 0.1 MHz -> ln(100)/-ln(0.9) = 43.7 -> minimum 100
        assertEquals(AutoSteps.MIN_STEPS, AutoSteps.of(new FrequencyRange(0.01, 1), SweepSettings.Scale.LOG, 200, false));
        // the largest step really is within RBW/2
        SweepSettings s = SweepSettings.defaults().withRange(new FrequencyRange(1, 15)).withScale(SweepSettings.Scale.LOG)
                .withSteps(AutoSteps.of(new FrequencyRange(1, 15), SweepSettings.Scale.LOG, 31, false));
        assertTrue(s.frequency(s.steps()) - s.frequency(s.steps() - 1) <= 0.0155 + 1e-9);
    }

    @Test
    void rangeDownToZeroMakesALogSweepLinear() {
        SweepSettings log = SweepSettings.defaults().withRange(1, 100).withScale(SweepSettings.Scale.LOG);
        assertEquals(SweepSettings.Scale.LINEAR, log.withRange(0, 100).scale());
        assertEquals(SweepSettings.Scale.LOG, log.withRange(2, 100).scale());
    }

    @Test
    void overrideFlagIsKeptInJavaPrefs() throws Exception {
        msa.config.MsaConfig cfg = msa.config.MsaConfig.read(
                java.nio.file.Path.of(AutoStepsTest.class.getResource("/msa-info/config.txt").toURI()));
        Properties jp = new Properties();
        assertTrue(MsaSettings.from(Preferences.empty(), jp, cfg).settings().autoSteps(), "default: automatic");
        jp.setProperty("autoSteps", "false");
        MsaSettings s = MsaSettings.from(Preferences.empty(), jp, cfg).settings();
        assertFalse(s.autoSteps());
        assertEquals("false", s.javaPrefs().getProperty("autoSteps"));
    }
}
