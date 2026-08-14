package it.ddot.core.hostreader.xml;

import it.ddot.core.hostreader.AbstractTextFileSourceReader;
import it.ddot.core.io.handler.StructuredSource2TripleEvents;
import it.ddot.core.DdotSourceFormats;

import java.util.List;

@SuppressWarnings("unused")
public class XmlReader extends AbstractTextFileSourceReader {

    static class StructureHandler implements XmlParser.IHandler {

        private final StructuredSource2TripleEvents handler;

        StructureHandler(StructuredSource2TripleEvents handler) {this.handler = handler;}

        @Override
        public void onTextLine(int lineNumber, String text) {
            handler.onLine(text, lineNumber);
        }

        @Override
        public void onXmlComment(int startLine, List<String> lines) {
            handler.blockStart();
            for (int i = 0; i < lines.size(); i++) {
                handler.onLine(lines.get(i), startLine + i);
            }
            handler.blockEnd();
        }

    }

    public XmlReader() {
        super(DdotSourceFormats.XML);
    }

    @Override
    public void readTo(List<String> lines, StructuredSource2TripleEvents ss2te) {
        // XML triples come from element text/attributes, not verbatim source lines → skip on write-back.
        XmlParser.parse(lines, new StructureHandler(ss2te));
    }

}
