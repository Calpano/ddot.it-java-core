package it.ddot.core;

public enum DdotSlot {

    SUBJECT, RELATION, OBJECT,
    /** TODO doc this one */
    META_START,

    META_RELATION, META_OBJECT,

    /**
     * the automaton's `MetaNextInline`: just after an inline `;;`, where the next entry begins. Like `MetaStart` it
     * admits a pair (`..` / `....`) or a free-text note — or nothing at all: an empty entry carries no metadata and is
     * dropped, leaving the triple and the entries around it untouched.
     */
    META_NEXT;

}
