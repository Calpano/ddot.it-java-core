package it.ddot.core.cds;

/** The spec's predefined configurations of the CDS rules. */
public enum RuleSet {
    ALL(true, true),
    /** The usual choice when the transitive closure is too costly. */
    ALL_EXCEPT_TRANSITIVE(true, false),
    /** Only r01, on the names as written. */
    ONLY_TRANSITIVE(false, true),
    /** Only what was written. */
    NONE(false, false);

    /** Aliases, inverses, the hierarchy, types and tags: everything except chains. */
    public final boolean normalise;
    /** r01. */
    public final boolean transitive;

    RuleSet(boolean normalise, boolean transitive) {
        this.normalise = normalise;
        this.transitive = transitive;
    }
}
