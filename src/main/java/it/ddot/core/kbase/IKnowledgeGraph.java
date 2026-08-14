package it.ddot.core.kbase;

import it.ddot.core.kbase.IKnowledgeLabel;
import it.ddot.core.kbase.inmem.Kedge;
import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.kbase.inmem.Klabel;
import it.ddot.core.kbase.inmem.Knode;
import com.graphinout.base.cj.document.ICjDocumentMutable;
import com.graphinout.foundation.pure.json.query.JsonQuery;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A single graph: a collection of {@link Knode}s and {@link Kedge}s (the latter being n-ary hyperedges, see
 * {@link IKnowledgeEdge}) with querying, pattern matching, type assignment, node merging and inference.
 *
 * <p>Types are modeled as ordinary edges: {@link #addTypeId} / {@link #addTypeName} create a {@value #HAS_TYPE}
 * binary edge from the node to a type node, and {@link #findNodesByType} / {@link #removeType} read and remove
 * those edges. Many methods here are convenience defaults built on the small set of abstract primitives
 * ({@link #addNode}, {@link #addEdge}, {@link #edges()}, {@link #nodes()}, {@link #removeEdge}, etc.).
 *
 * <p>The default in-memory implementation is {@code it.ddot.core.kbase.inmem.Kgraph}.
 */
public interface IKnowledgeGraph {

    /** Predicate label used for type edges created by {@link #addTypeId}/{@link #addTypeName}. */
    String HAS_TYPE = "has type";

    /** Adds a binary edge {@code subject --predicate--> object} (subject is {@code in}, object is {@code out}). */
    void addBiEdge(Knode subject, String predicate, Knode object);

    /** Adds an already-constructed edge to this graph. */
    void addEdge(Kedge edge);

    /**
     * Adds {@code edge} only if no structurally identical edge (same label text + same endpoints) already exists,
     * unless {@code forceCreate} is {@code true}.
     *
     * @return {@code true} if the edge was added, {@code false} if it was skipped as a duplicate
     */
    default boolean addEdge(Kedge edge, boolean forceCreate) {
        if (!forceCreate && edges().anyMatch(e -> edgesStructurallyEqual(e, edge))) return false;
        addEdge(edge);
        return true;
    }

    /** Returns {@code true} if two edges have the same label text and the same endpoint structure (direction + nodeId + typeId). */
    private static boolean edgesStructurallyEqual(Kedge a, Kedge b) {
        String labelA = a.label() != null && !a.label().entries().isEmpty() ? a.label().entries().getFirst().value : null;
        String labelB = b.label() != null && !b.label().entries().isEmpty() ? b.label().entries().getFirst().value : null;
        if (!Objects.equals(labelA, labelB)) return false;
        if (a.endpoints().size() != b.endpoints().size()) return false;
        List<String> keysA = a.endpoints().stream()
                .map(ep -> ep.kdirection.name() + ":" + ep.node.id() + (ep.type != null ? ":" + ep.type.id() : ""))
                .sorted().toList();
        List<String> keysB = b.endpoints().stream()
                .map(ep -> ep.kdirection.name() + ":" + ep.node.id() + (ep.type != null ? ":" + ep.type.id() : ""))
                .sorted().toList();
        return keysA.equals(keysB);
    }

    /** Adds a node to this graph. */
    void addNode(Knode node);

    /**
     * Records that {@code node} has type {@code typeId} by adding a {@code "has type"} binary edge. The
     * type-representing node is created if it does not already exist.
     */
    default void addTypeId(Knode node, String typeId) {
        addBiEdge(node, HAS_TYPE, getOrCreateNodeWithIdAndLabel(typeId));
    }

    default void addTypeName(Knode node, String typeName) {
        List<Knode> nodes = new ArrayList<>(findNodesByLabel(typeName).toList());
        Knode typeNode =
        switch (nodes.size()) {
            case 0 -> {
                // create a new node
                typeNode = new Knode(createId());
                typeNode.addLabelWithoutLanguage(typeName);
                yield typeNode;
            }
            case 1 -> nodes.getFirst();
            default -> {
                // sort nodes by id, take first one
                nodes.sort(Comparator.comparing(Knode::id));
                yield nodes.getFirst();
            }
        };
        addBiEdge(node, HAS_TYPE, typeNode);
    }

    /** Removes all edges and nodes from this graph. */
    default void clear() {
        clearEdges();
        clearNodes();
    }

    /** Removes all edges from this graph. */
    default void clearEdges() {
        edges().toList().forEach(this::removeEdge);
    }

    /** Removes all nodes from this graph. */
    default void clearNodes() {
        nodes().toList().forEach(this::removeNode);
    }

    /** Creates a new empty edge (without adding it to the graph). */
    Kedge createEdge();

    /** Generates a fresh, graph-unique identifier suitable for a new node. */
    String createId();

    /** Streams all edges in this graph. */
    Stream<Kedge> edges();

    /** Streams edges whose {@link IKnowledgeItem#data() data} matches the given JSON query. */
    Stream<Kedge> edgesMatchingData(JsonQuery query);

    /**
     * Exports this graph as a Connected JSON document containing a single graph entry.
     */
    default ICjDocumentMutable exportToCj() {
        return KnowledgeBases.toCj(this);
    }

    /** Returns the node with the given id, or {@code null} if none exists. */
    @Nullable Knode findNodeById(String id);

    /** Streams all nodes carrying a label entry equal to {@code label}. */
    Stream<Knode> findNodesByLabel(String label);

    /**
     * Returns all nodes that have a {@code "has type"} edge pointing to the node with id {@code typeId}.
     */
    default Stream<Knode> findNodesByType(String typeId) {
        return edges()
                .filter(e -> {
                    boolean isHasType = e.endpoints().stream()
                            .anyMatch(ep -> ep.kdirection == Kendpoint.Kdirection.in
                                    && ep.type != null && ep.type.id().equals(HAS_TYPE));
                    boolean hasTargetType = e.endpoints().stream()
                            .anyMatch(ep -> ep.kdirection == Kendpoint.Kdirection.out
                                    && ep.node.id().equals(typeId));
                    return isHasType && hasTargetType;
                })
                .map(e -> e.endpoints().stream()
                        .filter(ep -> ep.kdirection == Kendpoint.Kdirection.in)
                        .findFirst().map(ep -> ep.node).orElse(null))
                .filter(Objects::nonNull);
    }

    /** Returns the node with id {@code s}, creating it (using {@code s} as both id and label) if absent. */
    Knode getOrCreateNodeWithIdAndLabel(String s);

    /** Returns the node with the given id, creating it with the given label if absent. */
    Knode getOrCreateNodeWithIdAndLabel(String id, String label);

    /** Returns {@code true} if a node or edge with the given id exists in this graph. */
    boolean hasItem(String id);

    /** The identifier of this graph. */
    String id();

    /** Runs the graph's inference rules, materializing derived edges/nodes in place. */
    void inference();

    /** The label of this graph, or {@code null} if unlabeled. */
    @Nullable IKnowledgeLabel label();

    /** Sets the label of this graph. */
    void label(Klabel label);

    /**
     * Merges {@code sourceNodeIds} into {@code targetId}: re-creates every edge containing a source node with the
     * target substituted in, then removes the source nodes. Creates the target node if absent.
     *
     * @return the (possibly newly created) target node
     */
    default Knode mergeNodes(String[] sourceNodeIds, String targetId) {
        Knode target = getOrCreateNodeWithIdAndLabel(targetId);
        Set<String> sourceIds = new HashSet<>(Arrays.asList(sourceNodeIds));
        List<Kedge> toRemap = edges()
                .filter(e -> e.endpoints().stream().anyMatch(ep -> sourceIds.contains(ep.node.id())))
                .toList();
        for (Kedge oldEdge : toRemap) {
            Kedge newEdge = createEdge();
            if (oldEdge.label() != null) newEdge.label(oldEdge.label());
            if (oldEdge.data() != null) newEdge.data(oldEdge.data());
            oldEdge.endpoints().forEach(ep -> {
                Knode remapped = sourceIds.contains(ep.node.id()) ? target : ep.node;
                newEdge.addEndpoint(ep.kdirection, remapped, ep.type);
            });
            removeEdge(oldEdge);
            addEdge(newEdge);
        }
        Arrays.stream(sourceNodeIds).forEach(id -> {
            Knode src = findNodeById(id);
            if (src != null) removeNode(src);
        });
        return target;
    }

    /** Streams all nodes in this graph. */
    Stream<Knode> nodes();

    /** Streams nodes whose {@link IKnowledgeItem#data() data} matches the given JSON query. */
    Stream<Knode> nodesMatchingData(JsonQuery query);

    /** Streams nodes matching {@code slot} under the given {@link Kgraph.NodeMatchMode match mode}. */
    Stream<Knode> nodesMatchingSlot(Kgraph.Slot slot, Kgraph.NodeMatchMode mode);

    /** Returns all edges matching the given graph {@link Kgraph.Pattern}. */
    List<Kedge> pattern(Kgraph.Pattern pattern);

    /** Runs a structured {@link Kgraph.Query} against this graph and returns its result. */
    Kgraph.QueryResult query(Kgraph.Query query);

    /** Removes an edge from this graph. */
    void removeEdge(Kedge edge);

    /** Removes a node from this graph. Does not remove edges that still reference the node. */
    void removeNode(Knode node);

    /** Outcome of a {@link #removeBySource(String)} call. */
    record RemoveBySourceResult(int edgesRemoved, int nodesRemoved, int inferredEdgesRemoved) {}

    /**
     * Removes everything that originated <em>solely</em> from the source identified by {@code resourceUri}.
     * Intended as the first half of "update a file": call this, then re-read the new file contents through
     * the normal ingestion pipeline.
     *
     * <p>For every node and edge, provenance entries pointing at {@code resourceUri} are dropped. An item that
     * <em>had</em> such provenance and is left with <em>no</em> source locations is then removed; items that
     * still carry provenance from another source survive, and items that never had provenance (e.g. shared
     * predicate nodes) are left untouched. This relies on ingestion recording provenance on both endpoint
     * nodes and the edge (see {@code TripleEvent2Kgraph}).
     *
     * <p>Additionally, <strong>all inferred edges are discarded</strong> (their premises may have been removed),
     * so callers should re-run {@link #inference()} afterwards to regenerate still-valid inferences.
     *
     * @return counts of what was removed
     */
    RemoveBySourceResult removeBySource(String resourceUri);

    /**
     * Removes the {@code "has type"} edge from {@code node} to the node with id {@code typeId}.
     */
    default void removeType(Knode node, String typeId) {
        edges()
                .filter(e -> {
                    boolean isHasTypeFrom = e.endpoints().stream()
                            .anyMatch(ep -> ep.kdirection == Kendpoint.Kdirection.in
                                    && ep.node.id().equals(node.id())
                                    && ep.type != null && ep.type.id().equals(HAS_TYPE));
                    boolean hasTargetType = e.endpoints().stream()
                            .anyMatch(ep -> ep.kdirection == Kendpoint.Kdirection.out
                                    && ep.node.id().equals(typeId));
                    return isHasTypeFrom && hasTargetType;
                })
                .toList()
                .forEach(this::removeEdge);
    }

}
