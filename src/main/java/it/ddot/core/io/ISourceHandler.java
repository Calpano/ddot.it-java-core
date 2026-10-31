package it.ddot.core.io;

import it.ddot.core.event.IDdotEvent;
import it.ddot.core.source.ISnippet;
import org.jspecify.annotations.Nullable;

/**
 * Sink for everything a source reader recognizes: lines, triple events, and the source/resource lifecycle.
 *
 * <p>Deliberately knowledge-graph-free, so that the ddot.it grammar and every reader can live in
 * {@code ddot-core} (which must not depend on graphinout). Handlers that additionally expose a
 * {@code Kgraph} implement {@code it.ddot.core.kbase.io.IKgraphSourceHandler}, in the {@code ddotit-kbase} artifact.
 */
public interface ISourceHandler {

    /** Set the current active subject */
    void activeSubject(String subject);

    /** retrieve the current active subject, if set */
    @Nullable String activeSubject();

    /**
     * @param line       to process
     * @param lineNumber 1-based, counting within the source. E.g. when parsing a Markdown file with 3 sections, the
     *                   reported line numbers for the second section = item will not start at 1.
     */
    void onLine(String line, int lineNumber);

    void onTripleEvent(IDdotEvent tripleEvent);

    void sourceEnd();

    void sourceResourceEnd();

    void sourceResourceStart(ISnippet snippet);

    /** Called once per source. Allows reusable implementations to reset their state. */
    void sourceStart();

}
