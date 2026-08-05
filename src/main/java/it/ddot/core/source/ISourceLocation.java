package it.ddot.core.source;

public interface ISourceLocation {

    /** Location backed by an explicit {@link ISnippet} (e.g. a multi-line block span). */
    static ISourceLocation of(ISourceResourceAddress sourceResource, int lineNumber) {
        return new ISourceLocation() {
            public int lineNumber() {return lineNumber;}

            public ISourceResourceAddress sourceResource() {return sourceResource;}
        };
    }

    /**
     * Line number in the source file. -1 is unknown. Lines without ddot.it syntax need not be sent.
     */
    int lineNumber();

    ISourceResourceAddress sourceResource();

}
