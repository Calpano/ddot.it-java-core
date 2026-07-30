package com.calpano.ddot.it.refactor.find;

import com.calpano.ddot.it.refactor.RewriteRule;
import com.calpano.ddot.it.refactor.RuleParser;
import com.calpano.ddot.it.refactor.TriplePattern;
import com.calpano.ddot.it.refactor.TripleTemplate;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A parsed {@code !!find} region (Position 15 of {@code refactor/rules-next.adoc}): a match part
 * ({@link #where}, a conjunction of {@link MatchElement}s) plus what to do with the matches ({@link #verb}) and
 * SPARQL solution modifiers ({@link #modifiers}, kept verbatim). {@link #params} carries the region command's
 * {@code ?key=value} options ({@code name}, {@code end}, {@code mode}, {@code placement}).
 *
 * <p>The <em>rewrite</em> verbs ({@link DoVerb.Replace}, {@link DoVerb.Insert}) execute today by bridging to the
 * existing engine via {@link #toRewriteRule()}. The query and lint verbs (SELECT/ASK/DESCRIBE/CONSTRUCT/WARN/
 * LAYOUT) are represented for a consumer to interpret; this lib ships no query evaluator (see rules-next.adoc).
 */
public record FindQuery(@Nullable String name, Map<String, String> params, List<MatchElement> where,
                        DoVerb verb, List<String> modifiers) {

    /** {@code ?run=fixpoint} — iterate to saturation (see {@code ddot-rules.adoc} §Recursion). */
    public static final String RUN_FIXPOINT = "fixpoint";
    /** {@code ?run=once} — the default: match the graph once, apply the verb, stop. */
    public static final String RUN_ONCE = "once";
    /** Default {@code ?maxIter} bound on fixpoint rounds. */
    public static final int DEFAULT_MAX_ITER = 100;

    /** {@code true} if this region transforms the graph ({@link DoVerb.Replace} / {@link DoVerb.Insert}). */
    public boolean isRewrite() {
        return verb instanceof DoVerb.Replace || verb instanceof DoVerb.Insert;
    }

    /** {@code true} if this region is add-only, hence legal under {@code ?run=fixpoint}. */
    public boolean isAddOnly() {
        return verb instanceof DoVerb.Construct || verb instanceof DoVerb.Insert;
    }

    /** {@code true} if this region iterates to saturation ({@code ?run=fixpoint}). */
    public boolean isFixpoint() {
        return RUN_FIXPOINT.equals(params.get("run"));
    }

    /** The fixpoint group this region belongs to; regions without {@code ?group=} join the default group. */
    public String group() {
        return params.getOrDefault("group", "");
    }

    /** The {@code ?maxIter} round bound; {@link #DEFAULT_MAX_ITER} when unset. Validated at parse time. */
    public int maxIter() {
        String v = params.get("maxIter");
        if (v == null) return DEFAULT_MAX_ITER;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_ITER;
        }
    }

    /** The template of an add-only verb ({@code CONSTRUCT} / {@code INSERT}), else empty. */
    public List<TripleTemplate> addTemplate() {
        return switch (verb) {
            case DoVerb.Construct c -> c.template();
            case DoVerb.Insert i -> i.template();
            default -> List.of();
        };
    }

    /**
     * Compiles a rewrite region ({@code REPLACE} / {@code INSERT}) to a {@link RewriteRule} runnable by
     * {@code GraphRewriter}. The mapping (rules-next.adoc, "Rewrite: the REPLACE verb"):
     * <ul>
     *   <li>top-level patterns → the join condition; the first is the primary (removal target)</li>
     *   <li>{@code FILTER EXISTS { … }} → additional guard patterns (matched, never removed)</li>
     *   <li>{@code FILTER …} single-variable value predicates → post-binding {@link RewriteRule.Filter}s
     *       (len/count, numeric compare, regex/{@code =~}, strStarts/strEnds/contains, number/uri/date —
     *       see {@link com.calpano.ddot.it.refactor.RuleFunctions})</li>
     *   <li>{@code REPLACE} → {@link RewriteRule.RemovalMode#DELETE} (or {@code DEPRECATE} with
     *       {@code ?mode=deprecate}); {@code INSERT} → {@link RewriteRule.RemovalMode#KEEP}</li>
     *   <li>{@code ?placement=end-of-file} → {@link RewriteRule.Placement#END_OF_FILE}</li>
     * </ul>
     *
     * @throws UnsupportedOperationException for constructs the single-primary engine can't run — a query verb,
     *                                       {@code FILTER NOT EXISTS} / {@code OPTIONAL} / {@code UNION} /
     *                                       {@code BIND}, {@code ,,} metadata match, or a non-value FILTER.
     */
    public RewriteRule toRewriteRule() {
        List<TripleTemplate> template = switch (verb) {
            case DoVerb.Replace r -> r.template();
            case DoVerb.Insert i -> i.template();
            default -> throw new UnsupportedOperationException(
                    "not a rewrite region: " + verb.getClass().getSimpleName() + " (only REPLACE/INSERT execute)");
        };

        List<TriplePattern> match = new ArrayList<>();
        List<RewriteRule.Filter> filters = new ArrayList<>();
        for (MatchElement el : where) {
            switch (el) {
                case MatchElement.Pattern p -> {
                    if (!p.meta().isEmpty()) throw unsupported(",, metadata match is query-only, not executable");
                    match.add(p.pattern());
                }
                case MatchElement.Exists ex -> {
                    if (ex.negated()) throw unsupported("FILTER NOT EXISTS has no removal-target mapping");
                    for (MatchElement g : ex.group()) {
                        if (g instanceof MatchElement.Pattern gp && gp.meta().isEmpty()) match.add(gp.pattern());
                        else throw unsupported("only plain patterns are allowed inside FILTER EXISTS");
                    }
                }
                case MatchElement.Filter f -> {
                    RewriteRule.Filter vf = RuleParser.tryValueFilter(f.expression());
                    if (vf == null) throw unsupported("FILTER '" + f.expression()
                            + "' is not an executable single-variable predicate (see RuleFunctions for the "
                            + "supported forms: len/count, numeric compare, regex/=~, strStarts/strEnds/contains, "
                            + "number/uri/date)");
                    filters.add(vf);
                }
                case MatchElement.Optional ignored -> throw unsupported("OPTIONAL is query-only");
                case MatchElement.Union ignored -> throw unsupported("UNION is query-only");
                case MatchElement.Bind ignored -> throw unsupported("BIND is query-only (no add-side eval yet)");
            }
        }
        if (match.isEmpty()) throw unsupported("rewrite region has no match pattern");

        boolean delete = verb instanceof DoVerb.Replace;
        RewriteRule.RemovalMode mode = !delete ? RewriteRule.RemovalMode.KEEP
                : "deprecate".equals(params.get("mode")) ? RewriteRule.RemovalMode.DEPRECATE
                : RewriteRule.RemovalMode.DELETE;
        RewriteRule.Placement placement = "end-of-file".equals(params.get("placement"))
                ? RewriteRule.Placement.END_OF_FILE : RewriteRule.Placement.NEXT_TO_PRIMARY;

        return new RewriteRule(List.copyOf(match), mode, placement, List.copyOf(template), List.copyOf(filters));
    }

    private static UnsupportedOperationException unsupported(String why) {
        return new UnsupportedOperationException(why);
    }

}
