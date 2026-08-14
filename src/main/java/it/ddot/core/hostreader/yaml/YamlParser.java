package it.ddot.core.hostreader.yaml;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts ddot.it-relevant content from YAML source.
 *
 * <p>Recognises four content types:
 * <ul>
 *   <li><strong>Line comments</strong> ({@code # text}) – stripped of the leading {@code #}.</li>
 *   <li><strong>Key-value pairs</strong> ({@code key: value}) – only the value part is delivered.</li>
 *   <li><strong>List items</strong> ({@code - value}) – only the value part is delivered.</li>
 *   <li><strong>Block scalars</strong> ({@code key: |} or {@code key: >}) – all content lines
 *       collected as a single unit until the next key at the same (or lower) indentation.</li>
 * </ul>
 *
 * <p>Inline YAML comments ({@code value # note}) and surrounding quotes are stripped from
 * values before they are delivered.
 */
public class YamlParser {

    public interface IHandler {
        /** A {@code # comment} line, stripped of the leading {@code #}. */
        void onLineComment(int lineNumber, String commentText);

        /** The value portion of a {@code key: value} pair (key stripped, quotes/inline-comment stripped). */
        void onValue(int lineNumber, String value);

        /** The value portion of a {@code - value} list item. */
        void onListItem(int lineNumber, String value);

        /** All content lines collected from a block scalar ({@code |} or {@code >}). */
        void onBlockScalar(int startLine, List<String> lines);
    }

    /** Regex that matches YAML block-scalar indicators: |, >, |- , >+, |2, etc. */
    private static final java.util.regex.Pattern BLOCK_SCALAR =
            java.util.regex.Pattern.compile("[|>][+\\-]?\\d*");

    public static void parse(List<String> lines, IHandler handler) {
        boolean inBlockScalar = false;
        List<String> blockLines = new ArrayList<>();
        int blockStart = -1;
        int blockIndent = -1;

        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String line = lines.get(i);
            String trimmed = line.strip();

            // ── Inside a block scalar ────────────────────────────────────────
            if (inBlockScalar) {
                if (trimmed.isEmpty()) continue; // blank lines inside scalar are skipped
                int indent = leadingSpaces(line);
                if (indent > blockIndent) {
                    blockLines.add(trimmed); // collect, stripping indentation
                    continue;
                }
                // Current line has same/lower indent → block scalar ended
                handler.onBlockScalar(blockStart, List.copyOf(blockLines));
                blockLines.clear();
                inBlockScalar = false;
                // fall through: process current line normally
            }

            // ── Normal line ──────────────────────────────────────────────────
            if (trimmed.isEmpty() || trimmed.equals("---") || trimmed.equals("...")) continue;

            // Line comment
            if (trimmed.startsWith("#")) {
                handler.onLineComment(lineNumber, trimmed.substring(1).strip());
                continue;
            }

            // List item: "- value"
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                if (trimmed.length() > 2) {
                    String value = clean(trimmed.substring(2).strip());
                    if (!value.isEmpty()) handler.onListItem(lineNumber, value);
                }
                continue;
            }

            // Key-value pair: "key: value"
            int colonSpace = trimmed.indexOf(": ");
            if (colonSpace >= 0) {
                String value = trimmed.substring(colonSpace + 2).strip();
                if (BLOCK_SCALAR.matcher(value).matches()) {
                    // Start of a block scalar
                    inBlockScalar = true;
                    blockStart = lineNumber + 1;
                    blockIndent = leadingSpaces(line);
                } else if (!value.isEmpty()) {
                    handler.onValue(lineNumber, clean(value));
                }
                continue;
            }
            // Pure key line ("key:") — no value, skip
        }

        // Flush a block scalar that reaches end of file
        if (inBlockScalar && !blockLines.isEmpty()) {
            handler.onBlockScalar(blockStart, List.copyOf(blockLines));
        }
    }

    /** Strip inline YAML comment and surrounding quotes from a scalar value. */
    static String clean(String value) {
        // Strip inline comment (space + #)
        int commentIdx = value.indexOf(" #");
        if (commentIdx >= 0) value = value.substring(0, commentIdx).strip();
        // Strip surrounding double or single quotes
        if (value.length() >= 2) {
            char f = value.charAt(0), l = value.charAt(value.length() - 1);
            if ((f == '"' && l == '"') || (f == '\'' && l == '\'')) {
                value = value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }
}
