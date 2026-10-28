package msa.config;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * The MSA_Info folder (config.txt, MSA_Cal, MSA_Prefs) that the tools and the GUI use. It lives in the
 * project, as {@value #FOLDER}/ at the repository root, so a checkout carries its own calibration and the
 * Liberty BASIC installation is not needed. It is found by {@code --msa-info DIR}, or else by looking for
 * {@value #FOLDER}/config.txt in the working directory and its parents, then next to the running jar or
 * classes and their parents (so it works from the repository root, a module folder, IntelliJ, or
 * {@code java -jar module/target/x.jar}).
 */
public final class MsaInfo {

    private MsaInfo() {}

    /** Folder name at the repository root. */
    public static final String FOLDER = "msa-info";
    /** Settings of the Java port itself, in the MSA_Info folder. */
    public static final String APP_PROPERTIES = "msa-java.properties";

    /**
     * @param option the --msa-info argument, or null to search
     * @throws IllegalArgumentException if the folder (or its config.txt) cannot be found
     */
    public static Path locate(String option) {
        if (option != null) {
            Path p = Path.of(option);
            if (!Files.isRegularFile(p.resolve("config.txt")))
                throw new IllegalArgumentException("--msa-info " + option + ": no config.txt there");
            return p;
        }
        Optional<Path> found = searchUp(Path.of("").toAbsolutePath());
        if (found.isEmpty()) found = codeLocation().flatMap(MsaInfo::searchUp);
        return found.orElseThrow(() -> new IllegalArgumentException(
                "MSA_Info folder not found: expected " + FOLDER + "/config.txt in the project (or use --msa-info DIR)"));
    }

    /** {@value #FOLDER} in {@code start} or the nearest parent that has one with a config.txt. */
    static Optional<Path> searchUp(Path start) {
        for (Path d = start; d != null; d = d.getParent()) {
            Path c = d.resolve(FOLDER);
            if (Files.isRegularFile(c.resolve("config.txt"))) return Optional.of(c);
        }
        return Optional.empty();
    }

    private static Optional<Path> codeLocation() {
        try {
            var src = MsaInfo.class.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) return Optional.empty();
            return Optional.of(Path.of(src.getLocation().toURI()).toAbsolutePath());
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** {@value #APP_PROPERTIES}: settings of the Java port. Missing file or keys give the defaults. */
    public record AppConfig(boolean rememberSettings) {
        public static final AppConfig DEFAULTS = new AppConfig(true);

        public static AppConfig load(Path msaInfo) {
            Path f = msaInfo.resolve(APP_PROPERTIES);
            if (!Files.isRegularFile(f)) return DEFAULTS;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                return DEFAULTS;
            }
            return new AppConfig(!"false".equalsIgnoreCase(p.getProperty("rememberSettings", "true").trim()));
        }
    }
}
