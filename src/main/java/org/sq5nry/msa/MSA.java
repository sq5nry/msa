package org.sq5nry.msa;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

public class MSA {
    public interface CLibrary extends Library {
        Pointer UsbMSAInitialise();
        int UsbMSAGetVersions(Pointer pMSAData);
        boolean UsbMSARelease(Pointer pMSAData);
        int UsbMSAStart(Pointer pMSAData);
        int UsbMSAInit(Pointer pMSAData);
        int UsbMSAGetInstance(Pointer pMSAData);
        int UsbMSAGetState(Pointer pMSAData); // -1=no device +1=device OK
        int UsbMSADeviceReadString(Pointer pMSAData, Memory str, int message_size);
        int UsbMSADeviceAllSlimsAndLoad(Pointer pMSAData, short thisstep, short filtbank, short latch, short pdmcmd, short pdmlatch, Pointer pResults);
        int UsbMSADeviceAllSlimsAndLoadStruct(Pointer pMSAData, UsbAllSlimsAndLoadData data);
        int UsbMSADeviceReadAdcsStruct(Pointer pMSAData, UsbAdcControl data, USBrBuf results);
        int UsbMSADeviceWriteString(Pointer pMSAData, String data, int message_size);
    }

    public static void main(String args[]) throws Exception {
        CLibrary lib = Native.load("msadll", CLibrary.class);
        Pointer msa = null;
        try {
            msa = lib.UsbMSAInitialise();
            System.out.println("*msa=" + msa.toString());
            System.out.println("version=" + lib.UsbMSAGetVersions(msa));
            System.out.println("state=" + lib.UsbMSAGetState(msa));

            System.out.println("UsbMSAStart.ret=" + lib.UsbMSAStart(msa));
            System.out.println("state=" + lib.UsbMSAGetState(msa));

            for(short q=1; q<4; q++) {
                short desiredFreqBand = q;
                short switchFR = 0;
                short switchTR = 0;
                short videoFilterAddress = 1; //0..3
                short x1 = (short) (videoFilterAddress + (4 * desiredFreqBand) + (16 * switchFR) + (32 * switchTR) + 128);
                String data2 = "A20100" + String.format("%02X", x1);
                System.out.println("UsbMSADeviceWriteString.ret=" + lib.UsbMSADeviceWriteString(msa, data2, 4));
                Thread.sleep(500);
                String data3 = "A20100" + String.format("%02X", x1 - 128);
                System.out.println("UsbMSADeviceWriteString.ret=" + lib.UsbMSADeviceWriteString(msa, data3, 4));
                Thread.sleep(500);
                System.out.println("=UsbMSADeviceWriteString.ret=" + lib.UsbMSADeviceWriteString(msa, data2, 4));
                Thread.sleep(500);
            }

            UsbAllSlimsAndLoadData data1 = new UsbAllSlimsAndLoadData();
            data1.filtbank = 1;
            short le1 = 1;  //USB cb
            short le2 = 16;  //USB cb
            short fqud1 = 2;  //USB cb
            short le3 = 4;  //USB cb
            short fqud3 = 8;  //USB cb
            data1.latches = (short) (le1 + fqud1 + le3 + fqud3);
            data1.pdmcmdmult = 64;
            data1.pdmcmdadd = 32;

            System.out.println("UsbMSADeviceAllSlimsAndLoadStruct.data=" + data1);
            System.out.println("UsbMSADeviceAllSlimsAndLoadStruct.ret=" + lib.UsbMSADeviceAllSlimsAndLoadStruct(msa, data1));
            System.out.println("UsbMSADeviceAllSlimsAndLoadStruct.results=" + data1);

            UsbAdcControl data = new UsbAdcControl();
            USBrBuf results = new USBrBuf();
            System.out.println("UsbMSADeviceReadAdcsStruct.data=" + data);
            System.out.println("UsbMSADeviceReadAdcsStruct.ret=" + lib.UsbMSADeviceReadAdcsStruct(msa, data, results));
            System.out.println("UsbMSADeviceReadAdcsStruct.results=" + results);

            for(int ay=0; ay<30; ay++) {
                for(int ax=0; ax<20; ax++) {
                    lib.UsbMSADeviceReadAdcsStruct(msa, data, results);
                    System.out.print(results.magnitude + ", ");
                    Thread.sleep(100);
                }
                System.out.println();
            }

            Memory str = new Memory(100);
            System.out.println("UsbMSADeviceReadString.ret=" + lib.UsbMSADeviceReadString(msa, str, (int)str.size()));
            System.out.println("UsbMSADeviceReadString.results=" + str.getString(0));
        } finally {
            if (msa != null) {
                System.out.println("released successfully?=" + lib.UsbMSARelease(msa));
            }
        }

    }
}
