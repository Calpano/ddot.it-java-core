package com.calpano.ddot.it.refactor;

/**
 * One surgical edit to a source file, keyed by {@code resourceUri} (a {@code file:} URI) and a 1-based line
 * number. Produced by {@link GraphRewriter#plan} and applied by {@link LineBasedSourceWriter}.
 */
public sealed interface SourceEdit {

    String resourceUri();

    /** Delete the line a removed triple came from. */
    record DeleteLine(String resourceUri, int lineNumber) implements SourceEdit {}

    /** Replace the line in place (e.g. a rename re-serialized onto the original line). */
    record ReplaceLine(String resourceUri, int lineNumber, String newText) implements SourceEdit {}

    /**
     * Replace the line range {@code [startLine..endLine]} (inclusive, 1-based) with {@code newLines} — for
     * multi-line snippets such as a {@code !!block} object. An empty {@code newLines} deletes the span.
     */
    record ReplaceSpan(String resourceUri, int startLine, int endLine, java.util.List<String> newLines)
            implements SourceEdit {}

    /** Insert a new triple line directly after {@code lineNumber} (added triple, next to the primary match). */
    record InsertAfter(String resourceUri, int lineNumber, String newText) implements SourceEdit {}

    /** Append a new triple line at the end of the file (placement override). */
    record AppendToFile(String resourceUri, String newText) implements SourceEdit {}

}
