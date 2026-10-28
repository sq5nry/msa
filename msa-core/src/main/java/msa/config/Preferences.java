package msa.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MSA_Info/MSA_Prefs/Prefs.txt as written by "File > Save Prefs" (menuSavePreferenceFile):
 * blocks "StartContext NAME" .. "EndContext" (Grid, Trace, Sweep, ...) of "Key=value" lines.
 *
 * <p>Line ends are mixed in the real file: lines inside a context are joined with chr$(13) (CR), and
 * each context, printed with print #, ends in CR LF. Every line therefore keeps its own terminator, and
 * {@link #format()} gives back the original text byte for byte unless a value was changed with
 * {@link Context#set}. Keys are matched case-insensitively, as the BASIC code upper-cases tags.
 * Typed meaning lives in {@link msa.model.MsaSettings}.
 */
public final class Preferences {

    /** One physical line with its terminator ("\r", "\r\n", "\n" or "" at end of file). */
    public static final class Line {
        final String key;       // null for lines that are not "Key=value"
        String value;
        final String raw;
        final String term;

        private Line(String raw, String term, boolean parseKey) {
            this.raw = raw;
            this.term = term;
            int eq = parseKey ? raw.indexOf('=') : -1;
            if (eq > 0) {
                this.key = raw.substring(0, eq);
                this.value = raw.substring(eq + 1);
            } else {
                this.key = null;
                this.value = null;
            }
        }

        private Line(String key, String value, String term) {
            this.key = key;
            this.value = value;
            this.raw = null;
            this.term = term;
        }

        String text() { return key == null ? raw : key + "=" + value; }
    }

    /** A StartContext .. EndContext block. */
    public static final class Context {
        private final String name;
        private final Line start;
        private final List<Line> lines = new ArrayList<>();
        private Line end;

        private Context(String name, Line start) {
            this.name = name;
            this.start = start;
        }

        public String name() { return name; }

        /** Value of the first line with this key (case-insensitive), trimmed. */
        public Optional<String> get(String key) {
            for (Line l : lines)
                if (l.key != null && l.key.equalsIgnoreCase(key)) return Optional.of(l.value.trim());
            return Optional.empty();
        }

        /** All values of a repeated key (e.g. "CustomColors"). */
        public List<String> getAll(String key) {
            List<String> out = new ArrayList<>();
            for (Line l : lines)
                if (l.key != null && l.key.equalsIgnoreCase(key)) out.add(l.value.trim());
            return out;
        }

        /** Replaces the first line with this key, or appends a new line (CR-terminated) before EndContext. */
        public void set(String key, String value) {
            for (Line l : lines) {
                if (l.key != null && l.key.equalsIgnoreCase(key)) {
                    l.value = value;
                    return;
                }
            }
            lines.add(new Line(key, value, "\r"));
        }

        public List<String> keys() {
            return lines.stream().filter(l -> l.key != null).map(l -> l.key).toList();
        }
    }

    /** Top-level lines and contexts, in file order. */
    private final List<Object> items = new ArrayList<>();

    private Preferences() {}

    public static Preferences empty() { return new Preferences(); }

    public static Preferences read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.ISO_8859_1));
    }

    public static Preferences parse(String text) {
        Preferences p = new Preferences();
        Context cur = null;
        int i = 0, n = text.length();
        while (i < n) {
            int j = i;
            while (j < n && text.charAt(j) != '\r' && text.charAt(j) != '\n') j++;
            String raw = text.substring(i, j);
            String term = j >= n ? "" : (text.charAt(j) == '\r' && j + 1 < n && text.charAt(j + 1) == '\n') ? "\r\n"
                        : String.valueOf(text.charAt(j));
            i = j + term.length();
            String t = raw.trim();
            if (cur == null && t.regionMatches(true, 0, "StartContext", 0, 12)) {
                cur = new Context(t.substring(12).trim(), new Line(raw, term, false));
                p.items.add(cur);
            } else if (cur != null && t.regionMatches(true, 0, "EndContext", 0, 10)) {
                cur.end = new Line(raw, term, false);
                cur = null;
            } else if (cur != null) {
                cur.lines.add(new Line(raw, term, true));
            } else {
                p.items.add(new Line(raw, term, false));
            }
        }
        if (cur != null) throw new IllegalArgumentException("context " + cur.name + " has no EndContext");
        return p;
    }

    public Optional<Context> context(String name) {
        for (Object o : items)
            if (o instanceof Context c && c.name.equalsIgnoreCase(name)) return Optional.of(c);
        return Optional.empty();
    }

    /** The named context, created at the end of the file in the BASIC program's layout if missing. */
    public Context contextOrCreate(String name) {
        return context(name).orElseGet(() -> {
            Context c = new Context(name, new Line("StartContext " + name, "\r", false));
            c.end = new Line("EndContext", "\r\n", false);
            items.add(c);
            return c;
        });
    }

    public List<String> contextNames() {
        return items.stream().filter(o -> o instanceof Context).map(o -> ((Context) o).name).toList();
    }

    /** The file text; identical to the input when nothing was changed. */
    public String format() {
        StringBuilder sb = new StringBuilder();
        for (Object o : items) {
            if (o instanceof Context c) {
                append(sb, c.start);
                for (Line l : c.lines) append(sb, l);
                append(sb, c.end);
            } else {
                append(sb, (Line) o);
            }
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, Line l) {
        sb.append(l.text()).append(l.term);
    }

    /** Writes the file, keeping the previous version as NAME.bak. ISO-8859-1, like the BASIC program. */
    public void write(Path file) throws IOException {
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, format(), StandardCharsets.ISO_8859_1);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Default location: MSA_Info/MSA_Prefs/Prefs.txt. */
    public static Path defaultFile(Path msaInfo) {
        return msaInfo.resolve("MSA_Prefs").resolve("Prefs.txt");
    }
}
