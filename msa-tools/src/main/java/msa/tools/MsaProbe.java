package msa.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import msa.hw.AdcConfig;
import msa.hw.AdcResult;
import msa.hw.LoggingTransport;
import msa.hw.MsaException;
import msa.hw.MsaHardware;
import msa.hw.MsaProtocol;
import msa.hw.MsaTransport;
import msa.hw.SlimCommands;
import msa.hw.UsbMsa;
import msa.hw.sim.SimulatedTransport;
import msa.hw.usb.Fx2Loader;
import msa.hw.usb.IntelHex;
import msa.hw.usb.LibUsb;
import msa.hw.usb.LibUsbTransport;

/**
 * Command-line probe for the first hardware milestone.
 *
 * <pre>
 * java -jar msa-hw.jar [--sim] [--log vnalog.txt] [--dry-run] COMMAND ...
 *   info                       open the MSA, print firmware version (UsbMSAGetVersions)
 *   load-firmware FILE.hex     download firmware if the board shows up as 0547:1014 / 1012
 *   filter N                   select filter path 1..4
 *   reset-dds 1|3              serial-mode reset of DDS1 or DDS3 to 0 Hz
 *   read-adc [N] [samples]     N ADC reads (default 10), FX2 averaging (default 1)
 *   switch BAND [VIDEO]        latch 4: band 1..3, video wide|mid|narrow|xnarrow (default wide)
 *   smoke [--listen] [--settle MS] [--n N]
 *                              full no-signal hardware check, see {@link SmokeTest}
 *   plan FREQ | init | tune FREQ | sweep START STOP STEPS | console START STOP STEPS
 *                              synthesiser commands, see {@link SynthTool}
 * --dry-run prints the bytes each command would send, without opening anything.
 * </pre>
 * A7 step commands are only sent by plan-based commands (tune, sweep), with PLL words from the planner.
 */
public final class MsaProbe {

