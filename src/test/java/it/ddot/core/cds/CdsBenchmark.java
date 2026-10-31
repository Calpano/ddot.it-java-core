package it.ddot.core.cds;

import it.ddot.core.cds.CdsCorpora.Corpus;
import it.ddot.core.cds.CdsProbes.Budget;
import it.ddot.core.cds.CdsProbes.Kind;
import it.ddot.core.cds.CdsProbes.Probe;
import it.ddot.core.store.TripleStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every engine on every corpus: how long it takes to compile, and to answer each kind of question.
 *
 * <p>Off unless asked for, since it takes minutes: {@code mvn test -Dtest=CdsBenchmark -Dcds.bench}.
 * {@code -Dcds.engines=oracle,engine} picks engines, {@code -Dcds.corpora=adir,…} picks corpora by name
 * prefix. The table goes to standard out and to {@code target/cds-bench.txt}; the numbers worth
 * keeping go to {@code doc/cds-inf-perf.adoc} by hand, with what was tried.
 *
 * <p>Each measurement is the best of a few runs, after one to warm up, and the engine is made new
 * for each compile so the compile is measured cold. A section that runs out of its time budget is
 * measured on the probes it got through.
 */
class CdsBenchmark {

    /**
     * Every engine there is. An engine being tried is added here, and measured against the rest;
     * the ones tried before {@link CdsEngine} are in the history of this directory, and in
     * {@code doc/cds-inf-perf.adoc}.
     */
    static final Map<String, CdsReasoner.Factory> ENGINES = new LinkedHashMap<>();

    static {
        ENGINES.put("oracle", CdsInferenceEngine::new);
        ENGINES.put("engine", CdsEngine::new);
    }

    private static final int RUNS = 3;

    /**
     * Solutions per query. A join such as {@code x p ?y . ?y related ?z} over a long type chain has
     * millions, and building those measures the answer, not the engine.
     */
    private static final int LIMIT = 1_000;

