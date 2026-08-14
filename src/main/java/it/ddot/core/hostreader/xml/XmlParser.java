package it.ddot.core.hostreader.xml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extracts ddot.it-relevant content from XML source.
 *
 * <p>Recognises two content types:
 * <ul>
 *   <li><strong>XML comments</strong> ({@code <!-- ... -->}) – stripped of delimiters.</li>
 *   <li><strong>Text lines</strong> – any line that, after all XML tags are stripped,
 *       still contains non-whitespace characters.  This covers inline text like
 *       {@code <item>S .. P .. O</item>} as well as bare text in multi-line elements.</li>
 * </ul>
 *
 * <p>Processing instructions ({@code <?...?>}), DOCTYPE declarations, and CDATA sections
 * are silently skipped.  Attribute values are not scanned.
 */
public class XmlParser {

    public interface IHandler {
        /** Lines inside an XML comment block, stripped of {@code <!--} / {@code -->}. */
        void onXmlComment(int startLine, List<String> lines);

        /** A text line with all XML tags stripped. */
        void onTextLine(int lineNumber, String text);
    }

    /** Matches any XML/HTML tag including namespace-prefixed and self-closing tags. */
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    public static void parse(List<String> lines, IHandler handler) {
        boolean inComment = false;
        List<String> blockLines = new ArrayList<>();
        int blockStart = -1;

        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String trimmed = lines.get(i).strip();

            // ── Inside a multi-line comment ──────────────────────────────────
            if (inComment) {
                int end = trimmed.indexOf("-->");
                if (end >= 0) {
                    String before = trimmed.substring(0, end).strip();
                    if (!before.isEmpty()) blockLines.add(before);
                    handler.onXmlComment(blockStart, List.copyOf(blockLines));
                    blockLines.clear();
                    inComment = false;
                } else if (!trimmed.isEmpty()) {
                    blockLines.add(trimmed);
                }
                continue;
            }

            if (trimmed.isEmpty()) continue;

            // XML / HTML comment start
            if (trimmed.startsWith("<!--")) {
                int end = trimmed.indexOf("-->", 4);
                if (end >= 0) {
                    String content = trimmed.substring(4, end).strip();
                    handler.onXmlComment(lineNumber, List.of(content));
                } else {
                    inComment = true;
                    blockStart = lineNumber;
                    String tail = trimmed.substring(4).strip();
                    if (!tail.isEmpty()) blockLines.add(tail);
                }
                continue;
            }

            // Processing instruction or DOCTYPE — skip
            if (trimmed.startsWith("<?") || trimmed.startsWith("<!")) continue;

            // Strip all tags and deliver remaining text
            String text = TAG.matcher(trimmed).replaceAll("").strip();
            if (!text.isEmpty()) {
                handler.onTextLine(lineNumber, text);
            }
        }
    }
}
