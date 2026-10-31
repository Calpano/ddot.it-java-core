package it.ddot.core.store;

import org.jspecify.annotations.Nullable;

/**
 * One statement, and where it was written down.
 *
 * <p>The triple is the statement; the rest is provenance. Two facts with the same triple read from
 * two files are two facts, because each answers "where does this come from" differently. A fact
 * nobody wrote -- an axiom, or one a store worked out -- has no file, and its store says why it
 * holds instead: see {@link TripleStore#derivationsOf}.
 */
public interface IFact {

    String subject();

    String relation();

    String object();

    /** The file it was read from, or null when nobody wrote it down. */
    @Nullable String file();

    /** The lines of {@link #file} it was read from, or null with no file. */
    @Nullable ILineRange range();

    /** The component that read it, or null when not recorded. */
    @Nullable String reader();

    /** Whether somebody wrote this down, as opposed to it being given or worked out. */
    default boolean isRead() {
        return file() != null;
    }
}
