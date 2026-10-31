package it.ddot.core.cds;

import it.ddot.core.cds.CdsCorpora.Corpus;
import it.ddot.core.cds.CdsProbes.Budget;
import it.ddot.core.cds.CdsProbes.Probe;
import it.ddot.core.store.IFact;
import it.ddot.core.store.TripleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An engine asked from many threads at once -- as adir's server asks it, one thread per request --
 * and asked while its store grows.
 *
 * <p>Only for engines that claim to be safe. The oracle does not: it changes what it reads while
 * answering, and fails this contract -- which is how the contract was shown to have teeth.
 */
abstract class CdsConcurrencyContract {

    private static final int THREADS = 8;

    protected abstract CdsReasoner create(TripleStore<?> store, RuleSet rules);

    static Stream<Corpus> corpora() {
        return CdsCorpora.synthetic(10).stream().filter(c -> !c.name().startsWith("deep-types"));
    }

    /** Every thread asks every probe, each in its own order, and must get the oracle's answers. */
    @ParameterizedTest
    @MethodSource("corpora")
    void manyQuestionsAtOnceGetTheOraclesAnswers(Corpus corpus) throws Exception {
        TripleStore<?> store = corpus.store().get();
        allAgree(corpus.name(), store, CdsProbes.draw(store, 5, new Budget(1_000, 30, 10, 200, true)));
    }

    private void allAgree(String what, TripleStore<?> store, List<Probe> probes) throws Exception {
        allAgree(what, create(store, RuleSet.ALL), store, probes);
    }

    static void allAgree(String what, CdsReasoner candidate, TripleStore<?> store, List<Probe> probes)
            throws Exception {
        List<Object> expected = CdsProbes.answers(new CdsInferenceEngine(store, RuleSet.ALL), probes);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<List<String>>> results = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                long seed = t;
                results.add(pool.submit(() -> {
                    List<Integer> order = new ArrayList<>();
                    for (int i = 0; i < probes.size(); i++) order.add(i);
                    Collections.shuffle(order, new Random(seed));
                    List<String> wrong = new ArrayList<>();
                    for (int i : order) {
                        Object actual = probes.get(i).answer(candidate);
                        if (!expected.get(i).equals(actual) && wrong.size() < 5) {
                            wrong.add(probes.get(i) + ": oracle " + expected.get(i) + ", candidate " + actual);
                        }
                        // A name nobody used: an engine registers it while answering, which is the
                        // write most likely to race with another thread's reads.
                        String unseen = "unseen " + seed + " " + i;
                        if (candidate.holds(unseen, Cds.RELATED, probes.get(i).a()) && wrong.size() < 5) {
                            wrong.add(unseen + " is related to " + probes.get(i).a());
                        }
                        if (!candidate.canonical(unseen).equals(unseen) && wrong.size() < 5) {
                            wrong.add(unseen + " goes by " + candidate.canonical(unseen));
                        }
                    }
                    return wrong;
                }));
            }
            List<String> wrong = new ArrayList<>();
            for (Future<List<String>> result : results) wrong.addAll(result.get(5, TimeUnit.MINUTES));
            assertWithMessage(what).that(wrong).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Readers ask without pause while a writer grows the store batch by batch. Nothing may throw,
     * and once the writer is done the engine must answer as the oracle does.
     */
    @ParameterizedTest
    @MethodSource("corpora")
    void questionsWhileTheGraphGrows(Corpus corpus) throws Exception {
        List<? extends IFact> facts = corpus.store().get().all();
        List<Probe> probes = CdsProbes.draw(corpus.store().get(), 9, new Budget(300, 10, 3, 50, true));
        MemoryTripleStore store = new MemoryTripleStore();
        CdsReasoner candidate = create(store, RuleSet.ALL);
        AtomicBoolean writing = new AtomicBoolean(true);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> readers = new ArrayList<>();
            for (int t = 0; t < THREADS - 1; t++) {
                int offset = t;
                readers.add(pool.submit(() -> {
                    int i = offset;
                    while (writing.get()) {
                        try {
                            probes.get(i++ % probes.size()).answer(candidate);
                        } catch (Throwable failure) {
                            failures.add(failure);
                            return;
                        }
                    }
                }));
            }
            int batches = 20;
            for (int b = 1; b <= batches; b++) {
                for (int i = facts.size() * (b - 1) / batches; i < facts.size() * b / batches; i++) {
                    store.add(facts.get(i).subject(), facts.get(i).relation(), facts.get(i).object());
                }
                Thread.onSpinWait();
            }
            writing.set(false);
            for (Future<?> reader : readers) reader.get(5, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
        assertWithMessage(corpus + ": failures while growing").that(failures).isEmpty();
        List<String> differences = CdsProbes.differences(new CdsInferenceEngine(store, RuleSet.ALL), candidate, probes, 10);
        assertWithMessage(corpus + ": answers once grown").that(differences).isEmpty();
    }
}
