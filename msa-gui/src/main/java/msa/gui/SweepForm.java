package msa.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import msa.config.MsaConfig;
import msa.hw.SlimCommands.VideoFilter;
import msa.model.DisplaySettings;
import msa.sweep.FrequencyRange;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;

/**
 * The rules applied when the Sweep Parameters window is closed with OK ([axisXFinished]), separated from
 * the JavaFX dialog so they can be tested: clamping, centre/span to start/stop, and the log-sweep limits.
 */
public final class SweepForm {

    private SweepForm() {}


    /** What the dialog's fields hold. Text fields are passed as typed. */
    public record Input(boolean centerSpan, String cent, String span, String start, String stop,
                        String steps, String waitMs, String samples,
                        int path, VideoFilter video, int band, int horDiv,
                        boolean linear, SweepSettings.Direction direction,
                        String generatorMHz, boolean reverse, boolean refreshEachScan) {}

    /** New settings and notices the user should see (the BASIC "notice" boxes). */
    public record Result(SweepSettings sweep, DisplaySettings display, List<String> notices) {}

    /**
     * @param tracking true in "Spectrum Analyzer with TG" mode (generator field = TG offset), false in plain
     *                 SA mode (generator field = signal generator frequency); ignored without TG hardware
     * @throws IllegalArgumentException with a message for the user if a field cannot be read
     */
    public static Result apply(Input in, SweepSettings old, DisplaySettings oldDisplay, MsaConfig cfg, boolean tracking) {
        List<String> notices = new ArrayList<>();
        double typed = number("Steps/Sweep", in.steps());
        if (typed > SweepSettings.MAX_STEPS)
            throw new IllegalArgumentException("Steps/Sweep: \"" + in.steps() + "\" is more than the technical limit of "
                    + SweepSettings.MAX_STEPS);
        int steps = Math.max(1, (int) typed);
        int wait = Math.max(0, (int) Math.round(number("Wait", in.waitMs())));
        int samples = Math.max(1, Math.min(255, (int) number("Samples", in.samples())));

        double start, stop;
        if (in.centerSpan()) {
            double c = number("Cent", in.cent()), s = Math.abs(number("Span", in.span()));
            start = round6(c - s / 2);
            stop = round6(c + s / 2);
        } else {
            start = number("Start", in.start());
            stop = number("Stop", in.stop());
            if (stop < start) { double t = start; start = stop; stop = t; }
        }

        boolean linear = in.linear();
        int horDiv = in.horDiv();
        if (!linear) {
            if (start < 0 && stop > 0) {
                notices.add("Log sweep cannot cross zero Hz. Range changed.");
                stop = Math.max(stop, 1);
                start = stop / 100000;
            }
            if (start <= 0 && stop >= 0) {
                if (start == 0 && stop == 0) {
                    linear = true;
                    notices.add("Changed to linear sweep because span is small.");
                    horDiv = 10;
                } else if (start == 0) {
                    notices.add("0 Hz not allowed in log sweep; range changed");
                    start = 0.0001;
                    stop = Math.max(0.001, stop);
                }
            }
            if (!linear && stop < 0) {
                notices.add("Log sweep of negative frequencies is not supported here: linear sweep used.");
                linear = true;
            }
            if (!linear && start > 0) {
                double decades = Math.log10(stop / start);
                if (decades <= 0) {
                    linear = true;                       // zero span: nothing to spread logarithmically
                    notices.add("Zero span: linear sweep used.");
                    horDiv = 10;
                } else if (decades > 9) {
                    start = stop / 1e9;
                    notices.add("Log span cannot exceed 9 decades; sweep limits changed.");
                }
            }
            if (!linear && start <= 0) linear = true;
        }
        horDiv = Math.max(2, Math.min(12, horDiv));
        horDiv = 2 * (horDiv / 2);

        SynthPlanner.Generator gen = old.generator();
        if (cfg.tgTop() > 0) {
            double g = Math.round(number(tracking ? "TG Offset" : "Sig Gen Freq", in.generatorMHz()) * 1e6) / 1e6;
            if (tracking) {
                gen = new SynthPlanner.Tracking(g, in.reverse());
            } else {
                double lo2 = cfg.appxLo2();
                if (g < -75) notices.add("LO3 for Sig Gen Frequency may be too low");
                if (g > 3 * lo2) notices.add("LO3 for Sig Gen Frequency may be too high");
                gen = new SynthPlanner.SignalGen(g);
            }
        }

        SweepSettings s = old.withRange(new FrequencyRange(start, stop)).withSteps(steps).withWait(wait).withSamples(samples)
                .withScale(linear ? SweepSettings.Scale.LINEAR : SweepSettings.Scale.LOG).withDirection(in.direction())
                .withPath(in.path()).withVideo(in.video()).withBand(in.band()).withGenerator(gen);
        DisplaySettings d = oldDisplay.withGridDivisions(horDiv, oldDisplay.gridDivY()).withRefreshEachScan(in.refreshEachScan());
        return new Result(s, d, notices);
    }

    /** Frequencies are kept to whole hertz, as using("####.######") does. */
    static double round6(double mhz) { return Math.round(mhz * 1e6) / 1e6; }

    static double number(String field, String text) {
        String t = text == null ? "" : text.trim().replace(',', '.');
        try {
            double v = Double.parseDouble(t);
            if (!Double.isFinite(v)) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + ": \"" + text + "\" is not a number");
        }
    }

    /** Text for a frequency box (####.######, trailing zeros kept like the BASIC dialog). */
    static String box(double mhz) { return String.format(Locale.ROOT, "%.6f", mhz); }
}
