package msa.sweep;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SweepTimeTest {

    @Test
    void estimateAndLearnedOverhead() {
        SweepSettings s = SweepSettings.defaults().withSteps(999).withWait(5);   // 1000 points
        assertEquals(8.0, SweepTime.perPointMs(s, 2.0), 1e-12);                   // 5 + 1 + 2
        assertEquals(8.0, SweepTime.seconds(s, 2.0), 1e-12);
        // a sweep that took 10 s: 10 ms per point, so 4 ms overhead
        assertEquals(4.0, SweepTime.overheadFrom(s, 10_000_000_000L), 1e-9);
        assertEquals(0, SweepTime.overheadFrom(s, 1_000_000L), "never negative");
    }

    @Test
    void format() {
        assertEquals("0.45 s", SweepTime.format(0.449));
        assertEquals("12.3 s", SweepTime.format(12.34));
        assertEquals("2 min 05 s", SweepTime.format(125));
        assertEquals("1 h 12 min", SweepTime.format(4320));
        assertEquals("-", SweepTime.format(Double.NaN));
    }
}
