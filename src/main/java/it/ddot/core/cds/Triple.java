package it.ddot.core.cds;

import it.ddot.core.store.IFact;
import it.ddot.core.store.ILineRange;
import org.jspecify.annotations.Nullable;

/**
 * One statement: subject, relation, object -- all three plain names, with no provenance.
 *
 * <p>A relation may be written with a leading {@code ^}, which reads it backwards: {@code a ^p b}
 * is {@code b p a}. Every relation has that inverse whether or not anybody named it.
 */
public record Triple(String subject, String relation, String object) implements IFact {

    /** The bare statement of any fact, so facts from different stores compare by what they say. */
    public static Triple of(IFact fact) {
        return fact instanceof Triple triple ? triple : new Triple(fact.subject(), fact.relation(), fact.object());
    }

    @Override
    public @Nullable String file() {
        return null;
    }

    @Override
    public @Nullable ILineRange range() {
        return null;
    }

    @Override
    public @Nullable String reader() {
        return null;
    }

    @Override
    public String toString() {
        return subject + " .." + relation + ".. " + object;
    }
}
