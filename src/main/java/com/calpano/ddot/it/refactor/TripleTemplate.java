package com.calpano.ddot.it.refactor;

/**
 * A triple to add when a {@link RewriteRule} fires. Each position is an {@link OutTerm} — a literal id or a
 * reference to a variable bound by the rule's condition.
 */
public record TripleTemplate(OutTerm s, OutTerm p, OutTerm o) {

    /** Convenience: {@code (varRef(sVar), constant(predicate), constant(object))}. */
    public static TripleTemplate of(String sVar, String predicate, String object) {
        return new TripleTemplate(OutTerm.varRef(sVar), OutTerm.constant(predicate), OutTerm.constant(object));
    }

}
