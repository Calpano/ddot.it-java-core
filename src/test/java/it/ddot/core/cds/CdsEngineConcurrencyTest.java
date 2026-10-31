package it.ddot.core.cds;

import it.ddot.core.store.TripleStore;

class CdsEngineConcurrencyTest extends CdsConcurrencyContract {

    @Override
    protected CdsReasoner create(TripleStore<?> store, RuleSet rules) {
        return new CdsEngine(store, rules);
    }
}
