package it.ddot.core.source;



/**
 * @param sourceKindId The host format the reader read, as a short lowercase name. See {@link it.ddot.core.DdotSourceFormats}.
 * @param sourceLocation  The chunk's source URI, verbatim — e.g. {@code /README.md} or
 *                   {@code file:///Users/me/example.adoc}. This is the value {@code !!this} stands for, though the
 *                   substitution happens in the collector, not here.
 */
public record Snippet(
        String sourceKindId,
        ISourceLocation sourceLocation
) implements ISnippet {


}
