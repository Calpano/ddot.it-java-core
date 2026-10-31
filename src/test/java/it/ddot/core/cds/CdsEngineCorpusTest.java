package it.ddot.core.cds;

import it.ddot.core.store.TripleStore;

class CdsEngineCorpusTest extends CdsCorpusContract {

    @Override
    protected CdsReasoner create(TripleStore<?> store, RuleSet rules) {
        return new CdsEngine(store, rules);
    }
}
