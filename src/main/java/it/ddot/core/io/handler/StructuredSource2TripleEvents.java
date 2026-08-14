package it.ddot.core.io.handler;

import it.ddot.core.io.ISourceHandler;

public class StructuredSource2TripleEvents extends Source2TripleEvents {

    public StructuredSource2TripleEvents(ISourceHandler sourceHandler) {
        super(sourceHandler);
    }

    public void blockEnd() {
        resetSubject();
    }

    public void blockStart() {}

}
