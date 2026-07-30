package com.calpano.ddot.it.refactor;

import java.util.List;

/**
 * A pattern-conditioned graph refactoring: match a conjunction of triple patterns, then handle the matched
 * primary triple per {@link #removalMode} and/or add new triples ({@link #add}) placed per {@link #addPlacement}.
 * See {@code use-cases.adoc} for semantics and worked examples.
 *
 * <p><b>Multi-pattern conditions:</b> {@link #match} is a conjunction (join) — all patterns must match with
 * consistent variable bindings. The <b>first</b> pattern is the <em>primary</em> (target): its matched edge is
 * the one removed/deprecated. The remaining patterns are <em>guards</em>.
 *
 * @param match        the conjunctive condition; {@code match.getFirst()} is the primary/target pattern
 * @param removalMode  what to do with each matched primary triple
 * @param addPlacement where added triples are written back in the source
 * @param add          triples to add per solution (structurally de-duplicated by the engine)
 */
public record RewriteRule(List<TriplePattern> match, RemovalMode removalMode, Placement addPlacement,
                          List<TripleTemplate> add, List<Filter> filters) {

    /** A post-binding constraint on a bound variable (e.g. a length/count check) — see {@code filter} syntax. */
    public record Filter(String var, StringMatcher matcher) {}

    /** What happens to a matched primary triple. */
    public enum RemovalMode {
        /** Leave it in place (add-only rule). */
        KEEP,
        /** Delete it (its source line is removed, or replaced in place by a same-subject add — a rename). */
        DELETE,
        /** Keep it but mark it {@code ,, ..status.. deprecated} instead of deleting. */
        DEPRECATE
    }

    /** Where added triples land in the source file. */
    public enum Placement {
        /** Directly after the primary match's source line. */
        NEXT_TO_PRIMARY,
        /** Appended at the end of the primary match's source file. */
        END_OF_FILE
    }

    /** Single-pattern rule ({@code removeMatched} maps to {@link RemovalMode#DELETE}/{@link RemovalMode#KEEP}). */
    public static RewriteRule of(TriplePattern condition, boolean removeMatched, TripleTemplate... add) {
        return new RewriteRule(List.of(condition), removeMatched ? RemovalMode.DELETE : RemovalMode.KEEP,
                Placement.NEXT_TO_PRIMARY, List.of(add), List.of());
    }

    /** Multi-pattern (join) rule; {@code match.getFirst()} is the primary/target pattern. */
    public static RewriteRule of(List<TriplePattern> match, boolean removeMatched, List<TripleTemplate> add) {
        return new RewriteRule(match, removeMatched ? RemovalMode.DELETE : RemovalMode.KEEP,
                Placement.NEXT_TO_PRIMARY, add, List.of());
    }

    /**
     * Renames a relation everywhere: {@code (?s oldRelation ?o)} → remove and add {@code (?s newRelation ?o)}
     * (written in place — the add shares the primary subject).
     */
    public static RewriteRule renameRelation(String oldRelation, String newRelation) {
        return of(
                new TriplePattern(MatchTerm.var("s"), MatchTerm.literal(oldRelation), MatchTerm.var("o")),
                true,
                new TripleTemplate(OutTerm.varRef("s"), OutTerm.constant(newRelation), OutTerm.varRef("o")));
    }

    /** Returns a copy of this rule with a different add placement. */
    public RewriteRule withPlacement(Placement placement) {
        return new RewriteRule(match, removalMode, placement, add, filters);
    }

    /** Returns a copy of this rule that deprecates (rather than deletes) the matched primary triple. */
    public RewriteRule deprecating() {
        return new RewriteRule(match, RemovalMode.DEPRECATE, addPlacement, add, filters);
    }

    /** Returns a copy of this rule with the given post-binding filters. */
    public RewriteRule withFilters(List<Filter> filters) {
        return new RewriteRule(match, removalMode, addPlacement, add, filters);
    }

    /** The primary (target) pattern — {@code match.getFirst()}. */
    public TriplePattern primary() {
        return match.getFirst();
    }

}
