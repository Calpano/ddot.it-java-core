package it.ddot.core.hostreader.asciidoc.doc;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders an {@link AdocDocument} to HTML following AsciiDoc conventions.
 * <p>
 * Produces HTML compatible with Asciidoctor's default HTML5 backend output, using the same CSS class names
 * ({@code paragraph}, {@code listingblock}, {@code literalblock}, {@code sidebarblock}, etc.).
 */
public class AdocRenderer {

    private static final Pattern ANCHOR_PATTERN = Pattern.compile("\\[\\[(.*?)]]");
    private static final Pattern ATTR_PATTERN = Pattern.compile("\\[([^\\[].*?)]");

    // Inline formatting patterns
    private static final Pattern BOLD_PATTERN = Pattern.compile("(?<=^|\\W)\\*(\\S.*?\\S|\\S)\\*(?=$|\\W)");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<=^|\\W)_(\\S.*?\\S|\\S)_(?=$|\\W)");
    private static final Pattern MONO_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern XREF_WITH_LABEL_PATTERN = Pattern.compile("<<([^,>]+),([^>]+)>>");
    private static final Pattern XREF_PATTERN = Pattern.compile("<<([^>]+)>>");

    /** Appends a pending block title and returns null to clear it. */
    private static @Nullable String appendTitle(StringBuilder sb, @Nullable String pendingTitle) {
        if (pendingTitle != null) {
            sb.append("<div class=\"title\">").append(escapeHtml(pendingTitle)).append("</div>");
        }
        return null;
    }

    private static String escapeAttr(String text) {
        return escapeHtml(text);
    }

    static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Extracts the anchor id from a {@code [[id]]} parameter fragment. */
    public static @Nullable String extractId(@Nullable String parameters) {
        if (parameters == null) return null;
        Matcher m = ANCHOR_PATTERN.matcher(parameters);
        if (m.find()) return m.group(1);
        return null;
    }

    /** Extracts the language from parameters like {@code [source,java]} or {@code [plantuml]}. */
    static @Nullable String extractLanguage(@Nullable String parameters) {
        if (parameters == null) return null;
        Matcher m = ATTR_PATTERN.matcher(parameters);
        if (!m.find()) return null;
        String attrs = m.group(1);
        // [source,java] → java
        if (attrs.startsWith("source,")) return attrs.substring(7).trim();
        // [plantuml] or other single-word attribute → treat as language/type
        if (!attrs.contains("=") && !attrs.contains(",") && !attrs.startsWith("#")) {
            return attrs.trim();
        }
        return null;
    }

    /** Applies AsciiDoc inline formatting: bold, italic, monospace, and cross-references. */
    static String inlineFormat(String text) {
        String result = escapeHtml(text);
        // Cross-references (must be before other replacements since they use << >>)
        result = XREF_WITH_LABEL_PATTERN.matcher(result).replaceAll("<a href=\"#$1\">$2</a>");
        result = XREF_PATTERN.matcher(result).replaceAll("<a href=\"#$1\">$1</a>");
        // Bold *text*
        result = BOLD_PATTERN.matcher(result).replaceAll("<strong>$1</strong>");
        // Italic _text_
        result = ITALIC_PATTERN.matcher(result).replaceAll("<em>$1</em>");
        // Monospace `text`
        result = MONO_PATTERN.matcher(result).replaceAll("<code>$1</code>");
        return result;
    }

    /**
     * Parses AsciiDoc header lines ({@code :key: value}) into a map.
     */
    public static Map<String, String> parseHeaders(List<String> headers) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (String header : headers) {
            // Match :key: value  or  :key:  (flag-style, no value)
            if (header.startsWith(":") && header.length() > 1) {
                int secondColon = header.indexOf(':', 1);
                if (secondColon > 1) {
                    String key = header.substring(1, secondColon);
                    String value = header.substring(secondColon + 1).trim();
                    attrs.put(key, value);
                }
            }
        }
        return attrs;
    }

    /**
     * Renders a single block into the given StringBuilder, applying attribute substitution. Returns the updated
     * pendingTitle (null after it has been consumed).
     */
    private static @Nullable String renderBlock(StringBuilder sb, AdocDocument.Block block, @Nullable String pendingTitle, Map<String, String> attrs) {
        switch (block.kind) {
            case H1, H2, H3, H4, H5, H6 -> {
                int level = AdocDocument.Kind.hLevel(block);
                String id = extractId(block.parameters);
                sb.append("<h").append(level);
                if (id != null) sb.append(" id=\"").append(escapeAttr(id)).append("\"");
                sb.append(">");
                sb.append(inlineFormat(substituteAttributes(String.join(" ", block.lines), attrs)));
                sb.append("</h").append(level).append(">\n");
                pendingTitle = null;
            }
            case Plain -> {
                sb.append("<div class=\"paragraph\">");
                if (pendingTitle != null) {
                    sb.append("<div class=\"title\">").append(escapeHtml(substituteAttributes(pendingTitle, attrs))).append("</div>");
                    pendingTitle = null;
                }
                sb.append("<p>").append(inlineFormat(substituteAttributes(String.join("\n", block.lines), attrs)));
                sb.append("</p></div>\n");
            }
            case Dash4 -> {
                String lang = extractLanguage(block.parameters);
                sb.append("<div class=\"listingblock\">");
                pendingTitle = appendTitle(sb, pendingTitle);
                sb.append("<div class=\"content\"><pre class=\"highlight\"><code");
                if (lang != null) {
                    sb.append(" class=\"language-").append(escapeAttr(lang)).append("\"");
                    sb.append(" data-lang=\"").append(escapeAttr(lang)).append("\"");
                }
                sb.append(">");
                sb.append(escapeHtml(substituteAttributes(String.join("\n", block.lines), attrs)));
                sb.append("</code></pre></div></div>\n");
            }
            case Dot4 -> {
                sb.append("<div class=\"literalblock\">");
                pendingTitle = appendTitle(sb, pendingTitle);
                sb.append("<div class=\"content\"><pre>");
                sb.append(escapeHtml(substituteAttributes(String.join("\n", block.lines), attrs)));
                sb.append("</pre></div></div>\n");
            }
            case Plus4 -> {
                if (pendingTitle != null) {
                    sb.append("<div class=\"title\">").append(escapeHtml(substituteAttributes(pendingTitle, attrs))).append("</div>\n");
                    pendingTitle = null;
                }
                sb.append(substituteAttributes(String.join("\n", block.lines), attrs)).append("\n");
            }
            case Star4 -> {
                sb.append("<div class=\"sidebarblock\">");
                pendingTitle = appendTitle(sb, pendingTitle);
                sb.append("<div class=\"content\"><p>");
                sb.append(inlineFormat(substituteAttributes(String.join("\n", block.lines), attrs)));
                sb.append("</p></div></div>\n");
            }
            case SectionHeadline -> // When rendering a single block, emit as a standalone title div
                    sb.append("<div class=\"title\">").append(escapeHtml(substituteAttributes(String.join("", block.lines), attrs))).append("</div>\n");
            case Slash4, LineComment -> // Comments — not rendered
                    pendingTitle = null;
        }
        return pendingTitle;
    }

    /**
     * Replaces AsciiDoc attribute references ({@code {key}}) with their values.
     */
    static String substituteAttributes(String text, Map<String, String> attrs) {
        if (attrs.isEmpty()) return text;
        String result = text;
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * Renders a single block to HTML, using doc headers for attribute substitution. SectionHeadline blocks are rendered
     * as a standalone {@code <div class="title">}.
     */
    public static String toHtml(AdocDocument doc, AdocDocument.Block block) {
        Map<String, String> attrs = parseHeaders(doc.headers);
        StringBuilder sb = new StringBuilder();
        renderBlock(sb, block, null, attrs);
        return sb.toString();
    }

    public static String toHtml(AdocDocument doc) {
        Map<String, String> attrs = parseHeaders(doc.headers);
        StringBuilder sb = new StringBuilder();

        // Document title
        if (doc.title != null) {
            sb.append("<h1>").append(escapeHtml(substituteAttributes(doc.title, attrs))).append("</h1>\n");
        }

        // Render content blocks
        String pendingTitle = null;
        for (AdocDocument.Block block : doc.contents) {
            if (block.kind == AdocDocument.Kind.SectionHeadline) {
                pendingTitle = String.join("", block.lines);
                continue;
            }
            if (block.kind == AdocDocument.Kind.Slash4 || block.kind == AdocDocument.Kind.LineComment) {
                pendingTitle = null;
                continue;
            }
            pendingTitle = renderBlock(sb, block, pendingTitle, attrs);
        }

        return sb.toString();
    }

}
