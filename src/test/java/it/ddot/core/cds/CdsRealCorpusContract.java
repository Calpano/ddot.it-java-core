package it.ddot.core.cds;

import it.ddot.core.cds.CdsProbes.Budget;
import it.ddot.core.cds.CdsProbes.Probe;
import it.ddot.core.store.TripleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link CdsCorpusContract} and {@link CdsConcurrencyContract} on the real corpora: what adir makes of
 * a checked-out project.
 *
 * <p>Reading a project takes adir, so nothing in this module runs this contract. adir's tests do, with
 * a {@link CdsCorpora.RealCorpusReader} registered; a project that is not checked out is skipped.
 */
abstract class CdsRealCorpusContract {

    protected abstract CdsReasoner create(TripleStore<?> store, RuleSet rules);

    static Stream<String> real() {
        return CdsCorpora.REAL.stream();
    }

    @ParameterizedTest
    @MethodSource("real")
    void propertiesHoldOnRealCorpora(String project) {
        assumeTrue(CdsCorpora.exists(project), "no checkout of " + project);
        TripleStore<?> store = CdsCorpora.real(project);
        List<Probe> probes = CdsProbes.draw(store, 42, Budget.REAL);
        assertWithMessage(project).that(CdsProbes.violations(create(store, RuleSet.ALL), store, probes, RuleSet.ALL)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("real")
    void agreesWithTheOracleOnRealCorpora(String project) {
        assumeTrue(CdsCorpora.exists(project), "no checkout of " + project);
        TripleStore<?> store = CdsCorpora.real(project);
        CdsCorpusContract.agreeWithTheOracle(project, create(store, RuleSet.ALL), store, RuleSet.ALL, Budget.REAL);
    }

    @Test
    void manyQuestionsAtOnceOnTheAdirGraph() throws Exception {
        assumeTrue(CdsCorpora.exists("adir"), "no checkout of adir");
        TripleStore<?> store = CdsCorpora.real("adir");
        CdsReasoner candidate = create(store, RuleSet.ALL);
        assumeTrue(!(candidate instanceof CdsInferenceEngine), "the oracle does not claim to be thread-safe");
        CdsConcurrencyContract.allAgree("adir", candidate, store,
                CdsProbes.draw(store, 5, new Budget(3_000, 0, 0, 300, false)));
    }
}
