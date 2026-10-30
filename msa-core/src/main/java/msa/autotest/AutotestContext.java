package msa.autotest;

import msa.config.MsaConfig;
import msa.sweep.SweepEngine;
import msa.sweep.SweepSettings;
import msa.trace.SweepData;

/** What a test may do with the MSA. Methods block; they throw {@link Cancelled} when the user stops the run. */
public interface AutotestContext {

    /** A complete sweep and how long it took. */
    record Sweep(SweepData data, SweepSettings settings, double seconds) {}

    /** The user stopped the run. */
    final class Cancelled extends RuntimeException {
        public Cancelled() { super("stopped"); }
    }

    MsaConfig config();

    /**
     * True if the MSA has an SA/VNA input switch with separate transmission (T) and reflection (R) inputs, the
     * T/R selection driven by the control board's transmit/reflect latch bit (Wetterlin's MSA Input Switch).
     */
    default boolean inputSwitch() { return false; }

    /** True if MSA_CalPath{path}.txt exists, so levels are in dBm. */
    boolean hasPathCalibration(int path);

    /** Runs one complete sweep with exactly these settings (no automatic steps, wait or samples). */
    Sweep sweep(SweepSettings s);

    /** {@link SweepEngine#calibratePdm} at the frequency of the last step. */
    SweepEngine.PdmCalibration calibratePdm();

    /** The stored reference results of this MSA. */
    ReferenceStore reference();

    /** A line for the progress display. */
    void progress(String text);

    /** Throws {@link Cancelled} if the user stopped the run. */
    void checkCancelled();
}
