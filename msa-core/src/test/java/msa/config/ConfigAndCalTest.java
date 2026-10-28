package msa.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ConfigAndCalTest {

    static Path msaInfo() throws Exception {
        return Path.of(ConfigAndCalTest.class.getResource("/msa-info/config.txt").toURI()).getParent();
    }

    @Test
    void readsConfigWithCrLineEnds() throws Exception {
        MsaConfig c = MsaConfig.read(msaInfo().resolve("config.txt"));
        assertEquals(63.9996, c.masterClock());
        assertEquals(3, c.controlBoard());
        assertEquals(2, c.tgTop());
        assertEquals(4, c.filters().size());
        assertEquals(new MsaConfig.Filter(10.7, 0.4), c.filter(1));
        assertEquals(new MsaConfig.Filter(10.71125, 200), c.filter(4));
        assertEquals(new MsaConfig.Pll(2326, 0.974, 0, 0), c.pll1());
        assertEquals(new MsaConfig.Pll(2326, 8, 0, 1), c.pll2());
        assertEquals(4, c.videoFilters().size());
        assertEquals("XNarrow", c.videoFilters().get(3).name());
        assertEquals(270, c.videoFilters().get(3).magTimeConstantMs(), 1e-9);
    }

    @Test
    void realFrequencyTableHasPerBandSections() throws Exception {
        String text = java.nio.file.Files.readString(msaInfo().resolve("MSA_Cal/MSA_CalFreq.txt"),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        PathCalibration.Table f = PathCalibration.parse(text, "FREQTABLE");
        assertEquals(4.428, f.y(1050), 1e-9);
        assertEquals(-1.8, f.y(1051), 1e-9);        // 2G section starts at 1050.1 MHz
        assertEquals(19.7, f.y(3500), 1e-9);        // clamped at the last entry
        assertEquals((2.537 + 2.342) / 2, f.y(125), 1e-9);
    }

    @Test
    void calibrationTableInterpolatesLikeCalConvertMagPhase() {
        String text = """
                *Calibration for path 1 (made-up numbers for the test)
                CalVersion= 1
                MagTable=
                * ADC   dBm   phase
                10000  -100  0
                20000  -60   2
                30000  -20   4, comment allowed? no
                ENDCALFILE=
                """.replace("4, comment allowed? no", "4");
        PathCalibration.Table t = PathCalibration.parse(text, "MAGTABLE");
        assertEquals(3, t.size());
        assertEquals(-100, t.y(5000));      // clamped below
        assertEquals(-80, t.y(15000), 1e-9);
        assertEquals(-20, t.y(40000));      // clamped above
        assertEquals(3, t.z(25000), 1e-9);
        PathCalibration.Table f = PathCalibration.parse("FreqTable=\r0 0\r1000 -1.5\rEndCalFile=\r", "FREQTABLE");
        assertEquals(-0.75, f.y(500), 1e-9);
        assertEquals(-80.75, new PathCalibration(t, f).dBm(15000, 500), 1e-9); // power = magDB + freq correction
    }
}
