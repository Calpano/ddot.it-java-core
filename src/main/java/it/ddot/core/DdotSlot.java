package it.ddot.core;

public enum DdotSlot {

    SUBJECT, RELATION, OBJECT,
    /** TODO doc this one */
    META_START,

    META_RELATION, META_OBJECT,

    /**
     * the automaton's `MetaNextInline`: just after an inline `;;`, where only whitespace and then `..` / `....` may
     * follow — anything else makes the whole line NotATriple.
     */
    META_NEXT;

}
