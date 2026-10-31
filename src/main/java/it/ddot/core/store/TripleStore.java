package it.ddot.core.store;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A set of facts that can be added to, taken from and searched.
 *
 * <p>What {@code KnowledgeGraph} and {@code MemoryTripleStore} have in common, so that a consumer
 * such as {@code CdsInferenceEngine} can run over either.
 *
 * <p>A store may normalise what it is given -- {@code KnowledgeGraph} folds aliases -- so a fact
 * read back need not equal the fact put in.
 *
 * @param <T> the facts it holds
 */
public interface TripleStore<T extends IFact> {

    /** @return whether the store changed */
    boolean add(T fact);

    /** Adds a statement with no provenance. @return whether the store changed */
    boolean add(String subject, String relation, String object);

    /** @return whether the store changed */
    boolean remove(T fact);

    /** Every fact, in the order added. A copy, so the store can go on changing while it is read. */
    List<T> all();

    /** The facts matching a pattern; a null position matches anything. */
    List<T> find(@Nullable String subject, @Nullable String relation, @Nullable String object);

    int size();

    /**
     * Why the store holds a fact nobody wrote. Empty for a fact that was read, which answers the
     * question itself through {@link IFact#file}.
     */
    List<? extends IDerivation> derivationsOf(T fact);

    /**
     * A number that changes whenever the facts do, so a consumer can tell that what it worked out
     * from them is out of date without being told.
     */
    long revision();
}
