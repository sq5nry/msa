package msa.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MSA_Info/config.txt, the "Hardware Configuration Manager" file written by spectrumanalyzer.bas.
 * Lines are "key=value"; Liberty BASIC writes them with bare CR line ends, so any of CR, LF or CRLF is accepted.
 * "Filter=" appears once per resolution-filter path, in path order.
 */
public final class MsaConfig {

    /** One resolution-filter path: final IF centre in MHz and bandwidth in kHz. */
    public record Filter(double freqMHz, double bwKHz) {}

    /** One video filter: name, magnitude and phase capacitors in uF. */
    public record VideoFilterCaps(String name, double magCapUF, double phaseCapUF) {
        /** BASIC: videoMagTC = 2.7 * videoMagCap (ms, 2.7k resistor). */
        public double magTimeConstantMs() { return 2.7 * magCapUF; }
        /** BASIC: videoPhaseTC = 10 * videoPhaseCap (ms, 10k resistor). */
        public double phaseTimeConstantMs() { return 10 * phaseCapUF; }
    }

    /** One PLL: chip type (2325, 2326, 2350, 2353, 4112), phase-detector frequency, mode, polarity. */
    public record Pll(int type, double phaseFreqMHz, int mode, int phasePolarity) {
        public boolean fractional() { return mode == 1; }
    }

    private final Map<String, String> values;
    private final List<Filter> filters;
    private final List<VideoFilterCaps> videoFilters;

    private MsaConfig(Map<String, String> values, List<Filter> filters, List<VideoFilterCaps> videoFilters) {
        this.values = values;
        this.filters = List.copyOf(filters);
        this.videoFilters = List.copyOf(videoFilters);
    }

    public static MsaConfig read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.ISO_8859_1));
    }

    public static MsaConfig parse(String text) {
        Map<String, String> v = new LinkedHashMap<>();
        List<Filter> filters = new ArrayList<>();
        List<VideoFilterCaps> video = new ArrayList<>();
        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw.strip();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            String val = line.substring(eq + 1).strip();
            if (key.equalsIgnoreCase("Filter")) {
                String[] w = val.split("\\s+");
                filters.add(new Filter(Double.parseDouble(w[0]), w.length > 1 ? Double.parseDouble(w[1]) : 0));
            } else if (key.equalsIgnoreCase("VideoFilters")) {
                for (String item : val.split(";")) {
                    String[] w = item.split(",");
                    if (w.length >= 3)
                        video.add(new VideoFilterCaps(w[0].strip(), Double.parseDouble(w[1].strip()), Double.parseDouble(w[2].strip())));
                }
            } else {
                v.put(key.toLowerCase(Locale.ROOT), val);
            }
        }
        return new MsaConfig(v, filters, video);
    }

    public String raw(String key) { return values.get(key.toLowerCase(Locale.ROOT)); }

    public double number(String key) {
        String s = raw(key);
        if (s == null) throw new IllegalArgumentException("config.txt has no " + key + "=");
        return Double.parseDouble(s);
    }

    public double number(String key, double dflt) {
        String s = raw(key);
        return s == null || s.isEmpty() ? dflt : Double.parseDouble(s);
    }

    public int integer(String key, int dflt) { return (int) Math.round(number(key, dflt)); }

    // ---- named accessors for what the synthesiser code needs
    public double masterClock() { return number("masterclock"); }
    public int controlBoard() { return integer("cb", 0); }
    /** 0 = no tracking generator, 1 = original (PLL3, no DDS3), 2 = PLL3 + DDS3. */
    public int tgTop() { return integer("TGtop", 0); }
    public double appxDds1() { return number("appxdds1"); }
    public double dds1FilterBw() { return number("dds1filbw"); }
    public double appxDds3() { return number("appxdds3", 0); }
    public double dds3FilterBw() { return number("dds3filbw", 0); }
    public double appxLo2() { return number("appxLO2"); }
    public boolean hasVna() { return integer("hasVNA", 0) == 1; }

    public Pll pll1() { return new Pll(integer("PLL1", 0), number("PLL1phasefreq"), integer("PLL1mode", 0), integer("PLL1phasepolarity", 0)); }
    /** PLL2 is always integer-N in the BASIC code. */
    public Pll pll2() { return new Pll(integer("PLL2", 0), number("PLL2phasefreq"), 0, integer("PLL2phasepolarity", 0)); }
    public Pll pll3() { return new Pll(integer("PLL3", 0), number("PLL3phasefreq", 0), integer("PLL3mode", 0), integer("PLL3phasepolarity", 0)); }

    public List<Filter> filters() { return filters; }

    /** Path 1..N. */
    public Filter filter(int path) {
        if (path < 1 || path > filters.size()) throw new IllegalArgumentException("path must be 1.." + filters.size());
        return filters.get(path - 1);
    }

    public List<VideoFilterCaps> videoFilters() { return videoFilters; }
}
