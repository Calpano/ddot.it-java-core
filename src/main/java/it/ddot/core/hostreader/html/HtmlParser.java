package it.ddot.core.hostreader.html;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts ddot.it-relevant content from HTML source.
 * Recognizes HTML comment blocks, text content inside block-level elements, and the
 * {@code <ddot-it>} custom element (since ddot.it 1.1).
 */
public class HtmlParser {

    public interface IHandler {
        /** Lines inside an HTML comment block, stripped of delimiters. */
        void onHtmlComment(int startLine, List<String> lines);

        /** Lines of text content from inside a block element (e.g. {@code <p>}). */
        void onTextBlock(int startLine, List<String> lines);

        /**
         * The raw value of a {@code data-ddot} attribute found on any element. Because HTML attributes cannot
         * span lines, the value is a single line that may chain several triples
         * ({@code S ..P1.. O1 ..P2.. O2}); the whole value is known to be ddot.it content.
         */
        void onDataAttribute(int lineNumber, String value);

        /**
         * A {@code <ddot-it>} custom element (ddot.it 1.1). The {@code subject} is resolved from the
         * {@code subject}/{@code id}/{@code name} attribute (in that order). Every other attribute becomes a
         * {@code subject .. key .. value} triple; the element's raw inner markup (newlines preserved) becomes a
         * {@code subject .. has content .. ...} triple.
         *
         * @param attributes   insertion-ordered attribute map, including the subject-providing attribute
         * @param contentLines raw inner content lines (markup preserved), to be joined with {@code \n}
         */
        void onCustomElement(int startLine, String subject, Map<String, String> attributes, List<String> contentLines);
    }

    private static final java.util.Set<String> TEXT_BLOCK_TAGS =
            java.util.Set.of("p", "li", "td", "th", "div", "h1", "h2", "h3", "h4", "h5", "h6");

    /** Custom element name carrying ddot.it content. */
    private static final String CUSTOM_TAG = "ddot-it";

    /** Matches a single {@code name='value'} or {@code name="value"} attribute. */
    private static final Pattern ATTR = Pattern.compile("([\\w][\\w:.-]*)\\s*=\\s*(?:'([^']*)'|\"([^\"]*)\")");

