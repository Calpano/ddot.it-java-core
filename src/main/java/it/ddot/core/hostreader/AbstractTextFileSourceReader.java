package it.ddot.core.hostreader;

import it.ddot.core.io.handler.StructuredSource2TripleEvents;
import it.ddot.core.source.ISnippet;
import it.ddot.core.source.ISourceFormat;
import it.ddot.core.io.ISourceHandler;
import it.ddot.core.source.ISourceLocation;
import it.ddot.core.source.ISourceResourceAddress;

import java.io.IOException;
import java.util.List;

public abstract class AbstractTextFileSourceReader extends AbstractFileSourceReader {

    public AbstractTextFileSourceReader(ISourceFormat sourceFormat) {
        super(sourceFormat);
    }

    public abstract void readTo(List<String> lines, StructuredSource2TripleEvents ss2te);

    @Override
    public void readTo(ISourceResourceAddress sourceResourceAddress, ISourceHandler sourceHandler) throws IOException {
        assert sourceResourceAddress.isFile();
        StructuredSource2TripleEvents ss2te = new StructuredSource2TripleEvents(sourceHandler);
        ss2te.sourceStart();
        ISnippet snippet = ISnippet.of(sourceKind().sourceFormatId(), ISourceLocation.of(sourceResourceAddress, 1));
        ss2te.sourceResourceStart(snippet);

        // HTML triples are extracted from structure (comments, attributes, element text), never verbatim source
        // lines → not writable in place; mark synthesized so write-back safely skips them.
        AbstractFileSourceReader.readLines(toFile(snippet.sourceLocation().sourceResource()), lines -> {
            readTo(lines, ss2te);
        });
        ss2te.sourceResourceEnd();
        ss2te.sourceEnd();
    }


}
