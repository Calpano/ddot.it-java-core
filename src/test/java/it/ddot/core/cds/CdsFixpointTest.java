package it.ddot.core.cds;

import it.ddot.core.cds.CdsCorpora.Corpus;
import it.ddot.core.cds.CdsProbes.Budget;
import it.ddot.core.cds.CdsProbes.Kind;
import it.ddot.core.cds.CdsProbes.Probe;
import it.ddot.core.store.TripleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static it.ddot.core.cds.Cds.*;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The oracle and the engine against {@link CdsFixpointOracle}: the rules applied literally, to
 * every triple, derived ones included.
 *
 * <p>Only the questions the fixpoint can answer: whether a triple holds, and the questions about
 * the vocabulary that are triples too. The corpora are small, since the fixpoint stores all it
 * derives.
 */
class CdsFixpointTest {

    private static final EnumSet<Kind> ANSWERED = EnumSet.of(Kind.HOLDS, Kind.SUB, Kind.IS_TYPE, Kind.IS_RELATION,
            Kind.IS_SYMMETRIC, Kind.IS_TRANSITIVE);

    static Stream<Corpus> corpora() {
        return CdsCorpora.synthetic(2).stream();
    }

    // ---- the fixpoint itself -------------------------------------------------------------------

    @Test
    void theFixpointAppliesTheRules() {
        CdsFixpointOracle cds = fixpoint(
                "animal", HAS_SUBTYPE, "dog",
                "dog", HAS_SUBTYPE, "poodle",
                "rex", HAS_TYPE, "poodle",
                "car", HAS_TAG, "red",
                "car", HAS_PART, "door",
                "door", HAS_PART, "handle",
                "p", HAS_INVERSE, "q",
                "q", HAS_INVERSE, "r",
                "x", "r", "y");
        assertThat(cds.holds("rex", HAS_TYPE, "animal")).isTrue(); // r04
        assertThat(cds.holds("animal", RELATED, "dog")).isTrue(); // r05
        assertThat(cds.holds("poodle", HAS_SUPERTYPE, "animal")).isTrue(); // r01, r02
        assertThat(cds.holds("handle", HAS_TAG, "red")).isTrue(); // r18
        assertThat(cds.holds("p", SAME_AS, "r")).isTrue(); // r12
        assertThat(cds.isType("dog")).isTrue();
        assertThat(cds.isRelation("p")).isTrue();
        assertThat(cds.isSubRelation(HAS_TYPE, HAS_TAG)).isTrue();
        assertThat(cds.holds("dog", LINKS_TO, "animal")).isFalse();
    }

    /** The convention's {@code a has inverse ^a} is no statement about the alias for r06 to hand on. */
    @Test
    void theFixpointKeepsAnAliasOfARelationOneWay() {
        CdsFixpointOracle cds = fixpoint(
                IS_LINKED_FROM, HAS_SUBTYPE, "r",
                "a", IS_ALIAS_OF, "r",
                "x", "a", "y");
        assertThat(cds.holds("x", "r", "y")).isTrue();
        assertThat(cds.holds("a", SAME_AS, "r")).isFalse();
        assertThat(cds.holds("r", HAS_INVERSE, "^a")).isFalse();
        assertThat(cds.holds(RELATED, HAS_SUBTYPE, "a")).isTrue();
    }

    /** The gap the fixpoint exists for: a chain whose first step type inheritance derived. */
    @Test
    void theFixpointFollowsChainsOverDerivedTriples() {
        CdsFixpointOracle cds = fixpoint(
                "is classified as", HAS_TYPE, TRANSITIVE_RELATION,
                "is classified as", HAS_SUBTYPE, HAS_TYPE,
                "rex", HAS_TYPE, "poodle",
                "poodle", HAS_SUPERTYPE, "dog",
                "dog", "is classified as", "mammal");
        assertThat(cds.holds("rex", HAS_TYPE, "dog")).isTrue();
        assertThat(cds.holds("rex", "is classified as", "dog")).isTrue();
        assertThat(cds.holds("rex", "is classified as", "mammal")).isTrue();
    }

    // ---- the oracle and the engine against it ----------------------------------------------------

    @ParameterizedTest
    @MethodSource("corpora")
    void theOracleAgreesWithTheFixpoint(Corpus corpus) {
        agree(corpus, new CdsInferenceEngine(corpus.store().get(), RuleSet.ALL), corpus.store().get());
    }

    @ParameterizedTest
    @MethodSource("corpora")
    void theEngineAgreesWithTheFixpoint(Corpus corpus) {
        TripleStore<?> store = corpus.store().get();
        agree(corpus, new CdsEngine(store, RuleSet.ALL), store);
    }

    // ---- the hierarchy, every question ----------------------------------------------------------

    /**
     * The relations whose triples are the hierarchy, and the two sides of the root that r17 fills: asked
     * between every pair of names, not a sample. Sampled probes missed that the engines answered a
     * sub-relation edge r17 derives only through {@link CdsReasoner#isSubRelation}, never as a triple --
     * not even {@code has tag ..has subtype.. has type}, which the vocabulary alone entails.
     */
    private static final List<String> HIERARCHY = List.of(HAS_SUBTYPE, HAS_SUPERTYPE, RELATED, LINKS_TO, IS_LINKED_FROM);

