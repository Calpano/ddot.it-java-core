package it.ddot.core.hostreader.markdown;

import it.ddot.core.hostreader.structuredtext.ListMarker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts ddot.it-relevant content from a Markdown source.
 * Recognizes plain text, HTML comments, fenced code blocks, and section attributes.
 *
 * TODO make this technically extending the {@link it.ddot.core.hostreader.html.HtmlParser}
 */
public class MarkdownParser {

    public interface IHandler {

        /** A regular text line. */
        void onPlainLine(int lineNumber, String line);

        /**
         * A list item ({@code - }, {@code * }, {@code + } or {@code 1. }), split into the host {@code marker}
         * (indentation + bullet + spaces) and the residual {@code content} handed to the ddot grammar. The marker
         * must be stripped before the triple parser (else it leaks into the subject) and re-applied on write-back
         * ({@code sourceLine == marker + content}). Defaults to treating the whole line as plain.
         */
        default void onListItem(int lineNumber, String content, String marker) {
            onPlainLine(lineNumber, marker + content);
        }

        /** Lines inside an HTML comment block, stripped of delimiters. */
        void onHtmlComment(int startLine, List<String> lines);

        /**
         * Multi-line HTML comment. {@code delimitersOnOwnLines} is true when both {@code <!--} and {@code -->}
         * are alone on their lines, so every content line is a verbatim source line (write-back can rewrite them
         * in place); false when a delimiter shares a line with content (first/last line only partly the triple).
         * Defaults to the delimiter-agnostic {@link #onHtmlComment(int, List)}.
         */
        default void onHtmlComment(int startLine, List<String> lines, boolean delimitersOnOwnLines) {
            onHtmlComment(startLine, lines);
        }

        /**
         * A single-line HTML comment {@code <!-- inner -->}. Unlike {@link #onHtmlComment}, the exact host
         * wrapper is supplied ({@code prefix}/{@code suffix}, whitespace preserved) so write-back can rewrite the
         * inner triple and re-wrap it: {@code sourceLine == prefix + inner + suffix}. Defaults to treating it as a
         * generic comment block.
         */
        default void onInlineComment(int lineNumber, String inner, String prefix, String suffix) {
            onHtmlComment(lineNumber, List.of(inner));
        }

        /** Lines inside a fenced code block (``` or ~~~). */
        void onCodeBlock(int startLine, List<String> lines);

        /**
         * A section heading that carries custom attributes.
         * E.g. {@code ## My Section {#id .class}} → title="My Section", attrs="#id .class"
         */
        void onSectionAttributes(int lineNumber, String sectionTitle, String attrs);
    }

    // ## Title {attrs}
    private static final Pattern SECTION_ATTRS = Pattern.compile("^#+\\s+(.*?)\\s+\\{([^}]+)}\\s*$");
    // opening fence: 3+ backticks or tildes
    private static final Pattern FENCE_OPEN = Pattern.compile("^(`{3,}|~{3,})");

    public static void parse(List<String> lines, IHandler handler) {
        boolean inCodeBlock = false;
        boolean inHtmlComment = false;
        boolean htmlCommentTailPresent = false; // content shared the <!-- line → first line not verbatim
        char fenceChar = 0;
        List<String> blockLines = new ArrayList<>();
        int blockStart = -1;

        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String line = lines.get(i);

            if (inCodeBlock) {
                Matcher m = FENCE_OPEN.matcher(line.trim());
                if (m.find() && m.group(1).charAt(0) == fenceChar) {
                    handler.onCodeBlock(blockStart, List.copyOf(blockLines));
                    blockLines.clear();
                    inCodeBlock = false;
                } else {
                    blockLines.add(line);
                }
                continue;
            }

            if (inHtmlComment) {
                int end = line.indexOf("-->");
                if (end >= 0) {
                    String before = line.substring(0, end);
                    boolean beforePresent = !before.isBlank();
                    if (beforePresent) blockLines.add(before);
                    // "clean" = both delimiters alone on their lines → every content line is a verbatim source line.
                    boolean delimitersOnOwnLines = !htmlCommentTailPresent && !beforePresent;
                    handler.onHtmlComment(blockStart, List.copyOf(blockLines), delimitersOnOwnLines);
                    blockLines.clear();
                    inHtmlComment = false;
                } else {
                    blockLines.add(line);
                }
                continue;
            }

            // HTML comment
            int commentStart = line.indexOf("<!--");
            if (commentStart >= 0) {
                int commentEnd = line.indexOf("-->", commentStart + 4);
                if (commentEnd >= 0) {
                    // single-line comment: pass the exact wrapper so write-back can re-wrap the rewritten triple.
                    String raw = line.substring(commentStart + 4, commentEnd); // between <!-- and -->
                    int ls = 0;
                    while (ls < raw.length() && Character.isWhitespace(raw.charAt(ls))) ls++;
                    int te = raw.length();
                    while (te > ls && Character.isWhitespace(raw.charAt(te - 1))) te--;
                    String inner = raw.substring(ls, te);
                    String prefix = line.substring(0, commentStart) + "<!--" + raw.substring(0, ls);
                    String suffix = raw.substring(te) + "-->" + line.substring(commentEnd + 3);
                    handler.onInlineComment(lineNumber, inner, prefix, suffix);
                } else {
                    inHtmlComment = true;
                    String tail = line.substring(commentStart + 4);
                    htmlCommentTailPresent = !tail.isBlank();
                    // blockStart = the source line of the FIRST accumulated content line, so onLine(startLine+i)
                    // reports correct absolute line numbers. Tail (content after <!--) is on this line; otherwise
                    // the first content line is the next one.
                    blockStart = htmlCommentTailPresent ? lineNumber : lineNumber + 1;
                    if (htmlCommentTailPresent) blockLines.add(tail);
                }
                continue;
            }

            // Fenced code block
            Matcher fenceMatcher = FENCE_OPEN.matcher(line.trim());
            if (fenceMatcher.find()) {
                inCodeBlock = true;
                fenceChar = fenceMatcher.group(1).charAt(0);
                blockStart = lineNumber + 1; // content starts on the line after the opening fence
                blockLines.clear();
                continue;
            }

            // Section with attributes: ## Title {#id .class}
            Matcher attrsMatcher = SECTION_ATTRS.matcher(line);
            if (attrsMatcher.matches()) {
                handler.onSectionAttributes(lineNumber, attrsMatcher.group(1).strip(), attrsMatcher.group(2).strip());
                continue;
            }

            // List item: strip the bullet before the triple grammar; keep it for write-back.
            ListMarker.Split li =
                    ListMarker.markdown(line);
            if (li != null) {
                handler.onListItem(lineNumber, li.content(), li.marker());
                continue;
            }

            handler.onPlainLine(lineNumber, line);
        }
    }
}
