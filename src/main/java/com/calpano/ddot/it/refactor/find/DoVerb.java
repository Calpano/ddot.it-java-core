package com.calpano.ddot.it.refactor.find;

import com.calpano.ddot.it.refactor.TripleTemplate;

import java.util.List;

/**
 * What a {@code !!find} region <em>does</em> with its matches — the last line of the region (Position 15,
 * "Verb: optional LAST line"). The verb set is deliberately <em>open</em>: query verbs
 * ({@link Select}/{@link Ask}/{@link Describe}/{@link Construct}) report; {@link Replace}/{@link Insert}
 * transform (and bridge to the {@code RewriteRule} engine, see {@link FindQuery#toRewriteRule()});
 * {@link Warn} and {@link Layout} are lint/write-back directives with no SPARQL equivalent.
 */
public sealed interface DoVerb {

    /** {@code SELECT ?a ?b} — {@code projection} tokens verbatim (e.g. {@code (COUNT(?x) AS ?n)}); empty ⇒ {@code *}. */
    record Select(List<String> projection) implements DoVerb {
        /** The default omitted verb: {@code SELECT *}. */
        public static Select all() {return new Select(List.of());}

        public boolean isSelectAll() {return projection.isEmpty() || projection.equals(List.of("*"));}
    }

    /** {@code ASK} — existence test. */
    record Ask() implements DoVerb {}

    /** {@code DESCRIBE ?t} — dump the described resources. */
    record Describe(List<String> vars) implements DoVerb {}

    /** {@code CONSTRUCT { template }} — build new triples (not asserted into the corpus). */
    record Construct(List<TripleTemplate> template) implements DoVerb {}

    /**
     * {@code REPLACE { template }} — the invented rewrite verb: delete what {@code !!find} matched, insert the
     * template (Position 15). An empty template is pure deletion.
     */
    record Replace(List<TripleTemplate> template) implements DoVerb {}

    /** {@code INSERT { template }} — add-only sibling of {@link Replace} (nothing deleted). */
    record Insert(List<TripleTemplate> template) implements DoVerb {}

    /** {@code WARN <msg>} / {@code ERROR <msg>} — emit a message per match; {@code ERROR} additionally fails CI. */
    record Warn(Severity severity, String message) implements DoVerb {
        public enum Severity {WARN, ERROR}
    }

    /**
     * {@code LAYOUT} — write-back directive that stably reorders an entity's property lines to the template's
     * relation order. {@code relations} lists the relations in the desired order; {@code othersIndex} is the
     * position of the {@code ..?other..} marker where unlisted properties go, or {@code -1} for none.
     */
    record Layout(List<String> relations, int othersIndex) implements DoVerb {}

}