    @Test
    void theVocabularyAloneAgrees() {
        MemoryTripleStore store = new MemoryTripleStore();
        assertThat(hierarchyDifferences(new CdsInferenceEngine(store, RuleSet.ALL), store)).isEmpty();
        assertThat(hierarchyDifferences(new CdsEngine(store, RuleSet.ALL), store)).isEmpty();
    }

    @Test
    void aRelationOnBothSidesAgrees() {
        MemoryTripleStore store = new MemoryTripleStore();
        store.add(LINKS_TO, HAS_SUBTYPE, "depends on");
        store.add(LINKS_TO, HAS_SUBTYPE, "required by");
        store.add("depends on", HAS_INVERSE, "required by");
        store.add("x", "depends on", "y");
        assertThat(hierarchyDifferences(new CdsInferenceEngine(store, RuleSet.ALL), store)).isEmpty();
        assertThat(hierarchyDifferences(new CdsEngine(store, RuleSet.ALL), store)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("corpora")
    void theOracleAgreesOnTheWholeHierarchy(Corpus corpus) {
        TripleStore<?> store = corpus.store().get();
        assertWithMessage(corpus.name()).that(hierarchyDifferences(new CdsInferenceEngine(store, RuleSet.ALL), store)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("corpora")
    void theEngineAgreesOnTheWholeHierarchy(Corpus corpus) {
        TripleStore<?> store = corpus.store().get();
        assertWithMessage(corpus.name()).that(hierarchyDifferences(new CdsEngine(store, RuleSet.ALL), store)).isEmpty();
    }

    /** Every {@link #HIERARCHY} question between the relations and types of the vocabulary and the store. */
    private static List<String> hierarchyDifferences(CdsReasoner candidate, TripleStore<?> store) {
        CdsFixpointOracle fixpoint = new CdsFixpointOracle(store, RuleSet.ALL);
        java.util.Set<String> names = new java.util.TreeSet<>(Cds.names());
        for (var fact : store.all()) {
            for (String name : List.of(fact.subject(), fact.relation(), fact.object())) {
                if (fixpoint.isRelation(name) || fixpoint.isType(name)) names.add(name);
            }
        }
        List<String> differences = new ArrayList<>();
        for (String a : names) {
            for (String relation : HIERARCHY) {
                for (String b : names) {
                    boolean expected = fixpoint.holds(a, relation, b);
                    boolean actual = candidate.holds(a, relation, b);
                    if (expected != actual && differences.size() < 25) {
                        differences.add(a + " .." + relation + ".. " + b + ": fixpoint " + expected + ", "
                                + candidate.getClass().getSimpleName() + " " + actual);
                    }
                }
            }
        }
        return differences;
    }

    private static void agree(Corpus corpus, CdsReasoner candidate, TripleStore<?> store) {
        CdsFixpointOracle fixpoint = new CdsFixpointOracle(store, RuleSet.ALL);
        List<Probe> probes = CdsProbes.draw(store, 13, new Budget(1_500, 0, 0, 400, false)).stream()
                .filter(probe -> ANSWERED.contains(probe.kind()))
                .toList();
        List<String> differences = new ArrayList<>();
        // Every name, not a sample: whether it is a type or a relation is cheap to ask, and a
        // sample missed a type that only an alias made one.
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (var fact : store.all()) {
            names.add(fact.subject());
            names.add(fact.object());
        }
        for (String name : names) {
            if (fixpoint.isType(name) != candidate.isType(name) && differences.size() < 25) {
                differences.add("IS_TYPE(" + name + "): fixpoint " + fixpoint.isType(name) + ", " + candidate.getClass().getSimpleName() + " " + candidate.isType(name));
            }
            if (fixpoint.isRelation(name) != candidate.isRelation(name) && differences.size() < 25) {
                differences.add("IS_RELATION(" + name + "): fixpoint " + fixpoint.isRelation(name) + ", " + candidate.getClass().getSimpleName() + " " + candidate.isRelation(name));
            }
        }
        for (Probe probe : probes) {
            Object expected = probe.answer(fixpoint);
            Object actual = probe.answer(candidate);
            if (!expected.equals(actual) && differences.size() < 25) {
                differences.add(probe + ": fixpoint " + expected + ", " + candidate.getClass().getSimpleName() + " " + actual);
            }
        }
        assertWithMessage(corpus + " (" + fixpoint.size() + " triples in the closure)").that(differences).isEmpty();
    }

    private static CdsFixpointOracle fixpoint(String... spo) {
        MemoryTripleStore store = new MemoryTripleStore();
        for (int i = 0; i < spo.length; i += 3) store.add(spo[i], spo[i + 1], spo[i + 2]);
        return new CdsFixpointOracle(store, RuleSet.ALL);
    }
}
