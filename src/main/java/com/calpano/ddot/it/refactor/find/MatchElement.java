package com.calpano.ddot.it.refactor.find;

import com.calpano.ddot.it.refactor.TriplePattern;

import java.util.List;

/**
 * One element of a {@code !!find} region's match part (everything before the DO-verb) — the ddot-shaped
 * where-group of Position 15 (see {@code refactor/rules-next.adoc}). A region's match part is a sequence of
 * these, ANDed with shared variable bindings.
 *
 * <p>Expression-carrying elements ({@link Filter}, {@link Bind}) keep the SPARQL expression as an opaque
 * string: this lib parses the {@code !!find} <em>structure</em> and executes the rewrite subset via the
 * existing {@code RewriteRule} engine; a full FILTER/BIND expression evaluator is out of scope (and flagged as
 * such in rules-next.adoc, "the FILTER expression sub-language … imported wholesale").
 */
public sealed interface MatchElement {

    /**
     * A triple pattern, optionally carrying {@code ,,} metadata constraints (RDF-star-style quoted-triple
     * match, Q11). {@code meta} holds one {@link TriplePattern} per {@code ..key.. value} metadata pair, with
     * the pattern's own subject reused as a placeholder subject (only predicate/object are meaningful).
     */
    record Pattern(TriplePattern pattern, List<MetaConstraint> meta) implements MatchElement {
        public Pattern(TriplePattern pattern) {this(pattern, List.of());}
    }

    /** One {@code ,, ..key.. value} constraint on a {@link Pattern}'s triple metadata. */
    record MetaConstraint(com.calpano.ddot.it.refactor.MatchTerm key,
                          com.calpano.ddot.it.refactor.MatchTerm value) {}

    /**
     * A {@code FILTER} clause. {@code exists != null} means {@code FILTER [NOT] EXISTS { group }} (a guard);
     * otherwise {@code expression} is the raw boolean expression (e.g. {@code (?pop >= 8)}, {@code len(?o) >= 5}).
     */
    record Filter(String expression) implements MatchElement {}

    /** A {@code FILTER EXISTS} / {@code FILTER NOT EXISTS} guard over an inner group of patterns. */
    record Exists(boolean negated, List<MatchElement> group) implements MatchElement {}

    /** An {@code OPTIONAL { group }} left-join. */
    record Optional(List<MatchElement> group) implements MatchElement {}

    /** A {@code { left } UNION { right }} alternative. */
    record Union(List<MatchElement> left, List<MatchElement> right) implements MatchElement {}

    /** A {@code BIND (expression AS ?var)} — {@code expression} kept raw. */
    record Bind(String expression, String var) implements MatchElement {}

}
