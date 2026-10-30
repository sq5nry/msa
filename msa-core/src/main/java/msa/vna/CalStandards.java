package msa.vna;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A set of reflection calibration standards, as kept in MSA_Info/OperatingCal/CalSets/*.txt (OSLGetFileData$):
 * <pre>
 * Name=Ideal 50 ohms
 * Description=50 ohms, good for cal plane on back of SMA connectors
 * Open=RLC[S, C0, D0]
 * Short=RLC[P, R0, L0, D0]
 * Load=RLC[P,R50, C0]
 * </pre>
 * A spec without brackets is taken as the inside of RLC[...] (older files).
 */
public record CalStandards(String name, String description, String open, String shortSpec, String load, Path file) {

    public static final String FOLDER = "CalSets";

    /** "Ideal 50 ohms", written by OSLGetCalSets when the folder does not exist. */
    public static CalStandards ideal50() {
        return new CalStandards("Ideal 50 ohms", "50 ohms, good for cal plane on back of SMA connectors",
                "RLC[S, C0, D0]", "RLC[P, R0, L0, D0]", "RLC[P,R50, C0]", null);
    }

    public RlcSpec openSpec() { return RlcSpec.parse(open); }
    public RlcSpec shortSpecParsed() { return RlcSpec.parse(shortSpec); }
    public RlcSpec loadSpec() { return RlcSpec.parse(load); }

    /** All readable sets in the folder, by name; "Ideal 50 ohms" is added if the folder has none of that name. */
    public static List<CalStandards> list(Path calSetsFolder) {
        List<CalStandards> out = new ArrayList<>();
        if (calSetsFolder != null && Files.isDirectory(calSetsFolder)) {
            try (var files = Files.list(calSetsFolder)) {
                for (Path f : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt")).toList()) {
                    try {
                        out.add(read(f));
                    } catch (IOException | RuntimeException ignored) {
                        // an unreadable or incomplete file is left out, as the BASIC list does
                    }
                }
            } catch (IOException ignored) {
                // no list
            }
        }
        out.sort(Comparator.comparing(c -> c.name().toLowerCase(Locale.ROOT)));
        if (out.stream().noneMatch(c -> c.name().equalsIgnoreCase("Ideal 50 ohms"))) out.add(0, ideal50());
        return out;
    }

    public static CalStandards read(Path f) throws IOException {
        String name = null, descr = "", open = null, sh = null, load = null;
        for (String line : Files.readAllLines(f, StandardCharsets.ISO_8859_1)) {
            int eq = line.indexOf('=');
            if (eq < 2) continue;
            String id = line.substring(0, eq).strip().toUpperCase(Locale.ROOT);
            String v = line.substring(eq + 1).strip();
            String spec = v.contains("[") ? v : "RLC[" + v + "]";
            switch (id) {
                case "NAME" -> name = v;
                case "DESCRIPTION" -> descr = v;
                case "OPEN" -> open = spec;
                case "SHORT" -> sh = spec;
                case "LOAD" -> load = spec;
                default -> { }
            }
        }
        if (name == null || open == null || sh == null || load == null)
            throw new IOException("incomplete calibration standard file " + f);
        CalStandards c = new CalStandards(name, descr, open, sh, load, f);
        c.openSpec();
        c.shortSpecParsed();
        c.loadSpec();
        return c;
    }

    /** OSLSaveCalSetFile. */
    public void write(Path f) throws IOException {
        Files.createDirectories(f.toAbsolutePath().getParent());
        Files.write(f, List.of("Name=" + name, "Description=" + description, "Open=" + open, "Short=" + shortSpec,
                "Load=" + load), StandardCharsets.ISO_8859_1);
    }

    @Override public String toString() { return name; }
}
