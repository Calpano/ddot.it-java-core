package it.ddot.core.kserver.base;

import it.ddot.core.kbase.inmem.Kedge;
import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.kbase.inmem.Knode;
import it.ddot.core.kbase.Kendpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class KgraphTest {

    private Kgraph g;

    @BeforeEach
    void setUp() {
        g = new Kgraph("test");
    }

    // ---- Slot matching -------------------------------------------------------

    @Test
    void constant_matchesExact() {
        assertThat(new Kgraph.Constant("hello").matches("hello")).isTrue();
        assertThat(new Kgraph.Constant("hello").matches("world")).isFalse();
    }

    @Test
    void variable_matchesAnything() {
        Kgraph.Variable v = new Kgraph.Variable("x");
        assertThat(v.matches("anything")).isTrue();
        assertThat(v.matches("")).isTrue();
    }

    @Test
    void oneOf_matchesMember() {
        Kgraph.OneOf slot = new Kgraph.OneOf(Set.of("a", "b", "c"));
        assertThat(slot.matches("a")).isTrue();
        assertThat(slot.matches("b")).isTrue();
        assertThat(slot.matches("d")).isFalse();
    }

    @Test
    void prefix_matchesCaseInsensitive() {
        Kgraph.Prefix p = new Kgraph.Prefix("Wor");
        assertThat(p.matches("works at")).isTrue();
        assertThat(p.matches("world")).isTrue();
        assertThat(p.matches("World")).isTrue();
        assertThat(p.matches("knows")).isFalse();
    }

    @Test
    void initials_matchesWordInitials() {
        Kgraph.Initials wa = new Kgraph.Initials("wa");
        assertThat(wa.matches("works at")).isTrue();
        assertThat(wa.matches("was around")).isTrue();
        assertThat(wa.matches("knows")).isFalse();
        assertThat(wa.matches("w")).isFalse(); // only one word, initials need two
    }

    @Test
    void initials_singleLetter_matchesFirstWord() {
        Kgraph.Initials k = new Kgraph.Initials("k");
        assertThat(k.matches("knows")).isTrue();
        assertThat(k.matches("works at")).isFalse();
    }

    // ---- addBiEdge / nodes / edges -------------------------------------------

    @Test
    void addBiEdge_createsSubjectObjectAndPredicateNodes() {
        Knode alice = g.getOrCreateNodeWithIdAndLabel("alice");
        Knode bob   = g.getOrCreateNodeWithIdAndLabel("bob");
        g.addBiEdge(alice, "knows", bob);

        assertThat(g.findNodeById("knows")).isNotNull();
        assertThat(g.edges().count()).isEqualTo(1);
    }

    @Test
    void addBiEdge_samePredicateNodeReused() {
        Knode a = g.getOrCreateNodeWithIdAndLabel("a");
        Knode b = g.getOrCreateNodeWithIdAndLabel("b");
        Knode c = g.getOrCreateNodeWithIdAndLabel("c");
        g.addBiEdge(a, "rel", b);
        g.addBiEdge(a, "rel", c);

        long predCount = g.nodes().filter(n -> n.id().equals("rel")).count();
        assertThat(predCount).isEqualTo(1);
    }

    @Test
    void nodes_returnsAllNodes() {
        g.addNode(new Knode("n1"));
        g.addNode(new Knode("n2"));
        assertThat(g.nodes().map(Knode::id).toList()).containsExactly("n1", "n2");
    }

    @Test
    void findNodesByLabel_returnsMatchingNodes() {
        Knode n = new Knode("id1");
        n.addLabelWithoutLanguage("Alice");
        g.addNode(n);
        g.addNode(new Knode("id2")); // no label

        assertThat(g.findNodesByLabel("Alice").toList()).hasSize(1);
        assertThat(g.findNodesByLabel("Bob").toList()).isEmpty();
    }

    // ---- pattern() -----------------------------------------------------------

    @Test
    void pattern_constant_findsExactEdge() {
        Knode alice = g.getOrCreateNodeWithIdAndLabel("Alice");
        Knode bob   = g.getOrCreateNodeWithIdAndLabel("Bob");
        g.addBiEdge(alice, "knows", bob);

        Kgraph.Pattern p = new Kgraph.Pattern(
                new Kgraph.Constant("Alice"),
                new Kgraph.Constant("knows"),
                new Kgraph.Constant("Bob"));
        assertThat(g.pattern(p)).hasSize(1);
    }

    @Test
    void pattern_variable_matchesAll() {
        Knode a = g.getOrCreateNodeWithIdAndLabel("a");
        Knode b = g.getOrCreateNodeWithIdAndLabel("b");
        Knode c = g.getOrCreateNodeWithIdAndLabel("c");
        g.addBiEdge(a, "rel", b);
        g.addBiEdge(a, "rel", c);

        Kgraph.Variable X = new Kgraph.Variable("x");
        Kgraph.Variable Y = new Kgraph.Variable("y");
        Kgraph.Variable Z = new Kgraph.Variable("z");
        assertThat(g.pattern(new Kgraph.Pattern(X, Y, Z))).hasSize(2);
    }

    @Test
    void pattern_prefix_matchesByPrefix() {
        Knode a = g.getOrCreateNodeWithIdAndLabel("a");
        Knode b = g.getOrCreateNodeWithIdAndLabel("b");
        Knode c = g.getOrCreateNodeWithIdAndLabel("c");
        g.addBiEdge(a, "works at", b);
        g.addBiEdge(a, "knows", c);

        Kgraph.Variable X = new Kgraph.Variable("x");
        Kgraph.Variable Z = new Kgraph.Variable("z");
        List<Kedge> results = g.pattern(new Kgraph.Pattern(X, new Kgraph.Prefix("wor"), Z));
        assertThat(results).hasSize(1);
    }

    @Test
    void pattern_initials_matchesByWordInitials() {
        Knode a = g.getOrCreateNodeWithIdAndLabel("a");
        Knode b = g.getOrCreateNodeWithIdAndLabel("b");
        g.addBiEdge(a, "works at", b);

        Kgraph.Variable X = new Kgraph.Variable("x");
        Kgraph.Variable Z = new Kgraph.Variable("z");
        assertThat(g.pattern(new Kgraph.Pattern(X, new Kgraph.Initials("wa"), Z))).hasSize(1);
        assertThat(g.pattern(new Kgraph.Pattern(X, new Kgraph.Initials("kn"), Z))).isEmpty();
    }

    @Test
    void pattern_noMatch_returnsEmpty() {
        Knode a = g.getOrCreateNodeWithIdAndLabel("a");
        Knode b = g.getOrCreateNodeWithIdAndLabel("b");
        g.addBiEdge(a, "rel", b);

        assertThat(g.pattern(new Kgraph.Pattern(
                new Kgraph.Constant("x"),
                new Kgraph.Variable("p"),
                new Kgraph.Variable("o")))).isEmpty();
    }

    // ---- query() -------------------------------------------------------------

    @Test
    void query_singlePattern_returnsBindings() {
        Knode alice = g.getOrCreateNodeWithIdAndLabel("Alice");
        Knode bob   = g.getOrCreateNodeWithIdAndLabel("Bob");
        g.addBiEdge(alice, "knows", bob);

        Kgraph.Variable X = new Kgraph.Variable("x");
        Kgraph.Variable Z = new Kgraph.Variable("z");
        Kgraph.QueryResult res = g.query(new Kgraph.Query(List.of(
                new Kgraph.Pattern(X, new Kgraph.Constant("knows"), Z))));

        assertThat(res.matches).hasSize(1);
        assertThat(res.matches.getFirst().get(X)).isEqualTo("Alice");
        assertThat(res.matches.getFirst().get(Z)).isEqualTo("Bob");
    }

    /** Variable equality is by name: bindings are readable via a fresh instance or by name string. */
    @Test
    void query_bindingsReadableByVariableName() {
        Knode alice = g.getOrCreateNodeWithIdAndLabel("Alice");
        Knode bob   = g.getOrCreateNodeWithIdAndLabel("Bob");
        g.addBiEdge(alice, "knows", bob);

        Kgraph.QueryResult res = g.query(new Kgraph.Query(List.of(new Kgraph.Pattern(
                new Kgraph.Variable("x"), new Kgraph.Constant("knows"), new Kgraph.Variable("z")))));

        assertThat(res.matches).hasSize(1);
        Kgraph.Match m = res.matches.getFirst();
        // read with a different Variable instance of the same name, and by name string
        assertThat(m.get(new Kgraph.Variable("x"))).isEqualTo("Alice");
        assertThat(m.get("z")).isEqualTo("Bob");
    }

    @Test
    void query_joinTwoPatterns_bindingsConsistent() {
        // Alice knows Bob, Bob knows Carol → find (x knows z) & (z knows w)
        Knode alice = g.getOrCreateNodeWithIdAndLabel("Alice");
        Knode bob   = g.getOrCreateNodeWithIdAndLabel("Bob");
        Knode carol = g.getOrCreateNodeWithIdAndLabel("Carol");
        g.addBiEdge(alice, "knows", bob);
        g.addBiEdge(bob, "knows", carol);

        Kgraph.Variable X = new Kgraph.Variable("x");
        Kgraph.Variable Z = new Kgraph.Variable("z");
        Kgraph.Variable W = new Kgraph.Variable("w");
        Kgraph.QueryResult res = g.query(new Kgraph.Query(List.of(
                new Kgraph.Pattern(X, new Kgraph.Constant("knows"), Z),
                new Kgraph.Pattern(Z, new Kgraph.Constant("knows"), W))));

        assertThat(res.matches).hasSize(1);
        assertThat(res.matches.getFirst().get(X)).isEqualTo("Alice");
        assertThat(res.matches.getFirst().get(Z)).isEqualTo("Bob");
        assertThat(res.matches.getFirst().get(W)).isEqualTo("Carol");
    }

    @Test
    void query_noSatisfyingJoin_returnsEmpty() {
        Knode a = g.getOrCreateNodeWithIdAndLabel("a");
        Knode b = g.getOrCreateNodeWithIdAndLabel("b");
        Knode c = g.getOrCreateNodeWithIdAndLabel("c");
        g.addBiEdge(a, "rel", b);
        g.addBiEdge(c, "other", b);

        Kgraph.Variable X = new Kgraph.Variable("x");
        Kgraph.Variable Z = new Kgraph.Variable("z");
        Kgraph.Variable W = new Kgraph.Variable("w");
        // (x rel z) & (z rel w) — no chain exists
        Kgraph.QueryResult res = g.query(new Kgraph.Query(List.of(
                new Kgraph.Pattern(X, new Kgraph.Constant("rel"), Z),
                new Kgraph.Pattern(Z, new Kgraph.Constant("rel"), W))));
        assertThat(res.matches).isEmpty();
    }

    // ---- inference() ---------------------------------------------------------

    @Test
    void inference_subtypeImpliesSupertype() {
        // Alice has type Dog; Animal has subtype Dog (meaning Dog ⊂ Animal)
        // → infer Alice has type Animal
        Knode alice  = g.getOrCreateNodeWithIdAndLabel("Alice");
        Knode dog    = g.getOrCreateNodeWithIdAndLabel("Dog");
        Knode animal = g.getOrCreateNodeWithIdAndLabel("Animal");
        g.addBiEdge(alice,  "has type",    dog);
        g.addBiEdge(animal, "has subtype", dog); // u=Animal has-subtype z=Dog

        g.inference();

        // Alice should now also have type Animal
        Kgraph.Variable X = new Kgraph.Variable("x");
        Kgraph.Variable Z = new Kgraph.Variable("z");
        List<Kedge> typeEdges = g.pattern(new Kgraph.Pattern(
                new Kgraph.Constant("Alice"),
                new Kgraph.OneOf(Set.of("has type", "type", "is a")),
                X));
        List<String> inferredTypes = typeEdges.stream()
                .map(e -> e.endpoints().stream()
                        .filter(ep -> ep.kdirection == Kendpoint.Kdirection.out)
                        .findFirst().map(ep -> ep.node.id()).orElse(""))
                .toList();
        assertThat(inferredTypes).contains("Animal");
    }

    @Test
    void inference_noSubtypeRelation_addsNothing() {
        Knode a = g.getOrCreateNodeWithIdAndLabel("a");
        Knode b = g.getOrCreateNodeWithIdAndLabel("b");
        g.addBiEdge(a, "knows", b);

        long edgesBefore = g.edges().count();
        g.inference();
        assertThat(g.edges().count()).isEqualTo(edgesBefore);
    }

    // ---- misc ----------------------------------------------------------------

    @Test
    void createId_returnsDistinctIds() {
        String id1 = g.createId();
        String id2 = g.createId();
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void hasItem_trueAfterAddNode() {
        g.addNode(new Knode("x"));
        assertThat(g.hasItem("x")).isTrue();
        assertThat(g.hasItem("y")).isFalse();
    }
}
