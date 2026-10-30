package org.sq5nry.msa;

import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

public class USBrBuf extends Structure {
    public long numreads; //EEPROM firmware version
    public long magnitude;
    public long phase;

    @Override
    protected List getFieldOrder() {
        return Arrays.asList("numreads", "magnitude", "phase");
    }

    @Override
    public String toString() {
        return "USBrBuf{" +
                "numreads=" + numreads +
                ", magnitude=" + magnitude +
                ", phase=" + phase +
                '}';
    }
}
