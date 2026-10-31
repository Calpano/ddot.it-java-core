package it.ddot.core.hostreader;

import it.ddot.core.io.ISourceReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which readers are active, by file extension.
 *
 * <p>JSON format (all fields optional):
 * <pre>{@code
 * {
 *   "enabledExtensions": ["md", "java", "adoc"],
 *   "disabledExtensions": ["pptx", "dns"]
 * }
 * }</pre>
 *
 * <p>Rules:
 * <ul>
 *   <li>If {@code enabledExtensions} is non-empty, only those extensions are active.</li>
 *   <li>{@code disabledExtensions} always takes precedence (removes from the active set).</li>
 *   <li>Default (empty config) activates all extensions.</li>
 * </ul>
 *
 * <p>Unknown fields are ignored. The JSON is read by hand, as the rest of this module writes it by hand: this module
 * depends on nothing but jspecify. The getters and setters stay bean-shaped, so a caller's own JSON binding can still
 * map an embedded readers section onto this class.
 */
public final class ReadersConfig {

    private Set<String> enabledExtensions = Set.of();
    private Set<String> disabledExtensions = Set.of();

    public static ReadersConfig defaults() {return new ReadersConfig();}

    public static ReadersConfig fromJson(Path path) throws IOException {
        return fromJson(Files.readString(path));
    }

    /**
     * @throws IOException when {@code json} is not a JSON object, or one of the two fields is not an array of strings
     */
    public static ReadersConfig fromJson(String json) throws IOException {
        ReadersConfig config = new ReadersConfig();
        new Json(json).readObject(config);
        return config;
    }

    public List<ISourceReader> filter(List<ISourceReader> readers) {
        return readers.stream()
                .filter(reader -> reader.sourceKind().fileExtensions().stream().anyMatch(this::isEnabled))
                .collect(Collectors.toList());
    }

    public Set<String> getDisabledExtensions() {return disabledExtensions;}

    public Set<String> getEnabledExtensions() {return enabledExtensions;}

    public boolean isEnabled(String extension) {
        String ext = extension.toLowerCase();
        if (!enabledExtensions.isEmpty() && !enabledExtensions.contains(ext)) return false;
        return !disabledExtensions.contains(ext);
    }

    public void setDisabledExtensions(Set<String> v) {
        disabledExtensions = v.stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
    }

    public void setEnabledExtensions(Set<String> v) {
        enabledExtensions = v.stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
    }

    /** Just enough JSON for this file: one object, two string arrays, and any other value skipped. */
    private static final class Json {

        private final String s;
        private int i;

        Json(String s) {this.s = s;}

        void readObject(ReadersConfig config) throws IOException {
            expect('{');
            if (peek() == '}') {
                i++;
            } else {
                do {
                    String key = readString();
                    expect(':');
                    switch (key) {
                        case "enabledExtensions" -> config.setEnabledExtensions(readStringArray());
                        case "disabledExtensions" -> config.setDisabledExtensions(readStringArray());
                        default -> skipValue();
                    }
                } while (consume(','));
                expect('}');
            }
            if (peek() != 0) throw error("trailing content");
        }

        private boolean consume(char c) {
            if (peek() != c) return false;
            i++;
            return true;
        }

        private IOException error(String what) {
            return new IOException("readers config: " + what + " at offset " + i);
        }

        private void expect(char c) throws IOException {
            if (!consume(c)) throw error("expected '" + c + "'");
        }

        /** The next non-whitespace character, or 0 at the end. */
        private char peek() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            return i < s.length() ? s.charAt(i) : 0;
        }

        private String readString() throws IOException {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw error("bad \\u escape");
                        try {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad \\u escape");
                        }
                        i += 4;
                    }
                    default -> b.append(e); // \" \\ \/
                }
            }
            throw error("unterminated string");
        }

        private Set<String> readStringArray() throws IOException {
            expect('[');
            Set<String> values = new LinkedHashSet<>();
            if (consume(']')) return values;
            do {
                if (peek() != '"') throw error("expected a string");
                values.add(readString());
            } while (consume(','));
            expect(']');
            return values;
        }

        private void skipValue() throws IOException {
            char c = peek();
            if (c == '"') {
                readString();
            } else if (c == '{' || c == '[') {
                char close = c == '{' ? '}' : ']';
                i++;
                if (consume(close)) return;
                do {
                    if (c == '{') {
                        readString();
                        expect(':');
                    }
                    skipValue();
                } while (consume(','));
                expect(close);
            } else {
                int start = i;
                while (i < s.length() && "{}[],: \t\r\n\"".indexOf(s.charAt(i)) < 0) i++;
                if (i == start) throw error("expected a value");
            }
        }
    }

}
