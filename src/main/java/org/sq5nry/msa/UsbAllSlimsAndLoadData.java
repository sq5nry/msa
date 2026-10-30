package org.sq5nry.msa;

import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

public class UsbAllSlimsAndLoadData extends Structure {
    public short thisstep;
    public short filtbank;
    public short latches;
    public short pdmcommand;
    public short pdmcmdmult;
    public short pdmcmdadd;

    @Override
    protected List getFieldOrder() {
        return Arrays.asList("thisstep", "filtbank", "latches", "pdmcommand", "pdmcmdmult", "pdmcmdadd");
    }

    @Override
    public String toString() {
        return "UsbAllSlimsAndLoadData{" +
                "thisstep=" + thisstep +
                ", filtbank=" + filtbank +
                ", latches=" + latches +
                ", pdmcommand=" + pdmcommand +
                ", pdmcmdmult=" + pdmcmdmult +
                ", pdmcmdadd=" + pdmcmdadd +
                '}';
    }
}
