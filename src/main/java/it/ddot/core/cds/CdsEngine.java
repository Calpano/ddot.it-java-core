package it.ddot.core.cds;

import it.ddot.core.store.IFact;
import it.ddot.core.store.TripleStore;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Answers "is there an {@code x p y}?" -- and patterns, and whole queries through
 * {@link CdsQueryEngine} -- over a {@link TripleStore}, under the CDS vocabulary and rules.
 *
 * <p>The design is {@code doc/cds-inf.adoc}; the vocabulary and rules r01–r18 are
 * <a href="https://ddot.it/spec/ddot-it-cds.html">CDS &amp; Standards Alignment</a>, plus the
 * ddot.it additions. {@link CdsModel} does the work, and says how; {@code doc/cds-inf-perf.adoc}
 * says how it got that way, and what it costs. {@link CdsInferenceEngine} is the reference it must
 * answer as, and the tests compare the two question by question.
 *
 * <h2>Change</h2>
 * The store holds only what was stated. Whenever the store's {@link TripleStore#revision} has moved,
 * the engine brings its model up to date before answering: when the store has only grown, the model
 * grows by the new facts, whatever they say; when it lost a fact, the model is built anew.
 *
 * <h2>Threads</h2>
 * Every question takes the read lock, so any number run at once, as adir's server asks them.
 * Bringing the model up to date takes the write lock, so it waits for the questions in flight and
 * holds back new ones; the question that found the model stale then reads it under the read lock.
 */
public final class CdsEngine implements CdsReasoner {

    private final TripleStore<?> store;
    private final RuleSet rules;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private @Nullable CdsModel model;
    private long updatedAt;
    private int seen;
    private @Nullable IFact lastSeen;
    private int builds;

    public CdsEngine() {
        this(RuleSet.ALL);
    }

    public CdsEngine(RuleSet rules) {
        this(new MemoryTripleStore(), rules);
    }

    public CdsEngine(TripleStore<?> store) {
        this(store, RuleSet.ALL);
    }

    public CdsEngine(TripleStore<?> store, RuleSet rules) {
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

    @Override
    public CdsEngine add(String subject, String relation, String object) {
        store.add(subject, relation, object);
        return this;
    }

    @Override
    public boolean holds(String subject, String relation, String object) {
        return read(model -> model.holds(subject, relation, object));
    }

    @Override
    public Optional<Set<IFact>> why(String subject, String relation, String object) {
        return read(model -> Optional.ofNullable(model.why(subject, relation, object)));
    }

    @Override
    public List<Triple> match(@Nullable String subject, @Nullable String relation, @Nullable String object) {
        return read(model -> model.match(subject, relation, object));
    }

    @Override
    public long estimate(@Nullable String subject, @Nullable String relation, @Nullable String object) {
        return read(model -> model.estimate(subject, relation, object));
    }

    @Override
    public boolean isSubRelation(String sub, String sup) {
        return read(model -> model.isSubRelation(sub, sup));
    }

    @Override
    public String canonical(String name) {
        return read(model -> model.canonical(name));
    }

    @Override
    public Triple canonicalForm(IFact fact) {
        return read(model -> model.canonicalForm(fact));
    }

    @Override
    public boolean isRelation(String name) {
        return read(model -> model.isRelation(name));
    }

    @Override
    public boolean isType(String name) {
        return read(model -> model.isType(name));
    }

    @Override
    public boolean isSymmetric(String relation) {
        return read(model -> model.isSymmetric(relation));
    }

    @Override
    public boolean isTransitive(String relation) {
        return read(model -> model.isTransitive(relation));
    }

    /** How often a model was built from scratch, rather than grown -- for the benchmark. */
    int builds() {
        lock.readLock().lock();
        try {
            return builds;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * A question, asked of an up-to-date model under the read lock. When the store has moved on,
     * the model is brought up to date under the write lock first, which is then downgraded to the
     * read lock, so that the question sees the model it was brought to.
     */
    private <T> T read(Function<CdsModel, T> question) {
        lock.readLock().lock();
        try {
            if (model != null && store.revision() == updatedAt) return question.apply(model);
        } finally {
            lock.readLock().unlock();
        }
        lock.writeLock().lock();
        CdsModel current;
        try {
            current = refresh();
            lock.readLock().lock();
        } finally {
            lock.writeLock().unlock();
        }
        try {
            return question.apply(current);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Under the write lock: grown when the store has only grown, built anew when it lost a fact. */
    private CdsModel refresh() {
        long revision = store.revision();
        if (model != null && revision == updatedAt) return model;
        List<? extends IFact> facts = store.all();
        boolean grown = model != null && rules.normalise && seen > 0 && facts.size() > seen
                && facts.get(seen - 1) == lastSeen;
        if (grown) {
            model.grow(facts.subList(seen, facts.size()));
        } else {
            List<IFact> all = new ArrayList<>(Cds.axioms());
            all.addAll(facts);
            model = new CdsModel(rules, all);
            builds++;
        }
        updatedAt = revision;
        seen = facts.size();
        lastSeen = seen > 0 ? facts.get(seen - 1) : null;
        return model;
    }
}
