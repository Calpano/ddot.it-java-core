package com.calpano.ddot.it.refactor;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * One position (subject, predicate or object) of a {@link TriplePattern} condition.
 *
 * <p>A term optionally <em>binds</em> a variable {@link #var} and optionally <em>constrains</em> the matched
 * value with a {@link StringMatcher}. The four shapes from the requirements:
 * <ul>
 *   <li>{@link #literal literal("online")} — constraint {@code equals}, no binding</li>
 *   <li>{@link #var var("s")} — binds {@code s}, matches anything</li>
 *   <li>{@link #var(String, StringMatcher) var("s", prefix("tool:"))} — binds {@code s}, matches only values
 *       passing the constraint (the "string-match on a var")</li>
 *   <li>{@link #any() any()} — wildcard, matches anything, binds nothing</li>
 * </ul>
 *
 * @param var        variable name to bind the matched value to, or {@code null} for no binding
 * @param constraint string constraint the value must pass, or {@code null} to match anything
 */
public record MatchTerm(@Nullable String var, @Nullable StringMatcher constraint) {

    public static MatchTerm literal(String value) {
        return new MatchTerm(null, StringMatcher.equalsTo(value));
    }

    public static MatchTerm var(String name) {
        return new MatchTerm(name, null);
    }

    public static MatchTerm var(String name, StringMatcher constraint) {
        return new MatchTerm(name, constraint);
    }

    public static MatchTerm any() {
        return new MatchTerm(null, null);
    }

    /** {@code true} if {@code value} satisfies this term's constraint (an unconstrained term matches all). */
    public boolean matches(String value) {
        return constraint == null || constraint.matches(value);
    }

    /**
     * If {@code value} matches this term, returns the variable bindings it produces — the whole value under
     * {@link #var} (if any) plus any regex named-group captures; otherwise {@code null}. An empty map means
     * "matched, no bindings" (e.g. a wildcard or a plain literal).
     */
    public @Nullable Map<String, String> capture(String value) {
        if (constraint != null && !constraint.matches(value)) return null;
        Map<String, String> out = new HashMap<>();
        if (var != null) out.put(var, value);
        if (constraint instanceof StringMatcher.Regex re) {
            Map<String, String> groups = re.capture(value);
            if (groups != null) out.putAll(groups);
        }
        return out;
    }

}