    @Test
    void measure() throws IOException {
        assumeTrue(System.getProperty("cds.bench") != null, "set -Dcds.bench to run the benchmark");
        List<Corpus> corpora = new ArrayList<>(CdsCorpora.synthetic(10));
        corpora.addAll(CdsCorpora.synthetic(100));
        for (String project : CdsCorpora.REAL) {
            if (CdsCorpora.exists(project)) corpora.add(new Corpus(project, () -> CdsCorpora.real(project)));
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%-16s %-10s %8s %10s %10s %8s %10s %10s %10s %9s %9s %9s",
                "corpus", "engine", "facts", "compile ms", "holds µs", "true %", "match ms", "query ms", "vocab µs",
                "heap MB", "grow ms", "par µs"));
        for (Corpus corpus : corpora) {
            if (!selected("cds.corpora", corpus.name())) continue;
            TripleStore<?> store = corpus.store().get();
            List<Probe> probes = CdsProbes.draw(store, 11, Budget.BENCH);
            for (Map.Entry<String, CdsReasoner.Factory> engine : ENGINES.entrySet()) {
                if (!selected("cds.engines", engine.getKey())) continue;
                String line = measure(corpus.name(), engine.getKey(), engine.getValue(), store, probes);
                System.out.println(line);
                lines.add(line);
            }
        }
        lines.forEach(System.out::println);
        Files.write(Path.of("target", "cds-bench.txt"), lines);
    }

    private static String measure(String corpus, String name, CdsReasoner.Factory factory, TripleStore<?> store, List<Probe> probes) {
        double compile = Double.MAX_VALUE;
        for (int run = 0; run <= RUNS; run++) {
            CdsReasoner fresh = factory.create(store, RuleSet.ALL);
            long started = System.nanoTime();
            fresh.isRelation(Cds.RELATED);
            if (run > 0) compile = Math.min(compile, millis(started));
        }
        CdsReasoner engine = factory.create(store, RuleSet.ALL);
        engine.isRelation(Cds.RELATED);
        List<Probe> holds = only(probes, p -> p.kind() == Kind.HOLDS);
        List<Probe> matches = only(probes, p -> p.kind().name().startsWith("MATCH"));
        List<Probe> queries = only(probes, p -> p.kind() == Kind.QUERY);
        List<Probe> vocabulary = only(probes, p -> p.kind() != Kind.HOLDS && p.kind() != Kind.QUERY && !p.kind().name().startsWith("MATCH"));
        long yes = holds.stream().limit(2_000).filter(p -> (Boolean) p.answer(engine)).count();
        return String.format(Locale.ROOT, "%-16s %-10s %8d %10.1f %10.2f %8.1f %10.2f %10.2f %10.2f %9.1f %9.0f %9.2f",
                corpus, name, store.size(), compile,
                perProbe(engine, holds) * 1000, 100.0 * yes / Math.min(2_000, holds.size()),
                slowAt(name, store) ? Double.NaN : perProbe(engine, matches),
                slowAt(name, store) ? Double.NaN : perProbe(engine, queries),
                perProbe(engine, vocabulary) * 1000,
                heapMegabytes(factory, store),
                grow(factory, store, holds.getFirst()),
                name.equals("oracle") ? Double.NaN : parallelPerProbe(engine, holds) * 1000);
    }

    private static final int THREADS = 8;

    /**
     * Milliseconds per probe with the probes shared among {@value #THREADS} threads: wall time over
     * probes. An engine that serialises its questions gains nothing from the threads.
     */
    private static double parallelPerProbe(CdsReasoner engine, List<Probe> probes) {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS);
        try {
            double best = Double.MAX_VALUE;
            for (int run = 0; run <= RUNS; run++) {
                long started = System.nanoTime();
                List<java.util.concurrent.Future<?>> parts = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    int from = probes.size() * t / THREADS;
                    int to = probes.size() * (t + 1) / THREADS;
                    parts.add(pool.submit(() -> {
                        for (Probe probe : probes.subList(from, to)) probe.answer(engine);
                    }));
                }
                for (var part : parts) part.get();
                if (run > 0) best = Math.min(best, millis(started));
            }
            return best / probes.size();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * How much heap a compiled engine keeps, over and above the store it reads: the used heap after
     * a full collection with the engine alive, minus the same before it was made. Approximate --
     * the JVM decides what a collection frees -- but repeatable to a megabyte or so.
     */
    private static double heapMegabytes(CdsReasoner.Factory factory, TripleStore<?> store) {
        long before = usedAfterCollecting();
        CdsReasoner engine = factory.create(store, RuleSet.ALL);
        engine.isRelation(Cds.RELATED);
        long after = usedAfterCollecting();
        java.lang.ref.Reference.reachabilityFence(engine);
        return (after - before) / 1e6;
    }

    private static long usedAfterCollecting() {
        Runtime runtime = Runtime.getRuntime();
        long used = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            System.gc();
            used = Math.min(used, runtime.totalMemory() - runtime.freeMemory());
        }
        return used;
    }

    /**
     * What a graph that is still being read costs: the corpus added to an empty store in
     * {@value #BATCHES} batches, as adir's walker adds a file at a time, with one question after
     * each batch. An engine that recompiles everything on every change pays for the whole graph
     * {@value #BATCHES} times.
     */
    private static double grow(CdsReasoner.Factory factory, TripleStore<?> source, Probe question) {
        List<? extends it.ddot.core.store.IFact> facts = source.all();
        MemoryTripleStore store = new MemoryTripleStore();
        CdsReasoner engine = factory.create(store, RuleSet.ALL);
        long started = System.nanoTime();
        int batch = Math.max(1, facts.size() / BATCHES);
        for (int i = 0; i < facts.size(); i++) {
            var fact = facts.get(i);
            store.add(fact.subject(), fact.relation(), fact.object());
            if ((i + 1) % batch == 0 || i == facts.size() - 1) question.answer(engine);
        }
        double took = millis(started);
        if (engine instanceof CdsEngine growing) {
            System.out.println("GROW " + source.size() + " facts: built " + growing.builds() + " times in " + BATCHES
                    + " batches");
        }
        return took;
    }

    private static final int BATCHES = 50;

    /**
     * Whether an engine's patterns are too slow to measure on a corpus this size. The oracle's
     * {@code match} tests every node connected to the bound end, and a query calls it once per step
     * of a path, so a single probe can run for hours -- longer than any budget between probes can
     * stop. Its patterns are measured on the small corpora only.
     */
    private static boolean slowAt(String engine, TripleStore<?> store) {
        return engine.equals("oracle") && store.size() > 3_000;
    }

    /** No section may take longer than this; a slow engine is measured on the probes it got through. */
    private static final double BUDGET_MS = 20_000;

    /**
     * Milliseconds per probe, best of the runs after one to warm up. When the warm-up alone runs out
     * of budget, its own mean is the answer: an engine that slow needs no second decimal.
     */
    private static double perProbe(CdsReasoner engine, List<Probe> probes) {
        long started = System.nanoTime();
        int answered = 0;
        for (Probe probe : probes) {
            probe.answer(engine, LIMIT);
            answered++;
            if (millis(started) > BUDGET_MS) return millis(started) / answered;
        }
        double best = Double.MAX_VALUE;
        for (int run = 0; run < RUNS; run++) {
            started = System.nanoTime();
            for (Probe probe : probes) probe.answer(engine, LIMIT);
            best = Math.min(best, millis(started));
        }
        return best / probes.size();
    }

    private static List<Probe> only(List<Probe> probes, Predicate<Probe> which) {
        return probes.stream().filter(which).toList();
    }

    private static boolean selected(String property, String name) {
        String wanted = System.getProperty(property);
        if (wanted == null || wanted.isBlank()) return true;
        for (String prefix : wanted.split(",")) if (name.startsWith(prefix.trim())) return true;
        return false;
    }

    private static double millis(long started) {
        return (System.nanoTime() - started) / 1e6;
    }
}
