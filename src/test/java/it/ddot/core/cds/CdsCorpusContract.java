package it.ddot.core.cds;

import it.ddot.core.cds.CdsCorpora.Corpus;
import it.ddot.core.cds.CdsProbes.Budget;
import it.ddot.core.cds.CdsProbes.Probe;
import it.ddot.core.store.TripleStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * An engine on whole corpora: the properties every engine must have, and -- for any engine but the
 * oracle -- the same answers as the oracle, probe for probe.
 *
 * <p>The real corpora are in {@link CdsRealCorpusContract}.
 */
abstract class CdsCorpusContract {

    protected abstract CdsReasoner create(TripleStore<?> store, RuleSet rules);

    static Stream<Corpus> synthetic() {
        return CdsCorpora.synthetic(10).stream();
    }

    @ParameterizedTest
    @MethodSource("synthetic")
    void propertiesHoldOnSyntheticCorpora(Corpus corpus) {
        TripleStore<?> store = corpus.store().get();
        for (RuleSet rules : RuleSet.values()) {
            List<Probe> probes = CdsProbes.draw(store, 42, Budget.SMALL);
            assertWithMessage(corpus + " " + rules)
                    .that(CdsProbes.violations(create(store, rules), store, probes, rules)).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("synthetic")
    void agreesWithTheOracleOnSyntheticCorpora(Corpus corpus) {
        TripleStore<?> store = corpus.store().get();
        for (RuleSet rules : RuleSet.values()) {
            agreeWithTheOracle(corpus.name() + " " + rules, store, rules, Budget.SMALL);
        }
    }

    /**
     * A store that grows while the engine answers, as adir's graph does while it is read: after each
     * batch the engine -- kept, not made anew -- must answer as a fresh oracle would. One fact is
     * taken away halfway, so an engine that grows must also notice when the store did not only grow.
     */
    @ParameterizedTest
    @MethodSource("synthetic")
    void agreesWithTheOracleWhileGrowing(Corpus corpus) {
        List<? extends it.ddot.core.store.IFact> facts = corpus.store().get().all();
        List<Probe> probes = CdsProbes.draw(corpus.store().get(), 3, new Budget(300, 10, 3, 50, true));
        MemoryTripleStore store = new MemoryTripleStore();
        CdsReasoner candidate = create(store, RuleSet.ALL);
        int batches = 8;
        for (int b = 1; b <= batches; b++) {
            for (int i = facts.size() * (b - 1) / batches; i < facts.size() * b / batches; i++) {
                store.add(facts.get(i).subject(), facts.get(i).relation(), facts.get(i).object());
            }
            if (b == batches / 2) store.remove(store.all().get(store.size() / 2));
            List<String> differences = CdsProbes.differences(new CdsInferenceEngine(store, RuleSet.ALL), candidate, probes, 10);
            assertWithMessage(corpus + " after batch " + b).that(differences).isEmpty();
        }
    }

    /**
     * The oracle's answers, by corpus and rule set, for every engine's test class in this JVM: the
     * questions are the same for each, and the oracle is slow at some of them.
     */
    private static final java.util.Map<String, List<Object>> ORACLE = new java.util.concurrent.ConcurrentHashMap<>();

    private void agreeWithTheOracle(String what, TripleStore<?> store, RuleSet rules, Budget budget) {
        agreeWithTheOracle(what, create(store, rules), store, rules, budget);
    }

    static void agreeWithTheOracle(String what, CdsReasoner candidate, TripleStore<?> store, RuleSet rules,
                                   Budget budget) {
        if (candidate instanceof CdsInferenceEngine) return; // the oracle agrees with itself
        List<Probe> probes = CdsProbes.draw(store, 7, budget);
        List<Object> expected = ORACLE.computeIfAbsent(what + " " + store.size(),
                key -> CdsProbes.answers(new CdsInferenceEngine(store, rules), probes));
        List<String> differences = CdsProbes.differences(expected, candidate, probes, 20);
        assertWithMessage(what).that(differences).isEmpty();
    }
}
