package it.ddot.core.cds;

import it.ddot.core.store.IFact;
import it.ddot.core.store.TripleStore;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Answers "is there an {@code x p y}?" over a {@link TripleStore}, under the CDS vocabulary and
 * rules.
 *
 * <p>The design is {@code doc/cds-inf.adoc}; the vocabulary and rules r01–r18 are
 * <a href="https://ddot.it/spec/ddot-it-cds.html">CDS &amp; Standards Alignment</a>, plus the
 * ddot.it additions: {@code replaces} is transitive, a {@code has subtype} cycle is one concept, and
 * typing and subtyping create types.
 *
 * <p>The store holds only what was stated. Whatever the rules would derive is worked out when asked
 * -- so {@code a has subtype b} answers {@code a related b} without a {@code related} fact ever
 * existing. Any store will do: a {@link MemoryTripleStore}, or the {@code KnowledgeGraph}.
 *
 * <p>This is the reference implementation: written to be read against the spec, not to be fast.
 * {@link CdsEngine} is the fast one, and is checked against this -- see {@code CdsReasonerContract}.
 *
 * <h2>Change</h2>
 * The engine compiles what it knows from the store's facts, and compiles again whenever the store's
 * {@link TripleStore#revision} has moved. That is truth maintenance by recomputation: a conclusion
 * whose last support went away is simply not derived again. Incremental maintenance is an open
 * question in the design.
 */
public final class CdsInferenceEngine implements CdsReasoner {

    private final TripleStore<?> store;
    private final RuleSet rules;
    private CdsInferenceModel model;
    private long compiledAt;

    /** Over a new, empty {@link MemoryTripleStore}. */
    public CdsInferenceEngine() {
        this(RuleSet.ALL);
    }

    /** Over a new, empty {@link MemoryTripleStore}. */
    public CdsInferenceEngine(RuleSet rules) {
        this(new MemoryTripleStore(), rules);
    }

    public CdsInferenceEngine(TripleStore<?> store) {
        this(store, RuleSet.ALL);
    }

    public CdsInferenceEngine(TripleStore<?> store, RuleSet rules) {
        this.store = store;
        this.rules = rules;
    }

    @Override
    public RuleSet rules() {
        return rules;
    }

    @Override
    public TripleStore<?> store() {
        return store;
    }

    /** Adds a statement to the store, for building one up in a line. */
    @Override
    public CdsInferenceEngine add(String subject, String relation, String object) {
        store.add(subject, relation, object);
        return this;
    }

    @Override
    public boolean holds(String subject, String relation, String object) {
        return why(subject, relation, object).isPresent();
    }

    /**
     * The facts one derivation rests on: the store's own, with their provenance, and the axioms,
     * including the alias, inverse and {@code has subtype} statements it went through. Empty when
     * the statement does not hold.
     */
    @Override
    public Optional<Set<IFact>> why(String subject, String relation, String object) {
        return Optional.ofNullable(model().why(subject, relation, object));
    }

    /** Whether {@code sub} lies under {@code sup} in the relation hierarchy, reflexively. */
    @Override
    public List<Triple> match(@Nullable String subject, @Nullable String relation, @Nullable String object) {
        return model().match(subject, relation, object);
    }

    @Override
    public boolean isSubRelation(String sub, String sup) {
        return model().isSubRelation(sub, sup);
    }

    /** The name a concept or relation goes by; a relation's direction is kept, so this may start with {@code ^}. */
    @Override
    public String canonical(String name) {
        return model().canonical(name);
    }

    /** The form a fact is stored in: canonical names, in the canonical direction of its relation. */
    @Override
    public Triple canonicalForm(IFact fact) {
        return model().canonicalForm(fact);
    }

    @Override
    public boolean isRelation(String name) {
        return model().isRelation(name);
    }

    @Override
    public boolean isType(String name) {
        return model().isType(name);
    }

    @Override
    public boolean isSymmetric(String relation) {
        return model().isSymmetric(relation);
    }

    @Override
    public boolean isTransitive(String relation) {
        return model().isTransitive(relation);
    }

    private synchronized CdsInferenceModel model() {
        long revision = store.revision();
        if (model == null || revision != compiledAt) {
            // The revision is taken before the facts, so a change made while reading them is
            // noticed on the next question rather than lost.
            compiledAt = revision;
            List<IFact> all = new ArrayList<>(Cds.axioms());
            all.addAll(store.all());
            model = new CdsInferenceModel(rules, all);
        }
        return model;
    }
}
