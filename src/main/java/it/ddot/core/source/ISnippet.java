package it.ddot.core.source;

/**
 * As parsed from a host reader.
 */
public interface ISnippet {

    static ISnippet createUndefined() {
        return new Snippet("undefinedSourceKind", ISourceLocation.of(ISourceResourceAddress.of("undefinedSourceUri"), 1));
    }

    /** A snippet starting at {@code startLine}. */
    static ISnippet of(String sourceKind, ISourceLocation sourceLocation) {
        return new Snippet(sourceKind, sourceLocation);
    }

    static ISnippet of(String sourceUri) {
        return new Snippet("undefinedSourceKind", ISourceLocation.of(ISourceResourceAddress.of(sourceUri), 1));
    }

    /** for usage in tests */
    static ISnippet ofPlainUri(String uri) {
        return of("unknownKind", ISourceLocation.of(ISourceResourceAddress.of(uri), 1));
    }

    String sourceKindId();

    /**
     * Where the snippet itself begins in the source resource. Its {@link ISourceLocation#lineNumber()} is the
     * snippet's <em>own</em> first line in the file — <strong>not</strong> the location of any event inside it.
     * For the location of a single event, use {@link it.ddot.core.event.IDdotEvent#sourceLocation()}, which offsets
     * the event's snippet-relative line by this one.
     *
     * @see #startLineInResource()
     */
    ISourceLocation sourceLocation();

    /**
     * The 1-based line, <strong>in the source resource</strong>, at which this snippet starts. A reader that hands
     * the whole file to the parser as one snippet reports {@code 1} here; a reader that lifts an embedded chunk (an
     * AsciiDoc source block, an HTML comment) reports the file line that chunk begins on.
     */
    default int startLineInResource() {
        return sourceLocation().lineNumber();
    }

    default String sourceUri() {
        return sourceLocation().sourceResource().resourceUri();
    }

}
