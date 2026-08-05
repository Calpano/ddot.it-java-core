package com.calpano.ddot.it.core.source;

public interface ISourceLocation {

    static ISourceLocation of(ISourceResourceAddress sourceResource, int lineNumber) {
        return of(sourceResource, lineNumber, LineMapping.verbatim());
    }

    /** Single-line location whose write-back host decoration is {@code mapping}. */
    static ISourceLocation of(ISourceResourceAddress sourceResource, int lineNumber, LineMapping mapping) {
        return of(sourceResource, lineNumber, ISnippet.line(lineNumber, mapping));
    }

    /** Location backed by an explicit {@link ISnippet} (e.g. a multi-line block span). */
    static ISourceLocation of(ISourceResourceAddress sourceResource, int lineNumber, ISnippet snippet) {
        return new ISourceLocation() {
            public int lineNumber() {return lineNumber;}

            public ISourceResourceAddress sourceResource() {return sourceResource;}

            public ISnippet snippet() {return snippet;}
        };
    }

    /**
     * Line number in the source file. -1 is unknown. Lines without ddot.it syntax need not be sent.
     */
    int lineNumber();

    ISourceResourceAddress sourceResource();

    /**
     * The host source region this triple occupies, for write-back. Defaults to a verbatim single-line snippet at
     * {@link #lineNumber()} (inner line == source line), correct for {@code .ddot} and plain-line host triples.
     */
    default ISnippet snippet() {
        return ISnippet.verbatim(lineNumber());
    }

}
