package it.ddot.core.cds;

import it.ddot.core.cds.CdsCorpora.Corpus;
import it.ddot.core.cds.CdsProbes.Budget;
import it.ddot.core.cds.CdsProbes.Probe;
import it.ddot.core.store.IFact;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * {@link CdsEngine} grown fact by fact against one built at once: a model is never built anew while
 * the store only grows, so however the facts arrive -- a relation's meaning after its data, an alias
 * after what is said about it, two concepts made one after both have triples -- growing must end
 * where building ends.
 */
class CdsGrowthTest {

    static Stream<Corpus> corpora() {
        return Stream.concat(CdsCorpora.synthetic(1).stream(), CdsCorpora.synthetic(2).stream());
    }

    /** In the store's order, in batches of a few facts, and now and then of many. */
    @ParameterizedTest
    @MethodSource("corpora")
    void growingInOrderEndsWhereBuildingEnds(Corpus corpus) {
        grow(corpus, corpus.store().get().all(), 1);
    }

    /** Backwards: the meta statements a generator writes first now come after the data they govern. */
    @ParameterizedTest
    @MethodSource("corpora")
    void growingBackwardsEndsWhereBuildingEnds(Corpus corpus) {
        List<IFact> facts = new ArrayList<>(corpus.store().get().all());
        Collections.reverse(facts);
        grow(corpus, facts, 2);
    }

    @ParameterizedTest
    @MethodSource("corpora")
    void growingShuffledEndsWhereBuildingEnds(Corpus corpus) {
        List<IFact> facts = new ArrayList<>(corpus.store().get().all());
        Collections.shuffle(facts, new Random(3));
        grow(corpus, facts, 3);
    }

    private static void grow(Corpus corpus, List<? extends IFact> facts, long seed) {
        MemoryTripleStore grown = new MemoryTripleStore();
        CdsEngine growing = new CdsEngine(grown, RuleSet.ALL);
        Random random = new Random(seed);
        int i = 0;
        while (i < facts.size()) {
            // Mostly a few facts at a time, now and then a fifth of them.
            int size = 1 + random.nextInt(random.nextInt(4) == 0 ? Math.max(1, facts.size() / 5) : 4);
            for (int end = Math.min(facts.size(), i + size); i < end; i++) {
                grown.add(facts.get(i).subject(), facts.get(i).relation(), facts.get(i).object());
            }
            growing.isRelation("anything"); // brings the model up to date
        }
        MemoryTripleStore built = new MemoryTripleStore();
        for (IFact fact : facts) built.add(fact.subject(), fact.relation(), fact.object());
        List<Probe> probes = CdsProbes.draw(built, seed, new Budget(1_000, 30, 5, 300, true));
        List<String> differences = CdsProbes.differences(new CdsEngine(built, RuleSet.ALL), growing, probes, 20);
        assertWithMessage(corpus.name()).that(differences).isEmpty();
        assertWithMessage(corpus.name() + ", built anew").that(growing.builds()).isEqualTo(1);
    }
}
