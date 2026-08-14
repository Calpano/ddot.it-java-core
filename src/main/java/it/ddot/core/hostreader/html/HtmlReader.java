package it.ddot.core.hostreader.html;

import it.ddot.core.hostreader.AbstractTextFileSourceReader;
import it.ddot.core.hostreader.structuredtext.ListMarker;
import it.ddot.core.io.handler.StructuredSource2TripleEvents;
import it.ddot.core.DdotSourceFormats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HtmlReader extends AbstractTextFileSourceReader {

    static class StructureHandler implements HtmlParser.IHandler {

        private final StructuredSource2TripleEvents handler;

        StructureHandler(StructuredSource2TripleEvents handler) {this.handler = handler;}

        @Override
        public void onCustomElement(int startLine, String subject, Map<String, String> attributes, List<String> contentLines) {
            handler.blockStart();
            // Each non-subject attribute becomes "subject .. key .. value".
            for (Map.Entry<String, String> e : attributes.entrySet()) {
                String key = e.getKey();
                // subject providers, plus data-ddot which is emitted independently via onDataAttribute
                if (key.equals("subject") || key.equals("id") || key.equals("name") || key.equals("data-ddot"))
                    continue;
                handler.emitTriple(subject, key, e.getValue(), startLine);
            }
            // The element's inner markup becomes "subject .. has content .. ...".
            if (!contentLines.isEmpty()) {
                handler.emitTriple(subject, "has content", String.join("\n", contentLines), startLine);
            }
            handler.blockEnd();
        }

        @Override
        public void onDataAttribute(int lineNumber, String value) {
            // The whole attribute value is ddot.it content; HTML forces it onto one line, possibly chaining
            // several triples (each with optional ,, meta). Slice it back into per-triple ddot.it lines —
            // keeping each triple's meta attached — and let onLine parse triple + meta as usual.
            handler.blockStart();
            for (String line : splitDataDdotLines(value)) {
                handler.onLine(line, lineNumber);
            }
            handler.blockEnd();
        }

        @Override
        public void onHtmlComment(int startLine, List<String> lines) {
            handler.blockStart();
            for (int i = 0; i < lines.size(); i++) {
                forwardProse(startLine + i, lines.get(i));
            }
            handler.blockEnd();
        }

        @Override
        public void onTextBlock(int startLine, List<String> lines) {
            handler.blockStart();
            for (int i = 0; i < lines.size(); i++) {
                forwardProse(startLine + i, lines.get(i));
            }
            handler.blockEnd();
        }

        /**
         * Forwards a prose line to the ddot grammar, stripping a leading Markdown-style list bullet (HTML is a markdown
         * subset) so it does not leak into the subject. The mapping stays at the reader default ({@code synthesized})
         * so write-back still skips HTML.
         */
        private void forwardProse(int lineNumber, String line) {
            ListMarker.Split li =
                    ListMarker.markdown(line);
            handler.onLine(li != null ? li.content() : line, lineNumber);
        }

    }

    // Splitter states: a ddot.it line is "[subject] .. P .. O [ ,, meta ]"; chained on one line they repeat.
    private static final int SUBJECT = 0, PRED = 1, OBJ = 2, META_START = 3, META_KEY = 4, META_VAL = 5, META_PLAIN = 6;

    public HtmlReader() {
        super(DdotSourceFormats.HTML);
    }

    /**
     * Slice a single-line {@code data-ddot} value into per-triple ddot.it lines, preserving each triple's
     * {@code ,, meta}. Example: {@code S ..P1.. O1 ,, ..m.. v ..P2.. O2} →
     * {@code ["S ..P1.. O1 ,, ..m.. v", "..P2.. O2"]}. A new triple boundary is a {@code ..} that opens a predicate
     * after a previous triple's object (or that triple's meta), as opposed to the {@code ..} that separates
     * predicate/object or meta key/value. Values without a triple are returned unchanged.
     */
    static List<String> splitDataDdotLines(String value) {
        List<Integer> starts = new ArrayList<>(); // index of each continuation triple's opening ".."
        int state = SUBJECT;
        int n = value.length();
        for (int i = 0; i < n; ) {
            boolean dotDot = i + 1 < n && value.charAt(i) == '.' && value.charAt(i + 1) == '.';
            boolean commaComma = i + 1 < n && value.charAt(i) == ',' && value.charAt(i + 1) == ',';
            if (dotDot) {
                switch (state) {
                    case SUBJECT -> state = PRED;                 // first predicate opener
                    case PRED -> state = OBJ;                     // predicate/object separator
                    case OBJ -> {
                        starts.add(i);
                        state = PRED;
                    }  // new triple boundary
                    case META_START -> state = META_KEY;          // meta key opener
                    case META_KEY -> state = META_VAL;            // meta key/value separator
                    case META_VAL, META_PLAIN -> {
                        starts.add(i);
                        state = PRED;
                    } // new triple after meta
                    default -> {
                    }
                }
                i += 2;
            } else if (commaComma) {
                if (state == OBJ) state = META_START;             // object's meta begins
                i += 2;
            } else {
                if (state == META_START && !Character.isWhitespace(value.charAt(i))) state = META_PLAIN;
                i++;
            }
        }
        if (starts.isEmpty()) return List.of(value.strip());
        List<String> lines = new ArrayList<>();
        int prev = 0;
        for (int s : starts) {
            lines.add(value.substring(prev, s).strip());
            prev = s;
        }
        lines.add(value.substring(prev).strip());
        return lines;
    }

    @Override
    public void readTo(List<String> lines, StructuredSource2TripleEvents ss2te) {
        HtmlParser.parse(lines, new StructureHandler(ss2te));
    }


}
