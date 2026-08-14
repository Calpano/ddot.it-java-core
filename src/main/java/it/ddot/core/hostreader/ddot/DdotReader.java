package it.ddot.core.hostreader.ddot;

import it.ddot.core.hostreader.AbstractTextFileSourceReader;
import it.ddot.core.io.handler.StructuredSource2TripleEvents;
import it.ddot.core.DdotSourceFormats;

import java.util.List;

/**
 * Reader for native {@code .ddot} files.
 *
 * <p>A {@code .ddot} file is a plain-text stream of ddot.it triple lines, one {@code S .. P .. O} per line.
 * The whole file is treated as a single continuous source: the active subject carries over between lines (empty
 * {@code from} = continuation), and all ddot.it line commands are honoured — {@code ddot.it/on} / {@code ddot.it/off}
 * regions, {@code ddot.it/this} self-reference, {@code ,,} metadata suffixes, untyped {@code ....} links, and
 * {@code ddot.it/block} (a.k.a. {@code !!block}) multi-line object literals.
 *
 * <p>Unlike {@link it.ddot.core.hostreader.basic.DdotItBaseReader} (a strict test-only parser that
 * throws on blank or malformed lines), this reader is lenient: blank lines and non-triple lines are simply ignored,
 * matching the behaviour of the Markdown/AsciiDoc readers.
 */
public class DdotReader extends AbstractTextFileSourceReader {

    public DdotReader() {
        super(DdotSourceFormats.DDOT);
    }

    @Override
    public void readTo(List<String> lines, StructuredSource2TripleEvents ss2te) {
        for (int i = 0; i < lines.size(); i++) {
            ss2te.onLine(lines.get(i), i + 1);
        }
    }

}
