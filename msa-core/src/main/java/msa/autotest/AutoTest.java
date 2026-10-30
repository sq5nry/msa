package msa.autotest;

import msa.config.MsaConfig;

/** One self test. */
public interface AutoTest {
    String id();
    String title();
    /** What it does and what a failure points to (shown in the window). */
    String description();
    AutotestModel.Setup setup();
    /** Rough duration in seconds, for the list. */
    int seconds();
    /** Null if the test can run on this hardware, else why not (no TG, no VNA). */
    String notApplicable(MsaConfig cfg);
    /** As {@link #notApplicable(MsaConfig)}, also knowing whether the MSA has the SA/VNA input switch. */
    default String notApplicable(MsaConfig cfg, boolean inputSwitch) { return notApplicable(cfg); }
    AutotestModel.Result run(AutotestContext ctx);
}
