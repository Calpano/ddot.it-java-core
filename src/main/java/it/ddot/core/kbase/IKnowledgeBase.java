package it.ddot.core.kbase;

import it.ddot.core.kbase.IKnowledgeGraph;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.stream.Stream;

/**
 * A knowledge base: a named collection of {@link IKnowledgeGraph}s plus a document-level {@code @context}
 * namespace map. The default in-memory implementation is {@code it.ddot.core.kbase.inmem.Kbase}.
 */
public interface IKnowledgeBase {

    /** Creates and registers a new, empty graph with the given id. Behavior is undefined if the id already exists. */
    IKnowledgeGraph createGraph(String id);

    /** Returns the graph with the given id, or {@code null} if none exists. */
    @Nullable IKnowledgeGraph findGraphById(String id);

    /** Returns the conventional default graph, creating it on first use. */
    IKnowledgeGraph getOrCreateDefaultGraph();

    /** Returns the graph with the given id, creating an empty one if it does not yet exist. */
    IKnowledgeGraph getOrCreateGraph(String id);

    /** Streams all graphs currently held by this base. */
    Stream<? extends IKnowledgeGraph> graphs();

    /** Removes the graph with the given id; a no-op if it does not exist. */
    void deleteGraph(String id);

    /** Removes every graph from this base. */
    default void deleteAllGraphs() {
        graphs().map(IKnowledgeGraph::id).toList().forEach(this::deleteGraph);
    }

    /** Document-level {@code @context} namespace map for URI prefix expansion (e.g. {@code @vocab} → base URI). */
    @Nullable Map<String, String> context();

    /** Sets the document-level {@code @context} namespace map ({@code null} clears it). */
    void context(@Nullable Map<String, String> context);

}
