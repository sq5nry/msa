package org.sq5nry.msa;

import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * These parameters instruct the ADC convert commands in EEPROM firmware .hex file
 *    ' Adcs. takes value of 3 only, read both ADC's, Mag and Phase, even if there is only one ADC 'ver117c44
 *    ' Adcs, byte 0 = 3 = "B2" 'ver117c45
 *    ' Clocking. is the clocking option - 0 for AD7685 and 1 for LT1860. Use same clocking option for either 12 or 16 bit serial ADC. =1 'ver116-4r
 *    ' Clocking, byte 1 = "01" but, is no longer read or used by the EEPROM firmware. All ADC types are handled the same. 'ver117c45
 *    ' Delay. is the ADC Convert clock high time delay - 2 for AD7684, 4 for LT1860. Use same delay for both. =4 (about 5.4 usec) 'ver116-4r
 *    ' Delay, byte 2 = "04" CNV up time measured at 4.4 usec 'ver117c45
 *    ' Bits. was the value (in hex) of "adc1mux" in 'ver117-46. No longer used 'ver117c47
 *    ' Average. is number of readings with an average. Default is 1. Later, the variable "adcsamples" is used. 'ver117c21
 *    ' Average, byte 4 = 1-255 = "01" thru "FF". If "0" entered into "Samples", sw will change it to "1" 'ver117c45
 *        UsbAdcControl.Adcs.struct = 3 'must always be 3, becomes "B2" in msadll.dll 'ver117c45
 *        UsbAdcControl.Clocking.struct = 1 'could be 0-255, not used in EEPROM firmware
 *        UsbAdcControl.Delay.struct = 4 'CNV up time. use 4 for 4.4 usec
 *        UsbAdcControl.Bits.struct = 16 'use 16, however, could be 0-255, not used in EEPROM firmware v33
 *        UsbAdcControl.Average.struct = 1 '1-255. Number of read samples to average, default is 1
 */
public class UsbAdcControl extends Structure {
    private static final short UNUSED = 0;

    public short Adcs = 3;
    public short Clocking = UNUSED;
    public short Delay = 4;
    public short Bits = 16;
    public short Average = 1;

    @Override
    protected List getFieldOrder() {
        return Arrays.asList("Adcs", "Clocking", "Delay", "Bits", "Average");
    }

    @Override
    public String toString() {
        return "UsbAdcControl{" +
                "Adcs=" + Adcs +
                ", Clocking=" + Clocking +
                ", Delay=" + Delay +
                ", Bits=" + Bits +
                ", Average=" + Average +
                '}';
    }
}
