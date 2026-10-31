package it.ddot.core.cds;

import it.ddot.core.store.TripleStore;

/** The oracle on whole corpora: it has no oracle of its own, so only the properties apply. */
class CdsInferenceEngineCorpusTest extends CdsCorpusContract {

    @Override
    protected CdsReasoner create(TripleStore<?> store, RuleSet rules) {
        return new CdsInferenceEngine(store, rules);
    }
}
