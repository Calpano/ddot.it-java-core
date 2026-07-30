package com.calpano.ddot.it.token;

/**
 * The slot a piece of text occupies. Mirrors the five completable slots of the
 * Autocomplete Specification (s / p / o / mr / mo).
 *
 * <p>{@link #META} is the coarse "somewhere in the meta part" role used by the
 * PSI layer, which does not split the meta part further. Completion uses the
 * finer {@link #META_RELATION} / {@link #META_OBJECT}.
 */
public enum DdotRole {
    SUBJECT,
    PREDICATE,
    OBJECT,
    META,
    META_RELATION,
    META_OBJECT
}
