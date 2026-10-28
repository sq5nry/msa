package msa.model;

import java.util.Objects;

import msa.vna.TransmissionCal;

/**
 * Settings of the network-analyser modes that are not hardware: how the measurements are referenced and
 * converted.
 *
 * @param planeExtNs     reference plane extension in ns (Prefs "PlaneAdj")
 * @param s21JigAttach   transmission fixture, "Series" or "Shunt" (Prefs "S21Jig", first word)
 * @param s21JigR0       its reference resistance in ohms (S21Jig, second value)
 * @param s21ShuntDelay  delay of the shunt fixture's connection in ns (S21Jig, third value)
 * @param s11BridgeR0    reflection bridge reference resistance (Prefs "S11Bridge", first value)
 * @param s11GraphR0     impedance the reflection graphs are referenced to (S11Bridge, second value)
 * @param s11JigType     "Reflect" (bridge), or a series/shunt fixture used for reflection (S11Bridge, third)
 * @param calLevel       "Operating Cal &gt; Reference To" (desiredCalLevel; not in Prefs.txt)
 * @param throughDelayNs delay of the calibration through connection (lineCalThroughDelay)
 */
public record VnaSettings(double planeExtNs, String s21JigAttach, double s21JigR0, double s21ShuntDelay,
                          double s11BridgeR0, double s11GraphR0, String s11JigType,
                          TransmissionCal.Level calLevel, double throughDelayNs) {

    public VnaSettings {
        Objects.requireNonNull(s21JigAttach);
        Objects.requireNonNull(s11JigType);
        Objects.requireNonNull(calLevel);
    }

    /** As the BASIC program starts: no plane extension, series fixture, 50 ohms, bridge, no reference. */
    public static VnaSettings defaults() {
        return new VnaSettings(0, "Series", 50, 0, 50, 50, "Reflect", TransmissionCal.Level.NONE, 0);
    }

    public VnaSettings withPlaneExt(double ns) {
        return new VnaSettings(ns, s21JigAttach, s21JigR0, s21ShuntDelay, s11BridgeR0, s11GraphR0, s11JigType, calLevel, throughDelayNs);
    }

    public VnaSettings withCalLevel(TransmissionCal.Level l) {
        return new VnaSettings(planeExtNs, s21JigAttach, s21JigR0, s21ShuntDelay, s11BridgeR0, s11GraphR0, s11JigType, l, throughDelayNs);
    }

    public VnaSettings withThroughDelay(double ns) {
        return new VnaSettings(planeExtNs, s21JigAttach, s21JigR0, s21ShuntDelay, s11BridgeR0, s11GraphR0, s11JigType, calLevel, ns);
    }

    public VnaSettings withS21Jig(String attach, double r0, double shuntDelay) {
        return new VnaSettings(planeExtNs, attach, r0, shuntDelay, s11BridgeR0, s11GraphR0, s11JigType, calLevel, throughDelayNs);
    }

    public VnaSettings withS11Bridge(double bridgeR0, double graphR0, String jigType) {
        return new VnaSettings(planeExtNs, s21JigAttach, s21JigR0, s21ShuntDelay, bridgeR0, graphR0, jigType, calLevel, throughDelayNs);
    }
}
