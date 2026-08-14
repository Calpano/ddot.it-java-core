package it.ddot.core.hostreader.markdown;

import it.ddot.core.hostreader.AbstractTextFileSourceReader;
import it.ddot.core.io.handler.StructuredSource2TripleEvents;
import it.ddot.core.DdotSourceFormats;

import java.util.List;

public class MarkdownReader extends AbstractTextFileSourceReader {

    static class StructureHandler implements MarkdownParser.IHandler {

        private final StructuredSource2TripleEvents handler;

        StructureHandler(StructuredSource2TripleEvents handler) {this.handler = handler;}

        @Override
        public void onCodeBlock(int startLine, List<String> lines) {
            handler.blockStart();
            for (int i = 0; i < lines.size(); i++) {
                handler.onLine(lines.get(i), startLine + i);
            }
            handler.blockEnd();
        }

        @Override
        public void onHtmlComment(int startLine, List<String> lines) {
            // Abstract-method fallback (parser uses the delimiter-aware overload below); treat as non-writable.
            onHtmlComment(startLine, lines, false);
        }

        @Override
        public void onHtmlComment(int startLine, List<String> lines, boolean delimitersOnOwnLines) {
            handler.blockStart();
            // Clean comment (<!-- and --> alone on their lines): content lines are verbatim → writable in place.
            // Messy comment (a delimiter shares a line with content): first/last line is only partly the triple →
            // mark non-writable so write-back skips rather than corrupts. Single-line comments use onInlineComment.
            for (int i = 0; i < lines.size(); i++) {
                handler.onLine(lines.get(i), startLine + i);
            }
            handler.blockEnd();
        }

        @Override
        public void onInlineComment(int lineNumber, String inner, String prefix, String suffix) {
            // The wrapper is known, so the triple is writable: re-serialize inner and re-wrap as prefix+inner+suffix.
            handler.blockStart();
            handler.onLine(inner, lineNumber);
            handler.blockEnd();
        }

        @Override
        public void onListItem(int lineNumber, String content, String marker) {
            // Strip the bullet for the triple grammar; record it as a wrapper so write-back re-applies it. No
            // blockStart/blockEnd here — that would reset the subject and break continuation across list items.
            handler.onLine(content, lineNumber);
        }

        @Override
        public void onPlainLine(int lineNumber, String line) {
            handler.onLine(line, lineNumber);
        }

        @Override
        public void onSectionAttributes(int lineNumber, String sectionTitle, String attrs) {
            handler.blockStart();
            // Triples here are synthesized from the heading's {#id .class}; there is no writable triple line.
            boolean first = true;
            for (String token : attrs.split("\\s+")) {
                String type, value;
                if (token.startsWith("#")) {
                    type = "has tag";
                    value = token.substring(1);
                } else if (token.startsWith(".")) {
                    type = "has type";
                    value = token.substring(1);
                } else {
                    continue;
                }
                if (value.isEmpty()) continue;
                String synthesized = first
                        ? sectionTitle + " .. " + type + " .. " + value
                        : ".. " + type + " .. " + value;
                handler.onLine(synthesized, lineNumber);
                first = false;
            }
            handler.blockEnd();
        }

    }

    public MarkdownReader() {
        super(DdotSourceFormats.MARKDOWN);
    }

    @Override
    public void readTo(List<String> lines, StructuredSource2TripleEvents ss2te) {
        MarkdownParser.parse(lines, new StructureHandler(ss2te));
    }

}
