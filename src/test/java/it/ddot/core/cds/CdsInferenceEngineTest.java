package it.ddot.core.cds;

import it.ddot.core.store.TripleStore;

/** The reference engine against the contract -- the oracle has to pass it first. */
class CdsInferenceEngineTest extends CdsReasonerContract {

    @Override
    protected CdsReasoner create(TripleStore<?> store, RuleSet rules) {
        return new CdsInferenceEngine(store, rules);
    }
}
