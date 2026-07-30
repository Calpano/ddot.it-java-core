package com.calpano.ddot.it.source;

import com.calpano.ddot.it.kserver.base.ITripleEvent;
import org.jspecify.annotations.Nullable;

/**
 * Sink for everything a source reader recognises: lines, triple events and the source/resource lifecycle.
 *
 * <p>Deliberately knowledge-graph-free, so that the ddot.it grammar and every reader can live in
 * {@code ddot-core} (which must not depend on graphinout). Handlers that additionally expose a
 * {@code Kgraph} implement {@code com.calpano.ddot.it.source.handler.IKgraphSourceHandler} in
 * {@code ddot-graph}.
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

    void onTripleEvent(ITripleEvent tripleEvent);

    void sourceEnd();

    void sourceResourceEnd(ISourceResourceAddress sourceResourceAddress);

    void sourceResourceStart(ISourceResourceAddress sourceResourceAddress);

    /** Called once per source. Allows reusable implementations to reset their state. */
    void sourceStart();

}
