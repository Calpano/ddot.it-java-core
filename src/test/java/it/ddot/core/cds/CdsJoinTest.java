package it.ddot.core.cds;

import it.ddot.core.cds.CdsQuery.Pattern;
import it.ddot.core.cds.CdsQuery.Query;
import it.ddot.core.store.TripleStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static it.ddot.core.cds.Cds.*;
import static it.ddot.core.cds.CdsQuery.*;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The join order is the query layer's to choose, and must never change an answer: each query here
 * against a reference that cannot depend on order -- every pattern evaluated alone, the solutions
 * joined by what they share.
 */
class CdsJoinTest {

    private static final TripleStore<?> STORE = CdsCorpora.joins(9, 2_000);

    /** Queries whose patterns, in the order written, start with the big one. */
    static final Map<String, Query> QUERIES = new LinkedHashMap<>();

    static {
        QUERIES.put("big type, rare tag", where(t("?x", HAS_TYPE, "animal"), t("?x", HAS_TAG, "rare")));
        QUERIES.put("rare tag, big type", where(t("?x", HAS_TAG, "rare"), t("?x", HAS_TYPE, "animal")));
        QUERIES.put("hub, link, rare", where(t("?x", LINKS_TO, "x0"), t("?x", LINKS_TO, "?y"), t("?y", HAS_TAG, "rare")));
        QUERIES.put("related hub, small type", where(t("?x", RELATED, "x0"), t("?x", HAS_TYPE, "bird 3")));
        QUERIES.put("two links, rare", where(t("x5", seq(rel(LINKS_TO), rel(LINKS_TO)), "?z"), t("?z", HAS_TAG, "rare")));
        QUERIES.put("mammals linking birds", where(t("?a", HAS_TYPE, "mammal"), t("?a", LINKS_TO, "?b"), t("?b", HAS_TYPE, "bird 3")));
        QUERIES.put("a variable relation", where(t("?x", HAS_TAG, "rare"), t("?x", "?p", "x0")));
        QUERIES.put("a path both ways", where(t("?x", HAS_TAG, "rare"), t("?x", alt(rel(LINKS_TO), inv(rel(LINKS_TO))), "?y"),
                t("?y", HAS_TYPE, "fish")));
    }

    @Test
    void theOrderChosenChangesNoAnswer() {
        CdsEngine engine = new CdsEngine(STORE);
        CdsQueryEngine query = new CdsQueryEngine(engine);
        for (Map.Entry<String, Query> entry : QUERIES.entrySet()) {
            Set<Map<String, String>> expected = reference(query, entry.getValue().where());
            assertWithMessage(entry.getKey()).that(new HashSet<>(query.select(entry.getValue()))).isEqualTo(expected);
        }
    }

    @Test
    void theOracleGivesTheSameAnswers() {
        CdsQueryEngine engine = new CdsQueryEngine(new CdsEngine(CdsCorpora.joins(9, 300)));
        CdsQueryEngine oracle = new CdsQueryEngine(new CdsInferenceEngine(CdsCorpora.joins(9, 300), RuleSet.ALL));
        for (Map.Entry<String, Query> entry : QUERIES.entrySet()) {
            assertWithMessage(entry.getKey()).that(new HashSet<>(engine.select(entry.getValue())))
                    .isEqualTo(new HashSet<>(oracle.select(entry.getValue())));
        }
    }

    /** A test with both ends bound is answered as holds answers it, not by listing one end. */
    @Test
    void aTestIsAnsweredAsHoldsAnswersIt() {
        CdsEngine engine = new CdsEngine(STORE);
        assertThat(engine.estimate("x5", LINKS_TO, "x0")).isEqualTo(1);
        assertThat(engine.estimate(null, LINKS_TO, null)).isEqualTo(-1);
        assertThat(engine.estimate(null, HAS_TYPE, "animal")).isAtLeast(engine.estimate(null, HAS_TYPE, "bird 3"));
        for (String x : List.of("x1", "x2", "x3", "x0")) {
            boolean holds = engine.holds(x, RELATED, "x0");
            assertThat(engine.match(x, RELATED, "x0").isEmpty()).isEqualTo(!holds);
        }
    }

    /** Every pattern alone, then joined on the variables they share, in the order written. */
    private static Set<Map<String, String>> reference(CdsQueryEngine query, List<Pattern> patterns) {
        List<Map<String, String>> solutions = List.of(Map.of());
        for (Pattern pattern : patterns) {
            List<Map<String, String>> alone = query.select(List.of(pattern));
            List<Map<String, String>> joined = new ArrayList<>();
            for (Map<String, String> left : solutions) {
                for (Map<String, String> right : alone) {
                    if (!agree(left, right)) continue;
                    Map<String, String> both = new LinkedHashMap<>(left);
                    both.putAll(right);
                    joined.add(both);
                }
            }
            solutions = joined;
        }
        return new HashSet<>(solutions);
    }

    private static boolean agree(Map<String, String> left, Map<String, String> right) {
        for (Map.Entry<String, String> entry : right.entrySet()) {
            String value = left.get(entry.getKey());
            if (value != null && !value.equals(entry.getValue())) return false;
        }
        return true;
    }
}
