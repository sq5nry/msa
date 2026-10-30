package msa.trace;

import java.util.List;

import msa.sweep.MsaMode;

/**
 * What a graph axis shows (CalcGraphData: the SA branch and CalcTransmitGraphData).
 * Codes are the BASIC program's constGraph... values, as stored in Prefs.txt "GraphData=".
 * The reflection types are derived from S11 referenced to the graph R0 (CalcReflectDerivedData,
 * CalcReflectGraphData).
 */
public enum GraphData {
    /** constGraphS11DB: S11 magnitude in dB, re the graph R0. */
    S11_DB(1, "S11 dB", "S11 Mag(dB)"),
    /** constGraphS11Ang: S11 angle in degrees. */
    S11_ANGLE(2, "S11 Deg", "S11 Deg"),
    /** constRho: |S11|. */
    RHO(3, "Rho", "Rho"),
    /** constImpedMag: |Z| ohms. */
    Z_MAG(4, "Z ohms", "Z ohms"),
    /** constImpedAng: angle of Z, degrees. */
    Z_ANGLE(5, "Z Deg", "Z Deg"),
    /** constSerR: series resistance. */
    RS(6, "Rs", "Rs"),
    /** constSerReact: series reactance. */
    XS(7, "Xs", "Xs"),
    /** constParR: equivalent parallel resistance. */
    RP(8, "Rp", "Rp"),
    /** constParReact: equivalent parallel reactance. */
    XP(9, "Xp", "Xp"),
    /** constSerC: equivalent series capacitance, F. */
    CS(10, "Cs", "Cs"),
    /** constSerL: equivalent series inductance, H. */
    LS(11, "Ls", "Ls"),
    /** constParC: equivalent parallel capacitance, F. */
    CP(12, "Cp", "Cp"),
    /** constParL: equivalent parallel inductance, H. */
    LP(13, "Lp", "Lp"),
    /** constSWR. */
    SWR(14, "SWR", "SWR"),
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
    /** constTheta: the same as the S11 angle. */
    THETA(23, "Theta", "Theta"),
    /** constGD: group delay in seconds, -d(phase)/d(frequency) between neighbouring steps. */
    GROUP_DELAY(24, "G.D.", "Grp Delay (sec)"),
    /** constReturnLoss: -S11 dB. */
    RETURN_LOSS(25, "RL", "RL"),
    /** constInsertionLoss: -dB. */
    INSERTION_LOSS(26, "IL", "Insertion Loss(dB)"),
    /** constReflectPower: 100 rho^2. */
    REFLECT_POWER(27, "Ref%", "Reflect Pow(%)"),
    /** constComponentQ: |Xs| / Rs. */
    COMPONENT_Q(28, "Q", "Component Q"),
    /** constAdmitMag: 1 / |Z|, siemens. */
    ADMIT_MAG(29, "Y", "Admit. (S)"),
    /** constAdmitAng: -angle of Z. */
    ADMIT_ANGLE(30, "Admit Deg", "Admit Deg"),
    /** constConductance: real part of 1 / Z. */
    CONDUCTANCE(31, "Conduct", "Conduct. (S)"),
    /** constSusceptance: imaginary part of 1 / Z. */
    SUSCEPTANCE(32, "Suscep", "Suscep. (S)"),
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
    public boolean isAngle() {
        return this == ANGLE || this == RAW_ANGLE || this == S11_ANGLE || this == THETA || this == Z_ANGLE || this == ADMIT_ANGLE;
    }

    /** True for the types derived from the reflection S11. */
    public boolean reflection() { return code <= 14 || code == 23 || code == 25 || (code >= 27 && code <= 32); }

    /** True for the types that need the phase reading. */
    public boolean needsPhase() { return isAngle() || this == GROUP_DELAY || reflection(); }

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

    /** Data type for a Prefs.txt code; auxiliary types are not ported and give NONE. */
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
            case REFLECTION -> List.of(new Choice(S11_DB, "S11 Magnitude (dB)"), new Choice(S11_ANGLE, "S11 Phase Angle (Deg)"),
                    new Choice(RHO, "Reflect Coef. Mag (Rho)"), new Choice(THETA, "Reflect Coef. Angle (Theta)"),
                    new Choice(Z_MAG, "Impedance Mag (Z Mag)"), new Choice(Z_ANGLE, "Impedance Angle (Z Ang)"),
                    new Choice(RS, "Series Resistance (Rs)"), new Choice(XS, "Series Reactance (Xs)"),
                    new Choice(CS, "Series Capacitance (Cs)"), new Choice(LS, "Series Inductance (Ls)"),
                    new Choice(RP, "Parallel Resistance (Rp)"), new Choice(XP, "Parallel Reactance (Xp)"),
                    new Choice(CP, "Parallel Capacitance (Cp)"), new Choice(LP, "Parallel Inductance (Lp)"),
                    new Choice(RETURN_LOSS, "Return Loss (dB)"), new Choice(REFLECT_POWER, "Reflected Power (%)"),
                    new Choice(COMPONENT_Q, "Component Q"), new Choice(SWR, "VSWR"), new Choice(NONE, "None"));
        };
    }

    /** True if the axis window offers this type in the mode. */
    public boolean validFor(MsaMode mode) {
        return choices(mode).stream().anyMatch(c -> c.data() == this);
    }

    /**
     * GetDefaultGraphData: the primary axis shows dBm (SA), dB (transmission) or S11 dB (reflection), -100..0;
     * the other axis shows the phase -180..180 in the vector modes and nothing otherwise.
     */
    public static GraphData defaultFor(MsaMode mode, boolean primaryAxis) {
        if (mode == MsaMode.REFLECTION) return primaryAxis ? S11_DB : S11_ANGLE;
        if (primaryAxis) return mode == MsaMode.SA ? DBM : DB;
        return mode == MsaMode.VECTOR_TRANS ? ANGLE : NONE;
    }

    /** One entry of the axis window's Graph Data list. */
    public record Choice(GraphData data, String name) {}
}
