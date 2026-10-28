package msa.trace;

import java.util.List;

import msa.sweep.MsaMode;

/**
 * What a graph axis shows (CalcGraphData: the SA branch and CalcTransmitGraphData).
 * Codes are the BASIC program's constGraph... values, as stored in Prefs.txt "GraphData=".
 * Reflection types are not ported yet.
 */
public enum GraphData {
    /** constMagDBM: power in dBm (in the transmission modes: raw power before the operating calibration). */
    DBM(17, "dBm", "dBm"),
    /** constMagWatts: 10^(dBm/10) / 1000. */
    WATTS(18, "W", "W"),
    /** constMagV: RMS volts into 50 ohms, sqrt(mW / 20). */
    VOLTS(19, "V", "V"),
    /** constMagDB: transmission in dB, power minus the calibration reference. */
    DB(20, "S21 dB", "S21 dB"),
    /** constMagRatio: transmission as a voltage ratio, 10^(dB/20). */
    RATIO(21, "Ratio", "Mag (Ratio)"),
    /** constAngle: S21 phase in degrees, after the calibration reference and plane extension. */
    ANGLE(22, "S21 Deg", "S21 Deg"),
    /** constGD: group delay in seconds, -d(phase)/d(frequency) between neighbouring steps. */
    GROUP_DELAY(24, "G.D.", "Grp Delay (sec)"),
    /** constInsertionLoss: -dB. */
    INSERTION_LOSS(26, "IL", "Insertion Loss(dB)"),
    /** constRawAngle: phase without the calibration reference (plane extension included, as in BASIC). */
    RAW_ANGLE(33, "Raw Deg", "Raw Deg"),
    /** constNoGraph: axis off (BASIC returns 0). */
    NONE(40, "", "");

    public final int code;
    /** Short label: marker table, data window columns (BASIC yLabel$). */
    public final String unit;
    /** Label above the Y axis (BASIC yAxisLabel$). */
    public final String axisTitle;

    GraphData(int code, String unit, String axisTitle) {
        this.code = code;
        this.unit = unit;
        this.axisTitle = axisTitle;
    }

    /** True for phase-like types, which wrap at +-180 degrees (DataTypeIsAngle). */
    public boolean isAngle() { return this == ANGLE || this == RAW_ANGLE; }

    /** True for the types that need the phase reading. */
    public boolean needsPhase() { return isAngle() || this == GROUP_DELAY; }

    /** Converts a power in dBm to this unit (SA types); NaN stays NaN. Transmission types give NaN. */
    public double fromDbm(double dBm) {
        return switch (this) {
            case DBM -> dBm;
            case WATTS -> Math.pow(10, dBm / 10) / 1000;
            case VOLTS -> Math.sqrt(Math.pow(10, dBm / 10) / 20);
            case NONE -> 0;
            default -> Double.NaN;
        };
    }

    /** Data type for a Prefs.txt code; reflection and auxiliary types are not ported and give NONE. */
    public static GraphData ofCode(int code) {
        for (GraphData g : values()) if (g.code == code) return g;
        return NONE;
    }

    /** The choices of the axis window in a mode (FillRegularGraphData), with the menu text; NONE last. */
    public static List<Choice> choices(MsaMode mode) {
        return switch (mode) {
            case SA -> List.of(new Choice(DBM, "Magnitude (dBm)"), new Choice(WATTS, "Magnitude (Watts)"),
                    new Choice(VOLTS, "Magnitude (Volts)"), new Choice(NONE, "None"));
            case SCALAR_TRANS -> List.of(new Choice(DB, "Transmission (dB)"), new Choice(RATIO, "Transmission (Ratio)"),
                    new Choice(INSERTION_LOSS, "Insertion Loss (dB)"), new Choice(NONE, "None"));
            case VECTOR_TRANS -> List.of(new Choice(DB, "S21 Magnitude (dB)"), new Choice(ANGLE, "S21 Phase Angle"),
                    new Choice(DBM, "Raw Power (dBm)"), new Choice(RAW_ANGLE, "Raw Phase Angle"),
                    new Choice(INSERTION_LOSS, "Insertion Loss (dB)"), new Choice(GROUP_DELAY, "S21 Group Delay"),
                    new Choice(RATIO, "S21 Magnitude (Ratio)"), new Choice(NONE, "None"));
            case REFLECTION -> List.of(new Choice(NONE, "None"));
        };
    }

    /** True if the axis window offers this type in the mode. */
    public boolean validFor(MsaMode mode) {
        return choices(mode).stream().anyMatch(c -> c.data() == this);
    }

    /**
     * GetDefaultGraphData: the primary axis shows dBm (SA) or dB (transmission), -100..0; the other axis
     * shows S21 phase -180..180 in vector transmission and nothing otherwise.
     */
    public static GraphData defaultFor(MsaMode mode, boolean primaryAxis) {
        if (primaryAxis) return mode == MsaMode.SA ? DBM : DB;
        return mode == MsaMode.VECTOR_TRANS ? ANGLE : NONE;
    }

    /** One entry of the axis window's Graph Data list. */
    public record Choice(GraphData data, String name) {}
}
