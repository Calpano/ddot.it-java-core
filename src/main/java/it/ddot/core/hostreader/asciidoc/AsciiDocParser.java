package it.ddot.core.hostreader.asciidoc;

import it.ddot.core.hostreader.structuredtext.ListMarker;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts ddot.it-relevant blocks from AsciiDoc source.
 * Recognizes plain text, line comments, comment blocks (////), and source/listing blocks (----).
 */
public class AsciiDocParser {

    /**
     * Structures the parser can detect in the syntax
     */
    public interface IHandler {
        /** A regular text line. */
        void onPlainLine(int lineNumber, String line);

        /**
         * A list item ({@code * }, {@code - } or {@code . }), split into the host {@code marker} (indentation +
         * bullet + spaces) and the residual {@code content} handed to the ddot grammar. The marker is stripped
         * before the triple parser and re-applied on write-back ({@code sourceLine == marker + content}). Defaults
         * to treating the whole line as plain.
         */
        default void onListItem(int lineNumber, String content, String marker) {
            onPlainLine(lineNumber, marker + content);
        }

        /** A single-line comment (// text), stripped of the // prefix. */
        void onLineComment(int lineNumber, String commentText);

        /**
         * Wrapper-aware variant of {@link #onLineComment(int, String)}: the exact host wrapper is supplied so
         * write-back can rewrite the inner triple and re-wrap it ({@code sourceLine == prefix + inner + suffix}).
         * Defaults to the stripped form.
         */
        default void onLineComment(int lineNumber, String inner, String prefix, String suffix) {
            onLineComment(lineNumber, inner);
        }

        /** Lines inside a //// comment block, stripped of delimiters. */
        void onCommentBlock(int startLine, List<String> lines);

        /** Lines inside a ---- source/listing block. */
        void onSourceBlock(int startLine, List<String> lines);

        // TODO add onSection(int lineNumber, String sectionTitle) which sets the activeSubject

        // TODO parse section attributes
    }

    private static final String COMMENT_BLOCK_DELIM = "////";
    private static final String SOURCE_BLOCK_DELIM = "----";

    public static void parse(List<String> lines, IHandler handler) {
        boolean inCommentBlock = false;
        boolean inSourceBlock = false;
        List<String> blockLines = new ArrayList<>();
        int blockStart = -1;

        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String line = lines.get(i);
            String trimmed = line.strip();

            if (inCommentBlock) {
                if (trimmed.equals(COMMENT_BLOCK_DELIM)) {
                    handler.onCommentBlock(blockStart, List.copyOf(blockLines));
                    blockLines.clear();
                    inCommentBlock = false;
                } else {
                    blockLines.add(line);
                }
                continue;
            }

            if (inSourceBlock) {
                if (trimmed.equals(SOURCE_BLOCK_DELIM)) {
                    handler.onSourceBlock(blockStart, List.copyOf(blockLines));
                    blockLines.clear();
                    inSourceBlock = false;
                } else {
                    blockLines.add(line);
                }
                continue;
            }

            if (trimmed.equals(COMMENT_BLOCK_DELIM)) {
                inCommentBlock = true;
                blockStart = lineNumber + 1; // content starts on the line after the //// delimiter
                blockLines.clear();
                continue;
            }

            if (trimmed.equals(SOURCE_BLOCK_DELIM)) {
                inSourceBlock = true;
                blockStart = lineNumber + 1; // content starts on the line after the ---- delimiter
                blockLines.clear();
                continue;
            }

            // Line comment: starts with // but NOT ////
            if (trimmed.startsWith("//") && !trimmed.startsWith("////")) {
                // Pass the exact wrapper (indentation + // + spacing) so write-back can re-wrap the rewritten triple.
                int cs = line.indexOf("//");
                String raw = line.substring(cs + 2);
                int ls = 0;
                while (ls < raw.length() && Character.isWhitespace(raw.charAt(ls))) ls++;
                int te = raw.length();
                while (te > ls && Character.isWhitespace(raw.charAt(te - 1))) te--;
                String inner = raw.substring(ls, te);
                String prefix = line.substring(0, cs) + "//" + raw.substring(0, ls);
                String suffix = raw.substring(te);
                handler.onLineComment(lineNumber, inner, prefix, suffix);
                continue;
            }

            // List item: strip the bullet before the triple grammar; keep it for write-back.
            ListMarker.Split li =
                    ListMarker.asciidoc(line);
            if (li != null) {
                handler.onListItem(lineNumber, li.content(), li.marker());
                continue;
            }

            handler.onPlainLine(lineNumber, line);
        }
    }
}
