package com.calpano.ddot.it.refactor;

import org.jspecify.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rule language's <em>internal function library</em> — the built-in functions and predicates available in
 * {@code FILTER} clauses and as term-position typing. Everything here is implemented in this lib; there is no
 * call-out to user-registered host (Java) functions yet.
 *
 * <h2>Term-position typing</h2>
 * {@code number(?v)} / {@code uri(?v)} / {@code date(?v)} constrain a matched value to a shape (used by
 * {@link TermParser}). They compile to a whole-string {@link StringMatcher.Regex}.
 *
 * <h2>{@code FILTER}-clause predicates</h2>
 * A post-binding constraint on a single bound variable, compiled to a {@link RewriteRule.Filter}
 * ({@code var} + {@link StringMatcher}) that {@code GraphRewriter} applies. Recognised forms:
 * <ul>
 *   <li><b>numeric compare</b> — {@code ?v OP n} (e.g. {@code ?pop >= 8}); {@code OP} ∈
 *       {@code >= > <= < = == !=}; {@code n} may be a decimal or negative</li>
 *   <li><b>length / count</b> — {@code len(?v) OP n}, {@code count(?v, "c") OP n} (integer bound)</li>
 *   <li><b>regex</b> — {@code regex(?v, "re")}, {@code regex(?v, /re/)}, or the sugar {@code ?v =~ /re/}</li>
 *   <li><b>string predicates</b> — {@code strStarts(?v, "p")}, {@code strEnds(?v, "s")},
 *       {@code contains(?v, "s")}</li>
 *   <li><b>typing predicates</b> — {@code number(?v)}, {@code uri(?v)}, {@code date(?v)}</li>
 * </ul>
 * {@link #parseFilter(String)} returns {@code null} for anything it does not recognise (e.g. a cross-variable
 * expression or a call to a not-yet-supported host function), so the caller can report it as non-executable.
 */
public final class RuleFunctions {

    private RuleFunctions() {}

    // ---- typing shapes (shared by term-position typing and the number()/uri()/date() FILTER predicates) ----

    static final String NUMBER_RE = "-?\\d+(?:\\.\\d+)?";
    static final String URI_RE = "[a-zA-Z][a-zA-Z0-9+.-]*:.+";
    static final String DATE_RE = "\\d{4}-\\d{2}-\\d{2}(?:T.*)?";

    /** The typing constraint for {@code name} ({@code number}/{@code uri}/{@code date}), or {@code null} if unknown. */
    public static @Nullable StringMatcher typing(String name) {
        return switch (name) {
            case "number" -> StringMatcher.regex(NUMBER_RE);
            case "uri" -> StringMatcher.regex(URI_RE);
            case "date" -> StringMatcher.regex(DATE_RE);
            default -> null;
        };
    }

    // ---- FILTER-clause grammar ----------------------------------------------

    private static final String OP = "(>=|<=|==|!=|=|>|<)";
    private static final String VAR = "\\?([a-zA-Z_]\\w*)";

    private static final Pattern LEN =
            Pattern.compile("len\\s*\\(\\s*" + VAR + "\\s*\\)\\s*" + OP + "\\s*(-?\\d+)");
    private static final Pattern COUNT =
            Pattern.compile("count\\s*\\(\\s*" + VAR + "\\s*,\\s*(?:\"(.)\"|'(.)')\\s*\\)\\s*" + OP + "\\s*(-?\\d+)");
    private static final Pattern MATCH_REGEX = Pattern.compile(VAR + "\\s*=~\\s*/(.*)/");
    // fname(?v)  or  fname(?v, "arg")  or  fname(?v, /arg/)
    private static final Pattern FUNC = Pattern.compile(
            "([a-zA-Z]\\w*)\\s*\\(\\s*" + VAR + "\\s*(?:,\\s*(?:\"([^\"]*)\"|/(.*)/)\\s*)?\\)");
    private static final Pattern NUM_COMPARE =
            Pattern.compile(VAR + "\\s*" + OP + "\\s*(-?\\d+(?:\\.\\d+)?)");

    /**
     * Parses a {@code FILTER} value-predicate into an executable {@link RewriteRule.Filter}, or returns
     * {@code null} if {@code expr} is not a recognised single-variable predicate (the caller then treats it as
     * non-executable). {@code expr} is the boolean expression with any outer parentheses already stripped.
     */
    public static RewriteRule.@Nullable Filter parseFilter(String expr) {
        String s = expr.strip();

        Matcher len = LEN.matcher(s);
        if (len.matches()) {
            int[] r = intRange(len.group(2), Integer.parseInt(len.group(3)));
            if (r != null) return new RewriteRule.Filter(len.group(1), StringMatcher.length(r[0], r[1]));
        }
        Matcher cnt = COUNT.matcher(s);
        if (cnt.matches()) {
            String c = cnt.group(2) != null ? cnt.group(2) : cnt.group(3);
            int[] r = intRange(cnt.group(4), Integer.parseInt(cnt.group(5)));
            if (r != null) return new RewriteRule.Filter(cnt.group(1), StringMatcher.count(c.charAt(0), r[0], r[1]));
        }
        Matcher re = MATCH_REGEX.matcher(s);
        if (re.matches()) {
            return new RewriteRule.Filter(re.group(1), StringMatcher.regexFind(re.group(2)));
        }
        Matcher fn = FUNC.matcher(s);
        if (fn.matches()) {
            String arg = fn.group(3) != null ? fn.group(3) : fn.group(4); // "quoted" or /regex/
            StringMatcher m = predicate(fn.group(1), arg);
            return m == null ? null : new RewriteRule.Filter(fn.group(2), m);
        }
        Matcher num = NUM_COMPARE.matcher(s);
        if (num.matches()) {
            StringMatcher.NumericOp op = StringMatcher.NumericOp.fromSymbol(num.group(2));
            if (op != null) return new RewriteRule.Filter(num.group(1),
                    StringMatcher.numeric(op, Double.parseDouble(num.group(3))));
        }
        return null;
    }

    /** A boolean predicate function {@code fname(?v[, arg])} → its {@link StringMatcher}, or {@code null} if unknown. */
    private static @Nullable StringMatcher predicate(String fname, @Nullable String arg) {
        return switch (fname) {
            case "regex" -> arg == null ? null : StringMatcher.regexFind(arg);
            case "strStarts" -> arg == null ? null : StringMatcher.prefix(arg);
            case "strEnds" -> arg == null ? null : StringMatcher.suffix(arg);
            case "contains" -> arg == null ? null : StringMatcher.substring(arg);
            case "number", "uri", "date" -> arg != null ? null : typing(fname); // typing predicates take no arg
            default -> null;
        };
    }

    /**
     * Maps a comparison operator + integer to an inclusive {@code [min, max]} range (for len/count), or
     * {@code null} for {@code !=} — which has no single-range form, so it is reported as unrecognised rather
     * than silently matching everything.
     */
    private static int @Nullable [] intRange(String op, int n) {
        return switch (op) {
            case ">=" -> new int[]{n, Integer.MAX_VALUE};
            case ">" -> new int[]{n + 1, Integer.MAX_VALUE};
            case "<=" -> new int[]{0, n};
            case "<" -> new int[]{0, n - 1};
            case "==", "=" -> new int[]{n, n};
            default -> null; // '!='
        };
    }

}
