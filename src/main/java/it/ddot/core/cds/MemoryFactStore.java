package it.ddot.core.cds;

import it.ddot.core.store.IDerivation;
import it.ddot.core.store.IFact;
import it.ddot.core.store.TripleStore;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The plain store: a set of facts, indexed by subject and object, and nothing worked out.
 *
 * <p>Facts are equal as their type says. A {@link Triple} is only its statement, so the same statement
 * added twice is one; a fact that also carries where it was read is a second fact when read a second time.
 *
 * @param <T> the facts it holds
 */
public class MemoryFactStore<T extends IFact> implements TripleStore<T> {

    /** Makes the fact {@link #add(String, String, String)} adds: a statement with no provenance. */
    @FunctionalInterface
    public interface Maker<T> {
        T make(String subject, String relation, String object);
    }

    private final Maker<T> maker;
    private final Set<T> facts = new LinkedHashSet<>();
    private final Map<String, List<T>> bySubject = new HashMap<>();
    private final Map<String, List<T>> byObject = new HashMap<>();
    private long revision;

    public MemoryFactStore(Maker<T> maker) {
        this.maker = maker;
    }

    @Override
    public synchronized boolean add(T fact) {
        if (!facts.add(fact)) return false;
        bySubject.computeIfAbsent(fact.subject(), k -> new ArrayList<>()).add(fact);
        byObject.computeIfAbsent(fact.object(), k -> new ArrayList<>()).add(fact);
        revision++;
        return true;
    }

    @Override
    public boolean add(String subject, String relation, String object) {
        return add(maker.make(subject, relation, object));
    }

    @Override
    public synchronized boolean remove(T fact) {
        if (!facts.remove(fact)) return false;
        bySubject.get(fact.subject()).remove(fact);
        byObject.get(fact.object()).remove(fact);
        revision++;
        return true;
    }

    @Override
    public synchronized List<T> all() {
        return List.copyOf(facts);
    }

    @Override
    public synchronized List<T> find(@Nullable String subject, @Nullable String relation, @Nullable String object) {
        Iterable<T> candidates = subject != null ? bySubject.getOrDefault(subject, List.of())
                : object != null ? byObject.getOrDefault(object, List.of())
                : facts;
        List<T> out = new ArrayList<>();
        for (T fact : candidates) {
            if (subject != null && !subject.equals(fact.subject())) continue;
            if (relation != null && !relation.equals(fact.relation())) continue;
            if (object != null && !object.equals(fact.object())) continue;
            out.add(fact);
        }
        return out;
    }

    @Override
    public synchronized int size() {
        return facts.size();
    }

    /** Always empty: this store only holds what it was given. */
    @Override
    public List<IDerivation> derivationsOf(T fact) {
        return List.of();
    }

    @Override
    public synchronized long revision() {
        return revision;
    }
}
