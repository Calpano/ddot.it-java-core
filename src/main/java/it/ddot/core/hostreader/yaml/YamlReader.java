package it.ddot.core.hostreader.yaml;

import it.ddot.core.hostreader.AbstractTextFileSourceReader;
import it.ddot.core.io.handler.StructuredSource2TripleEvents;
import it.ddot.core.DdotSourceFormats;

import java.util.List;

@SuppressWarnings("unused")
public class YamlReader extends AbstractTextFileSourceReader {

    static class StructureHandler implements YamlParser.IHandler {

        private final StructuredSource2TripleEvents handler;

        StructureHandler(StructuredSource2TripleEvents handler) {this.handler = handler;}

        @Override
        public void onBlockScalar(int startLine, List<String> lines) {
            handler.blockStart();
            for (int i = 0; i < lines.size(); i++) {
                handler.onLine(lines.get(i), startLine + i);
            }
            handler.blockEnd();
        }

        @Override
        public void onLineComment(int lineNumber, String commentText) {
            handler.blockStart();
            handler.onLine(commentText, lineNumber);
            handler.blockEnd();
        }

        @Override
        public void onListItem(int lineNumber, String value) {
            handler.onLine(value, lineNumber);
        }

        @Override
        public void onValue(int lineNumber, String value) {
            handler.onLine(value, lineNumber);
        }

    }

    public YamlReader() {
        super(DdotSourceFormats.YAML);
    }

    @Override
    public void readTo(List<String> lines, StructuredSource2TripleEvents ss2te) {
        // YAML triples come from parsed keys/values, not verbatim source lines → skip on write-back.
        YamlParser.parse(lines, new StructureHandler(ss2te));
    }

}
