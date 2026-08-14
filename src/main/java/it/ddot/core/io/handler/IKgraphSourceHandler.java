package it.ddot.core.io.handler;

import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.io.ISourceHandler;

/**
 * An {@link ISourceHandler} that is backed by a {@link Kgraph} and exposes it, so readers can add graph data
 * that does not fit the (subject, predicate, object) triple-event stream — e.g. node labels, types and inline
 * HTML resources discovered by the AsciiDoc reader.
 *
 * <p>This method used to sit on {@link ISourceHandler} itself. It was split out so that {@code ISourceHandler}
 * — and with it the ddot.it grammar and every reader interface — can live in the zero-dependency
 * {@code ddot-core} module: {@link Kgraph} pulls in {@code com.graphinout:base}, which {@code ddot-core} must
 * never depend on (see ddot-core/pom.xml).
 */
public interface IKgraphSourceHandler extends ISourceHandler {

    /** Expose the active kgraph for advanced data */
    Kgraph kgraph();

}
