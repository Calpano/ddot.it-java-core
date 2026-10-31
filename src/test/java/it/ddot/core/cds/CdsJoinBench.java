package it.ddot.core.cds;

import it.ddot.core.cds.CdsQuery.Query;
import it.ddot.core.store.IFact;
import it.ddot.core.store.TripleStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static it.ddot.core.cds.Cds.*;
import static it.ddot.core.cds.CdsQuery.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Queries whose join order matters, timed: {@link CdsJoinTest}'s on a join corpus of 20 000 animals,
 * and some an adir user would ask, on the real graphs.
 *
 * <p>Off unless asked for: {@code mvn test -Dtest=CdsJoinBench -Dcds.joins}; {@code -Dcds.corpora}
 * picks corpora as for {@link CdsBenchmark}, {@code joins} being the synthetic one.
 */
class CdsJoinBench {

    @Test
    void measure() {
        assumeTrue(System.getProperty("cds.joins") != null, "set -Dcds.joins to run the join benchmark");
        String only = System.getProperty("cds.corpora", "");
        if (only.isEmpty() || only.contains("joins")) run("joins", CdsCorpora.joins(9, 20_000), CdsJoinTest.QUERIES);
        for (String project : CdsCorpora.REAL) {
            if (!only.isEmpty() && !only.contains(project)) continue;
            if (!CdsCorpora.exists(project)) continue;
            TripleStore<?> store = CdsCorpora.real(project);
            run(project, store, realQueries(store));
        }
    }

    /** Best of five, after one to warm up; a query that takes half a minute is measured once. */
    private static void run(String corpus, TripleStore<?> store, Map<String, Query> queries) {
        CdsEngine engine = new CdsEngine(store);
        engine.isRelation(RELATED);
        CdsQueryEngine query = new CdsQueryEngine(engine);
        for (Map.Entry<String, Query> entry : queries.entrySet()) {
            double best = Double.MAX_VALUE;
            int size = 0;
            long budget = System.nanoTime();
            for (int run = 0; run < 6; run++) {
                long started = System.nanoTime();
                size = query.select(entry.getValue()).size();
                double took = (System.nanoTime() - started) / 1e6;
                if (run > 0 || (System.nanoTime() - budget) / 1e6 > 30_000) best = Math.min(best, took);
                if ((System.nanoTime() - budget) / 1e6 > 30_000) break;
            }
            System.out.println(String.format(Locale.ROOT, "JOIN %-14s %-30s %6d %9.2f ms", corpus, entry.getKey(), size, best));
        }
    }

    /**
     * Packages, classes and directories of adir's graphs: a hub everybody uses, a package of 15 to
     * 60 members and its class that uses most, a directory of 15 to 60 entries.
     */
    private static Map<String, Query> realQueries(TripleStore<?> store) {
        Map<String, Integer> contains = new HashMap<>();
        Map<String, Integer> usedBy = new HashMap<>();
        Map<String, Integer> uses = new HashMap<>();
        Set<String> packages = new HashSet<>();
        for (IFact fact : store.all()) {
            if (fact.relation().equals("contains")) contains.merge(fact.subject(), 1, Integer::sum);
            if (fact.relation().equals("uses")) {
                usedBy.merge(fact.object(), 1, Integer::sum);
                uses.merge(fact.subject(), 1, Integer::sum);
            }
            if (fact.relation().equals(HAS_TYPE) && fact.object().equals("Java package")) packages.add(fact.subject());
        }
        Map<String, Query> out = new LinkedHashMap<>();
        if (usedBy.isEmpty() || contains.isEmpty()) return out;
        String hub = usedBy.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        String pkg = contains.entrySet().stream().filter(e -> packages.contains(e.getKey()) && e.getValue() >= 15 && e.getValue() <= 60)
                .map(Map.Entry::getKey).sorted().findFirst()
                .orElseGet(() -> contains.entrySet().stream().filter(e -> e.getValue() >= 15 && e.getValue() <= 60)
                        .map(Map.Entry::getKey).sorted().findFirst().orElseThrow());
        String cls = null;
        int most = -1;
        for (IFact fact : store.all()) {
            if (fact.subject().equals(pkg) && fact.relation().equals("contains") && uses.getOrDefault(fact.object(), 0) > most) {
                cls = fact.object();
                most = uses.getOrDefault(fact.object(), 0);
            }
        }
        String dir = contains.entrySet().stream().filter(e -> e.getKey().startsWith("/") && e.getValue() >= 15 && e.getValue() <= 60)
                .map(Map.Entry::getKey).sorted().findFirst().orElseThrow();
        out.put("R1 typed, in pkg, uses hub", where(t("?c", HAS_TYPE, "Java type"), t(pkg, "contains", "?c"), t("?c", "uses", hub)));
        out.put("R2 shared deps in pkg", where(t(cls, "uses", "?d"), t("?x", "uses", "?d"), t("?x", "is contained in", pkg)));
        out.put("R2 limit 10", where(t(cls, "uses", "?d"), t("?x", "uses", "?d"), t("?x", "is contained in", pkg)).limit(10));
        out.put("R3 related hub, in pkg", where(t("?x", RELATED, hub), t("?x", "is contained in", pkg)));
        out.put("R4 files in dir comodified", where(t("?f", HAS_TYPE, "file"), t(dir, "contains", "?f"), t("?f", "comodified with", "?g")));
        out.put("R5 uses/in, in pkg", where(t("?x", seq(rel("uses"), rel("is contained in")), pkg), t("?x", "is contained in", pkg)));
        out.put("R6 pkg-internal deps", where(t("?a", "uses", "?b"), t("?a", "is contained in", pkg), t("?b", "is contained in", pkg)));
        out.put("R7 in pkg, typed", where(t(pkg, "contains", "?c"), t("?c", HAS_TYPE, "Java type")));
        return out;
    }
}
