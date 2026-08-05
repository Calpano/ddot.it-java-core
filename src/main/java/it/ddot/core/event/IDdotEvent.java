package it.ddot.core.event;

import it.ddot.core.source.ISnippet;
import it.ddot.core.source.ISourceLocation;

/**
 * A {@link IDdotTriple}, its {@link IMeta}
 *
 * <h2>Two different line numbers</h2>
 * An event carries one line number of its own, {@link #startLineInSnippet()}, and inherits a second one from its
 * {@link ISnippet#startLineInResource() snippet}. They are only equal for the common case of a reader that treats
 * the whole file as one snippet starting at line 1, which is why mixing them up stays invisible until an embedded
 * snippet shows up. Never hand {@code snippet().sourceLocation()} to a consumer as the event's location — that is
 * the snippet's start; call {@link #sourceLocation()}, which adds the two together.
 */
public interface IDdotEvent extends IDdotTriple {

    default boolean hasMeta() {
        return !meta().isEmpty();
    }

    IMeta meta();

    ISnippet snippet();

    default String sourceKind() {
        return snippet().sourceKindId();
    }

    default String sourceUri() {
        return snippet().sourceUri();
    }

    /**
     * Relative to the {@link ISnippet}, at which line does this event start? (1-based, inclusive)
     *
     * <p>This is <em>not</em> a file line: a snippet lifted out of line 40 of a document reports {@code 1} for its
     * first event. Use {@link #startLineInResource()} to get the file line.
     */
    int startLineInSnippet();

    /**
     * The 1-based line of this event's first line <strong>in the source resource</strong>: the snippet's own start
     * line offset by the event's position within the snippet.
     */
    default int startLineInResource() {
        return snippet().startLineInResource() + startLineInSnippet() - 1;
    }

    /**
     * This event's own provenance — the resource it came from, at {@link #startLineInResource()}.
     *
     * <p>This, not {@code snippet().sourceLocation()}, is what a consumer records as "where the triple was
     * written": the snippet's location points at the snippet's first line and is identical for every event in it.
     */
    default ISourceLocation sourceLocation() {
        return ISourceLocation.of(snippet().sourceLocation().sourceResource(), startLineInResource());
    }

}
