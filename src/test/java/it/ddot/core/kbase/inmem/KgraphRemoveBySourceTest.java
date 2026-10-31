package it.ddot.core.kbase.inmem;

import it.ddot.core.kbase.IKnowledgeGraph.RemoveBySourceResult;
import it.ddot.core.event.DdotEvent;
import it.ddot.core.source.ISnippet;
import it.ddot.core.io.handler.TripleEvent2Kgraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** Provenance-scoped removal: {@link Kgraph#removeBySource(String)}. */
class KgraphRemoveBySourceTest {

    private static final String FILE_A = "file://a";
    private static final String FILE_B = "file://b";

    private Kgraph g;
    private TripleEvent2Kgraph handler;

    /** Inferred edges are discarded by removeBySource (premises may be gone); they are counted separately. */
    @Test
    void discardsInferredEdges() {
        ingest(FILE_A, "Alice", "has type", "Dog", 1);
        ingest(FILE_A, "Animal", "has subtype", "Dog", 2);
        g.inference(); // derives: Alice has type Animal
        assertThat(edges("Alice", "has type", "Animal")).hasSize(1);

        RemoveBySourceResult r = g.removeBySource(FILE_A);

        assertThat(r.inferredEdgesRemoved()).isAtLeast(1);
        assertThat(edges("Alice", "has type", "Animal")).isEmpty();
    }

    /** A node referenced by another source survives; only the removed file's edge goes. */
    @Test
    void keepsEntitiesWithOtherProvenance() {
        ingest(FILE_A, "Alice", "knows", "Bob", 1);
        ingest(FILE_B, "Alice", "works at", "Acme", 1);

        g.removeBySource(FILE_A);

        assertThat(edges("Alice", "knows", "Bob")).isEmpty();   // from A: gone
        assertThat(edges("Alice", "works at", "Acme")).hasSize(1); // from B: kept
        assertThat(g.findNodeById("Alice")).isNotNull();        // also in B
        assertThat(g.findNodeById("Acme")).isNotNull();         // only in B
        assertThat(g.findNodeById("Bob")).isNull();             // only in A
    }

    /** An edge and a node sourced solely from the removed file disappear; the predicate node remains. */
    @Test
    void removesEdgesAndNodesSourcedOnlyFromThatFile() {
        ingest(FILE_A, "Alice", "knows", "Bob", 1);

        RemoveBySourceResult r = g.removeBySource(FILE_A);

        assertThat(edges("Alice", "knows", "Bob")).isEmpty();
        assertThat(g.findNodeById("Alice")).isNull();
        assertThat(g.findNodeById("Bob")).isNull();
        // predicate node is vocabulary: never had provenance, so it survives
        assertThat(g.findNodeById("knows")).isNotNull();
        assertThat(r.edgesRemoved()).isEqualTo(1);
        assertThat(r.nodesRemoved()).isEqualTo(2);
    }

    @BeforeEach
    void setUp() {
        g = new Kgraph("test");
        handler = new TripleEvent2Kgraph(g);
    }

    /** The same triple seen in two files yields two edges; removing one file leaves the other's edge + nodes. */
    @Test
    void sharedTripleAcrossFiles_keepsTheSurvivingEdge() {
        ingest(FILE_A, "Alice", "knows", "Bob", 1);
        ingest(FILE_B, "Alice", "knows", "Bob", 9);

        g.removeBySource(FILE_A);

        assertThat(edges("Alice", "knows", "Bob")).hasSize(1);
        assertThat(g.findNodeById("Bob")).isNotNull(); // still has B provenance
        assertThat(g.findNodeById("Bob").sourceLocations()).hasSize(1);
        assertThat(g.findNodeById("Bob").sourceLocations().getFirst().sourceResource().resourceUri())
                .isEqualTo(FILE_B);
    }

    /** Removing an unknown source is a harmless no-op. */
    @Test
    void unknownSource_isNoOp() {
        ingest(FILE_A, "Alice", "knows", "Bob", 1);

        RemoveBySourceResult r = g.removeBySource("file://nonexistent");

        assertThat(r.edgesRemoved()).isEqualTo(0);
        assertThat(r.nodesRemoved()).isEqualTo(0);
        assertThat(edges("Alice", "knows", "Bob")).hasSize(1);
    }

    private List<Kedge> edges(String s, String p, String o) {
        return g.pattern(new Kgraph.Pattern(
                new Kgraph.Constant(s), new Kgraph.Constant(p), new Kgraph.Constant(o)));
    }

    private void ingest(String uri, String from, String type, String to, int line) {
        DdotEvent de = new DdotEvent(ISnippet.of(uri), from, type, to, line);
        handler.accept(de);
    }

}