    public static void main(String[] argv) {
        List<String> args = new ArrayList<>(List.of(argv));
        boolean sim = args.remove("--sim");
        boolean dryRun = args.remove("--dry-run");
        boolean listen = args.remove("--listen");
        int settle = intOption(args, "--settle", 300);
        int n = intOption(args, "--n", 200);
        Path log = null;
        int i = args.indexOf("--log");
        if (i >= 0) {
            log = Path.of(args.get(i + 1));
            args.remove(i + 1);
            args.remove(i);
        }
        if (args.isEmpty()) {
            usage();
            return;
        }
        try {
            System.exit(run(args, sim, dryRun, log, listen, settle, n));
        } catch (MsaException | msa.synth.SynthException | UnsupportedOperationException | IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("""
            usage: msa-probe [--sim] [--log FILE] [--dry-run] COMMAND
              info | load-firmware FILE.hex | filter 1..4 | reset-dds 1|3 | read-adc [N] [samples]
              switch BAND [wide|mid|narrow|xnarrow] | smoke [--listen] [--settle MS] [--n N]
              plan FREQ | init | tune FREQ [--count N] | sweep START STOP STEPS [--csv FILE]
              console [START STOP STEPS] interactive sweep engine, starts from Prefs.txt (? for help)
              record START STOP STEPS --out FILE [--sweeps N]   record sweeps for replay
              --replay FILE    with plan/init/tune/sweep/console: run on a recording instead of the MSA
                options for these: --msa-info DIR --path P --band 0..3 (default 1) --video V --wait MS
                                   --samples N --tg-offset MHZ --sg MHZ     (frequencies in MHz)""");
    }

    private static int run(List<String> args, boolean sim, boolean dryRun, Path log, boolean listen, int settle, int n) {
        String cmd = args.get(0);
        if (cmd.equals("load-firmware")) {
            loadFirmware(Path.of(args.get(1)));
            return 0;
        }
        if (SynthTool.COMMANDS.contains(cmd)) return SynthTool.run(args, sim, log, settle);
        if (cmd.equals("console")) return ConsoleTool.run(args, sim, log);
        if (cmd.equals("record")) return RecordTool.run(args, sim, log);
        if (dryRun) {
            dryRun(args);
            return 0;
        }
        MsaTransport t;
        if (sim) {
            SimulatedTransport st = new SimulatedTransport();
            // mimic what a real board was seen doing: one status-only frame left after each ADC read
            st.setTrailingStatusFrames(Integer.getInteger("msa.sim.trailing", 1));
            t = st;
        } else {
            t = LibUsbTransport.open();
        }
        if (log != null) t = LoggingTransport.toFile(t, log);
        try (UsbMsa msa = new UsbMsa(t)) {
            System.out.println("opened " + t.description());
            switch (cmd) {
                case "info" -> info(msa);
                case "filter" -> SlimCommands.run(msa, SlimCommands.commandFilter(SlimCommands.filterBank(intArg(args, 1, 1))));
                case "reset-dds" -> SlimCommands.run(msa, SlimCommands.resetSerialDds(intArg(args, 1, 1), 0, 0));
                case "read-adc" -> readAdc(msa, intArg(args, 1, 10), intArg(args, 2, 1));
                case "switch" -> SlimCommands.run(msa, SlimCommands.commandSwitches(switchArg(args)));
                case "smoke" -> {
                    return new SmokeTest(msa, System.out, listen, settle, n).run() == 0 ? 0 : 2;
                }
                default -> usage();
            }
        }
        return 0;
    }

    private static int switchArg(List<String> args) {
        int band = intArg(args, 1, 1);
        SlimCommands.VideoFilter v = args.size() > 2
                ? SlimCommands.VideoFilter.valueOf(args.get(2).toUpperCase(java.util.Locale.ROOT))
                : SlimCommands.VideoFilter.WIDE;
        return SlimCommands.switchData(v, band, false, false);
    }

    private static int intOption(List<String> args, String name, int dflt) {
        int i = args.indexOf(name);
        if (i < 0) return dflt;
        int v = Integer.parseInt(args.get(i + 1));
        args.remove(i + 1);
        args.remove(i);
        return v;
    }

    private static void info(MsaHardware msa) {
        System.out.printf("bcdDevice 0x%04x, UsbMSAGetVersions = %d (0x%04x)%n", msa.bcdDevice(), msa.versions(), msa.versions());
    }

    private static void readAdc(MsaHardware msa, int n, int samples) {
        AdcConfig cfg = AdcConfig.defaults().withAverage(samples);
        for (int k = 0; k < n; k++) {
            long t0 = System.nanoTime();
            AdcResult r = msa.readAdcs(cfg);
            long us = (System.nanoTime() - t0) / 1000;
            System.out.printf("%3d  mag %5d  phase %5d  readsDone %3d  status 0x%02x  %5d us%n",
                    k, r.adc1(), r.adc2(), r.readsDone(), r.status(), us);
        }
    }

    private static void dryRun(List<String> args) {
        List<SlimCommands.Op> ops = switch (args.get(0)) {
            case "filter" -> SlimCommands.commandFilter(SlimCommands.filterBank(intArg(args, 1, 1)));
            case "reset-dds" -> SlimCommands.resetSerialDds(intArg(args, 1, 1), 0, 0);
            case "switch" -> SlimCommands.commandSwitches(switchArg(args));
            case "read-adc" -> List.of(new SlimCommands.Op(
                    MsaProtocol.adcCommand(AdcConfig.defaults().withAverage(intArg(args, 2, 1))), 0));
            default -> throw new MsaException("--dry-run supports filter, reset-dds, switch, read-adc");
        };
        for (SlimCommands.Op op : ops)
            System.out.println(op.hex() + (op.sleepAfterMs() > 0 ? "   (then sleep " + op.sleepAfterMs() + " ms)" : ""));
    }

    private static void loadFirmware(Path hex) {
        List<IntelHex.Chunk> image;
        try {
            image = IntelHex.read(hex);
        } catch (java.io.IOException e) {
            throw new MsaException("cannot read " + hex, e);
        }
        int bytes = image.stream().mapToInt(c -> c.data().length).sum();
        try (LibUsb.Context ctx = new LibUsb.Context()) {
            if (LibUsbTransport.open(ctx) instanceof LibUsbTransport running) {
                System.out.println("firmware already running: " + running.description());
                running.close();
                return;
            }
            LibUsb.Handle h = null;
            for (int pid : new int[] {MsaProtocol.PID_SLIM_LOADER, MsaProtocol.PID_ORIG_LOADER}) {
                h = ctx.open(MsaProtocol.VID, pid);
                if (h != null) break;
            }
            if (h == null) throw new MsaException("no MSA in loader mode (0547:1014 / 1012) found");
            System.out.printf("loading %d bytes in %d chunks into 0547:%04x%n", bytes, image.size(), h.pid);
            try (LibUsb.Handle dev = h) {
                Fx2Loader.load(dev::controlOut, image);
            }
            for (int k = 0; k < 50; k++) { // wait up to 5 s for re-enumeration
                sleep(100);
                if (LibUsbTransport.open(ctx) instanceof LibUsbTransport t) {
                    System.out.println("re-enumerated as " + t.description());
                    t.close();
                    return;
                }
            }
            throw new MsaException("firmware loaded but the device did not re-enumerate as 0547:1015/1013");
        }
    }

    private static int intArg(List<String> a, int idx, int dflt) {
        return a.size() > idx ? Integer.parseInt(a.get(idx)) : dflt;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
