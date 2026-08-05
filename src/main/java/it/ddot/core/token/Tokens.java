package it.ddot.core.token;

import org.jspecify.annotations.NullUnmarked;

import java.util.List;

public class Tokens {

    /** Mirrors {@code JSON.stringify} string escaping (quotes, backslash, control chars). */
    @NullUnmarked
    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    /**
     * Serialize tokens as {@code JSON.stringify(arr, null, 2)} would (2-space indent, field order
     * line/start/end/token/text) plus a trailing newline — byte-identical to the {@code expected.tokens.json} corpus
     * files.
     */
    @NullUnmarked
    public static String toJson(List<Token> tokens) {
        if (tokens.isEmpty()) return "[]\n";
        StringBuilder b = new StringBuilder();
        b.append("[\n");
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            b.append("  {\n");
            b.append("    \"line\": ").append(t.line()).append(",\n");
            b.append("    \"start\": ").append(t.start()).append(",\n");
            b.append("    \"end\": ").append(t.end()).append(",\n");
            b.append("    \"token\": \"").append(escapeJson(t.tokenName().id)).append("\",\n");
            b.append("    \"text\": \"").append(escapeJson(t.text())).append("\"");
            List<String> scopes = t.scopes();
            if (!scopes.isEmpty()) {
                b.append(",\n    \"scopes\": [\n");
                for (int j = 0; j < scopes.size(); j++) {
                    b.append("      \"").append(escapeJson(scopes.get(j))).append("\"");
                    b.append(j < scopes.size() - 1 ? ",\n" : "\n");
                }
                b.append("    ]");
            }
            b.append("\n  }");
            b.append(i < tokens.size() - 1 ? ",\n" : "\n");
        }
        b.append("]\n");
        return b.toString();
    }

}
