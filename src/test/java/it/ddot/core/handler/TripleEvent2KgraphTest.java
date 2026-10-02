package it.ddot.core.handler;


import com.graphinout.base.cj.document.ICjDocument;
import com.graphinout.foundation.pure.json.document.IJsonObject;
import it.ddot.core.kbase.KnowledgeBases;
import it.ddot.core.kbase.inmem.Kedge;
import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.DdotVocabulary;
import it.ddot.core.event.DdotEvent;
import it.ddot.core.event.IDdotEvent;
import it.ddot.core.event.IMeta;
import it.ddot.core.event.IMetaPair;
import it.ddot.core.source.ISourceLocation;
import it.ddot.core.source.ISourceResourceAddress;
import it.ddot.core.io.handler.TripleEvent2Kgraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class TripleEvent2KgraphTest {

    private static final ISourceResourceAddress DOC = ISourceResourceAddress.of("test://doc");

    private Kgraph g;
    private TripleEvent2Kgraph handler;

    private static IDdotEvent event(String from, String type, String to, int line) {
        return DdotEvent.of(from, type, to, ISourceLocation.of(DOC, line));
    }

    private static IDdotEvent eventWithMeta(String from, String type, String to, int line, IMeta meta) {
        return DdotEvent.of(from, type, to, meta, ISourceLocation.of(DOC, line));
    }

    /**
     * Duplicate display labels must not crash. Two distinct entities may share a label (the label defaults to the id
     * but can be set separately in advanced ddot.it); identity is by id, so a triple referencing the shared text
     * resolves by id and creates the edge. (Regression: the old handler looked up nodes by label and threw
     * IllegalStateException when a label was shared by more than one node.)
     */
    /**
     * The collector is where `!!this` becomes the chunk's source URI (Parse Spec: a collector "does the
     * resolution a single reader cannot -- substituting `source` for `!!this`"). Readers emit it
     * verbatim, so if this stopped happening every document's self-references would merge onto one node
     * literally named "ddot.it/this".
     */
    @Test
    void thisCommand_resolvesToTheSourceUri() {
        handler.accept(event("ddot.it/this", "author", "Alice", 1));

        assertThat(g.findNodeById("test://doc")).isNotNull();
        assertThat(g.findNodeById("ddot.it/this")).isNull();
    }

    /** All four spellings the Parse Spec lists resolve, in either slot. */
    @Test
    void everySpellingOfThis_resolves() {
        handler.accept(event("!!this", "author", "Alice", 1));
        handler.accept(event("http://ddot.it/this", "author", "Bob", 2));
        handler.accept(event("https://ddot.it/this", "author", "Carol", 3));
        handler.accept(event("Dave", "wrote", "ddot.it/this", 4));

        assertThat(g.findNodeById("test://doc")).isNotNull();
        for (String spelling : List.of("!!this", "http://ddot.it/this", "https://ddot.it/this", "ddot.it/this")) {
            assertThat(g.findNodeById(spelling)).isNull();
        }
    }

    /** A name that merely STARTS with the command is ordinary text, not a self-reference. */
    @Test
    void lookalikeName_isNotResolved() {
        handler.accept(event("ddot.it/thisandthat", "is", "a name", 1));

        assertThat(g.findNodeById("ddot.it/thisandthat")).isNotNull();
    }

    @Test
    void duplicateLabels_doNotCrash() {
        // pre-existing graph: two different ids that happen to share the display label "Mercury"
        g.getOrCreateNodeWithIdAndLabel("planet-mercury", "Mercury");
        g.getOrCreateNodeWithIdAndLabel("element-mercury", "Mercury");
        assertThat(g.findNodesByLabel("Mercury").toList()).hasSize(2);

        handler.accept(event("Mercury", "orbits", "Sun", 1));

        assertThat(edges("Mercury", "orbits", "Sun")).hasSize(1);
    }

    /** No meta on the triple leaves edge data untouched (null). */
    @Test
    void noMeta_leavesEdgeDataNull() {
        handler.accept(event("Alice", "knows", "Bob", 1));
        Kedge edge = edges("Alice", "knows", "Bob").get(0);
        assertThat(edge.data()).isNull();
    }

    /** Re-reading the same source (same uri+line) must not pile up duplicate provenance on a node. */
    @Test
    void nodeProvenance_isDeduplicated() {
        handler.accept(event("Alice", "knows", "Bob", 1));
        handler.accept(event("Alice", "knows", "Carol", 1)); // Alice again, same source line
        assertThat(g.findNodeById("Alice").sourceLocations()).hasSize(1);
    }

    /** Subject/object/predicate nodes use id == text, so Constant queries hit the SPO index. */
    @Test
    void nodes_identifiedByText() {
        handler.accept(event("Alice", "knows", "Bob", 1));
        assertThat(g.findNodeById("Alice")).isNotNull();
        assertThat(g.findNodeById("Bob")).isNotNull();
        assertThat(g.findNodeById("knows")).isNotNull();
    }

    /** The predicate node stays provenance-free, so shared vocabulary survives a source removal. */
    @Test
    void predicateNode_hasNoProvenance() {
        handler.accept(event("Alice", "knows", "Bob", 1));
        assertThat(g.findNodeById("knows").sourceLocations()).isEmpty();
    }

    @BeforeEach
    void setUp() {
        g = new Kgraph("test");
        handler = new TripleEvent2Kgraph(g);
    }

    /** Provenance: the source location (resource + line) is recorded on the produced edge. */
    @Test
    void sourceLocation_storedOnEdge() {
        handler.accept(event("Alice", "knows", "Bob", 7));
        Kedge edge = edges("Alice", "knows", "Bob").get(0);
        assertThat(edge.sourceLocations()).hasSize(1);
        assertThat(edge.sourceLocations().get(0).lineNumber()).isEqualTo(7);
        assertThat(edge.sourceLocations().get(0).sourceResource().resourceUri()).isEqualTo("test://doc");
    }

    /** Provenance is recorded on both endpoint nodes so source removal can clean them up. */
    @Test
    void sourceLocation_storedOnEndpointNodes() {
        handler.accept(event("Alice", "knows", "Bob", 7));
        assertThat(g.findNodeById("Alice").sourceLocations()).hasSize(1);
        assertThat(g.findNodeById("Bob").sourceLocations()).hasSize(1);
        assertThat(g.findNodeById("Alice").sourceLocations().get(0).sourceResource().resourceUri()).isEqualTo("test://doc");
    }

    /** The triple's own meta (e.g. {@code ,, ..since.. 2025}) is folded into edge data and read back via meta(). */
    @Test
    void tripleMeta_foldedIntoEdgeData() {
        handler.accept(eventWithMeta("Alice", "knows", "Bob", 1, IMeta.of(Map.of("since", "2025"))));
        Kedge edge = edges("Alice", "knows", "Bob").get(0);
        assertThat(edge.meta()).isNotNull();
        assertThat(edge.meta().get("since")).isEqualTo("2025");
    }

    /**
     * A relation the triple writes TWICE ({@code ,, ..author.. Ann ;; ..author.. Bob}) keeps both values. Edge data
     * is a JSON object and cannot hold {@code author} twice, so they are stored as an array and read back as two
     * pairs; folding them in one by one, as this did, kept only Bob.
     */
    @Test
    void tripleMeta_repeatedRelation_keepsEveryValue() {
        IMeta meta = IMeta.of(List.of(IMetaPair.of("author", "Ann"), IMetaPair.of("author", "Bob")));
        handler.accept(eventWithMeta("Ann", "wrote", "Book", 1, meta));

        Kedge edge = edges("Ann", "wrote", "Book").get(0);
        assertThat(edge.meta().getAll("author")).containsExactly("Ann", "Bob").inOrder();
        assertThat(edge.meta().size()).isEqualTo(2);
    }

    /** Re-reading the same source must not append the same metadata a second time. */
    @Test
    void tripleMeta_reReadingTheSameTriple_isIdempotent() {
        IMeta meta = IMeta.of(List.of(IMetaPair.of("author", "Ann"), IMetaPair.of("author", "Bob")));
        handler.accept(eventWithMeta("Ann", "wrote", "Book", 1, meta));
        handler.accept(eventWithMeta("Ann", "wrote", "Book", 1, meta));

        Kedge edge = edges("Ann", "wrote", "Book").get(0);
        assertThat(edge.meta().getAll("author")).containsExactly("Ann", "Bob").inOrder();
    }

    /**
     * Edge metadata is the edge's data itself, one key per relation — no wrapping {@code meta} object. That is the
     * shape graphinout's ddot reader gives a CJ edge, so CJ from either side reads back the same way.
     */
    @Test
    void tripleMeta_isStoredFlatInEdgeData() {
        handler.accept(eventWithMeta("Alice", "knows", "Bob", 1, IMeta.of(List.of(
                IMetaPair.of("since", "2025"), IMetaPair.of(DdotVocabulary.TEXT, "a note")))));
        IJsonObject data = edges("Alice", "knows", "Bob").get(0).data().asObject();

        assertThat(data.keys()).containsExactly("since", DdotVocabulary.TEXT);
        assertThat(data.get("since").asString()).isEqualTo("2025");
        assertThat(data.get(DdotVocabulary.TEXT).asString()).isEqualTo("a note");
    }

    /** The CJ export writes the same flat edge data, and importing that CJ gives the metadata back. */
    @Test
    void tripleMeta_roundTripsThroughCjAsFlatEdgeData() {
        IMeta meta = IMeta.of(List.of(IMetaPair.of("since", "2025"), IMetaPair.of("author", "Ann"), IMetaPair.of("author", "Bob")));
        handler.accept(eventWithMeta("Ann", "wrote", "Book", 1, meta));

        ICjDocument cj = KnowledgeBases.toCj(g);
        IJsonObject cjEdgeData = cj.graphs().findFirst().orElseThrow()
                .edges().findFirst().orElseThrow().data().jsonValue().asObject();
        assertThat(cjEdgeData.get("since").asString()).isEqualTo("2025");
        assertThat(cjEdgeData.get("author").asArray().size()).isEqualTo(2);
        assertThat(cjEdgeData.get("meta")).isNull();

        Kedge back = KnowledgeBases.fromCj(cj).graphs().findFirst().orElseThrow().edges().findFirst().orElseThrow();
        assertThat(back.meta().get("since")).isEqualTo("2025");
        assertThat(back.meta().getAll("author")).containsExactly("Ann", "Bob").inOrder();
    }

    /** All meta keys are folded, not just the first. */
    @Test
    void tripleMeta_multipleKeys() {
        handler.accept(eventWithMeta("Alice", "knows", "Bob", 1, IMeta.of(Map.of("since", "2025", "source", "interview"))));
        Kedge edge = edges("Alice", "knows", "Bob").get(0);
        assertThat(edge.meta().get("since")).isEqualTo("2025");
        assertThat(edge.meta().get("source")).isEqualTo("interview");
    }

    /**
     * The actual subject→predicate→object edge the event represents must be created. (Regression: the old handler only
     * added "has source" edges and never the triple itself, so querying (Alice, knows, Bob) returned nothing.)
     */
    @Test
    void typedTriple_createsSemanticEdge() {
        handler.accept(event("Alice", "knows", "Bob", 1));
        assertThat(edges("Alice", "knows", "Bob")).hasSize(1);
    }

    /** An untyped link ({@code S .... O}) reaches the graph as the spelled-out "links to" predicate. */
    @Test
    void untypedTriple_usesLinksToPredicate() {
        handler.accept(event("Alice", DdotVocabulary.LINKS_TO, "Bob", 1));
        assertThat(edges("Alice", "links to", "Bob")).hasSize(1);
    }

    private List<Kedge> edges(String s, String p, String o) {
        return g.pattern(new Kgraph.Pattern(new Kgraph.Constant(s), new Kgraph.Constant(p), new Kgraph.Constant(o)));
    }

}
