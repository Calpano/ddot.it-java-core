package it.ddot.core.cds;

import it.ddot.core.store.TripleStore;

class CdsEngineTest extends CdsReasonerContract {

    @Override
    protected CdsReasoner create(TripleStore<?> store, RuleSet rules) {
        return new CdsEngine(store, rules);
    }
}
