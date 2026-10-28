package msa.sweep;

import java.util.Locale;

/**
 * The four operating modes of spectrumanalyzer.bas (global msaMode$, saved in Prefs.txt as "msaMode=").
 * Spectrum analyser with or without the tracking generator are both {@link #SA}; the generator decides.
 */
public enum MsaMode {
    /** Spectrum analyser, with the TG as signal generator or tracking ("SA"). */
    SA("SA", "Spectrum Analyzer"),
    /** Scalar network analyser: transmission magnitude only, MSA without the VNA phase detector ("ScalarTrans"). */
    SCALAR_TRANS("ScalarTrans", "SNA Transmission"),
    /** Vector network analyser, transmission S21 with phase ("VectorTrans"). */
    VECTOR_TRANS("VectorTrans", "VNA Transmission"),
    /** Vector network analyser, reflection S11 through a bridge or fixture ("Reflection"). */
    REFLECTION("Reflection", "VNA Reflection");

    /** The value in Prefs.txt. */
    public final String prefsName;
    /** The Mode menu text. */
    public final String title;

    MsaMode(String prefsName, String title) {
        this.prefsName = prefsName;
        this.title = title;
    }

    /** VNA modes read the phase ADC and steer the PDM ([ReadStep]: msaMode$ not SA or ScalarTrans). */
    public boolean readsPhase() { return this == VECTOR_TRANS || this == REFLECTION; }

    /** Scalar or vector transmission. */
    public boolean transmission() { return this == SCALAR_TRANS || this == VECTOR_TRANS; }

    /** Network-analyser modes: tracking generator always on, operating calibration applies. */
    public boolean networkAnalyser() { return this != SA; }

    /** Prefs.txt value to mode; unknown values give SA. */
    public static MsaMode ofPrefs(String s) {
        if (s != null) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            for (MsaMode m : values()) if (m.prefsName.toLowerCase(Locale.ROOT).equals(t)) return m;
        }
        return SA;
    }
}