    public static void parse(List<String> lines, IHandler handler) {
        boolean inComment = false;
        boolean inElement = false;
        // <ddot-it> collection state
        boolean inCustom = false;
        boolean inCustomIgnore = false; // <ddot-it> without a subject: skip until close
        String customSubject = null;
        LinkedHashMap<String, String> customAttrs = null;
        List<String> customLines = null;
        int customStart = -1;

        List<String> blockLines = new ArrayList<>();
        int blockStart = -1;

        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String trimmed = lines.get(i).strip();

            if (inComment) {
                int end = trimmed.indexOf("-->");
                if (end >= 0) {
                    String before = trimmed.substring(0, end).strip();
                    if (!before.isEmpty()) blockLines.add(before);
                    handler.onHtmlComment(blockStart, List.copyOf(blockLines));
                    blockLines.clear();
                    inComment = false;
                } else if (!trimmed.isEmpty()) {
                    blockLines.add(trimmed);
                }
                continue;
            }

            // Collecting raw content of a <ddot-it> element until </ddot-it>.
            if (inCustom) {
                int end = indexOfIgnoreCase(trimmed, "</" + CUSTOM_TAG + ">");
                if (end >= 0) {
                    String before = trimmed.substring(0, end).strip();
                    if (!before.isEmpty()) customLines.add(before);
                    handler.onCustomElement(customStart, customSubject, customAttrs, List.copyOf(customLines));
                    inCustom = false;
                } else {
                    customLines.add(trimmed); // keep raw markup, including blank lines
                }
                continue;
            }

            // Inside a subject-less <ddot-it>: ignore everything until close.
            if (inCustomIgnore) {
                if (indexOfIgnoreCase(trimmed, "</" + CUSTOM_TAG + ">") >= 0) inCustomIgnore = false;
                continue;
            }

            if (inElement) {
                int closeTagIdx = trimmed.indexOf("</");
                if (closeTagIdx >= 0) {
                    String before = trimmed.substring(0, closeTagIdx).strip();
                    if (!before.isEmpty()) blockLines.add(before);
                    handler.onTextBlock(blockStart, List.copyOf(blockLines));
                    blockLines.clear();
                    inElement = false;
                } else if (!trimmed.isEmpty()) {
                    blockLines.add(trimmed);
                }
                continue;
            }

            if (trimmed.isEmpty()) continue;

            // HTML comment
            if (trimmed.startsWith("<!--")) {
                int end = trimmed.indexOf("-->", 4);
                if (end >= 0) {
                    String content = trimmed.substring(4, end).strip();
                    handler.onHtmlComment(lineNumber, List.of(content));
                } else {
                    inComment = true;
                    blockStart = lineNumber;
                    String tail = trimmed.substring(4).strip();
                    if (!tail.isEmpty()) blockLines.add(tail);
                }
                continue;
            }

            // <ddot-it> custom element (ddot.it 1.1)
            if (isOpenTagNamed(trimmed, CUSTOM_TAG)) {
                int gt = trimmed.indexOf('>');
                if (gt < 0) continue; // opening tag spanning multiple lines is not supported
                String inner = trimmed.substring(1, gt).strip();
                boolean selfClose = inner.endsWith("/");
                if (selfClose) inner = inner.substring(0, inner.length() - 1).strip();
                LinkedHashMap<String, String> attrs = parseAttributes(inner);
                // data-ddot on a <ddot-it> element is handled independently of the element's own triples.
                String dataDdot = attrs.get("data-ddot");
                if (dataDdot != null) handler.onDataAttribute(lineNumber, dataDdot);
                String subject = resolveSubject(attrs);
                String afterTag = trimmed.substring(gt + 1);
                int close = indexOfIgnoreCase(afterTag, "</" + CUSTOM_TAG + ">");

                if (subject == null) {
                    // No subject → the element (and its content) is ignored.
                    if (!selfClose && close < 0) inCustomIgnore = true;
                    continue;
                }

                if (selfClose) {
                    handler.onCustomElement(lineNumber, subject, attrs, List.of());
                } else if (close >= 0) {
                    // Inline: <ddot-it ...>content</ddot-it>
                    String content = afterTag.substring(0, close).strip();
                    handler.onCustomElement(lineNumber, subject, attrs,
                            content.isEmpty() ? List.of() : List.of(content));
                } else {
                    // Multi-line: collect following lines until </ddot-it>
                    inCustom = true;
                    customSubject = subject;
                    customAttrs = attrs;
                    customLines = new ArrayList<>();
                    customStart = lineNumber;
                    String tail = afterTag.strip();
                    if (!tail.isEmpty()) customLines.add(tail);
                }
                continue;
            }

            // Opening HTML element (not closing tag, not DOCTYPE/comment)
            if (trimmed.startsWith("<") && !trimmed.startsWith("</") && !trimmed.startsWith("<!")) {
                int closeTag = trimmed.indexOf('>');
                if (closeTag < 0) continue;
                // Any element may carry ddot.it triples in a data-ddot attribute.
                String dataDdot = dataDdotValue(trimmed.substring(1, closeTag));
                if (dataDdot != null) handler.onDataAttribute(lineNumber, dataDdot);
                String afterTag = trimmed.substring(closeTag + 1);
                int closeElem = afterTag.indexOf("</");
                if (closeElem >= 0) {
                    // Inline: <p>content</p>
                    String content = afterTag.substring(0, closeElem).strip();
                    if (!content.isEmpty()) handler.onTextBlock(lineNumber, List.of(content));
                } else if (!afterTag.isBlank()) {
                    // Opening tag with trailing text (no close on this line)
                    inElement = true;
                    blockStart = lineNumber;
                    blockLines.add(afterTag.strip());
                } else {
                    // Standalone opening tag — start collecting if it's a text-bearing element
                    String tagName = trimmed.substring(1, closeTag).trim().split("\\s+")[0].toLowerCase();
                    if (TEXT_BLOCK_TAGS.contains(tagName)) {
                        inElement = true;
                        blockStart = lineNumber;
                    }
                }
            }
            // Closing tags, DOCTYPE, etc. are silently skipped
        }

        // Flush an unterminated <ddot-it> element at end of input.
        if (inCustom && customSubject != null) {
            handler.onCustomElement(customStart, customSubject, customAttrs, List.copyOf(customLines));
        }
    }

    /** True if {@code trimmed} opens an element whose tag name equals {@code tagName} (case-insensitive). */
    private static boolean isOpenTagNamed(String trimmed, String tagName) {
        if (!trimmed.startsWith("<")) return false;
        int i = 1, n = trimmed.length();
        while (i < n) {
            char c = trimmed.charAt(i);
            if (c == '>' || c == '/' || Character.isWhitespace(c)) break;
            i++;
        }
        return trimmed.substring(1, i).equalsIgnoreCase(tagName);
    }

    /** Resolve the subject from the {@code subject}, {@code id}, or {@code name} attribute, in that order. */
    private static @Nullable String resolveSubject(Map<String, String> attrs) {
        for (String key : new String[]{"subject", "id", "name"}) {
            String v = attrs.get(key);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /** Value of the {@code data-ddot} attribute within an opening tag's inner text, or null if absent. */
    private static @Nullable String dataDdotValue(String openTagInner) {
        Matcher m = ATTR.matcher(openTagInner);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase("data-ddot")) {
                return m.group(2) != null ? m.group(2) : m.group(3);
            }
        }
        return null;
    }

    private static LinkedHashMap<String, String> parseAttributes(String s) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        Matcher m = ATTR.matcher(s);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3);
            map.put(m.group(1), value);
        }
        return map;
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().indexOf(needle.toLowerCase());
    }
}
