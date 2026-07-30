package com.calpano.ddot.it.refactor;

import org.jspecify.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared grammar for a single ddot triple slot ({@link MatchTerm} / {@link OutTerm}) and for splitting an
 * {@code S ..P.. O} line. Used by both surface parsers — the legacy block/arrow {@link RuleParser} and the
 * {@code !!find} {@link com.calpano.ddot.it.refactor.find.FindParser} — so the term vocabulary is identical
 * across them (see {@code refactor/rules-next.adoc}, Position 15).
 *
 * <p>Term shapes:
 * <ul>
 *   <li>{@code ?name} — whole-slot variable</li>
 *   <li>{@code ?_} — anonymous "any" (no binding)</li>
 *   <li>{@code prefix:$name} — literal prefix + captured tail (namespace migration; UC-7)</li>
 *   <li>{@code ?s ~ "glob*"} / {@code ?s =~ /re/} — a value-constrained variable</li>
 *   <li>{@code number(?v)} / {@code uri(?v)} / {@code date(?v)} — a typed match-and-bind (functions replace
 *       datatype tags for ddot's untyped strings; rules-next.adoc, Position 15, "Functions")</li>
 *   <li>anything else — a literal id</li>
 * </ul>
 */
public final class TermParser {

    private TermParser() {}

    private static final Pattern VAR = Pattern.compile("\\?([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Pattern GLOB_FILTER = Pattern.compile("\\?([a-zA-Z_][a-zA-Z0-9_]*)\\s*~\\s*\"(.*)\"");
    private static final Pattern REGEX_FILTER = Pattern.compile("\\?([a-zA-Z_][a-zA-Z0-9_]*)\\s*=~\\s*/(.*)/");
    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9]*");
    // typing function around a variable: number(?pop), uri(?spec), date(?d)
    private static final Pattern TYPE_FN = Pattern.compile("([a-zA-Z]+)\\s*\\(\\s*\\?([a-zA-Z_]\\w*)\\s*\\)");

    /**
     * Splits {@code s} into subject / predicate / object on the first two {@code ..}. An empty predicate becomes
     * the default {@code links to} ({@code ....} untyped link). An empty object is allowed only when
     * {@code allowEmptyObject} (the {@code !!find} existence hole {@code ..rel..}), yielding {@code "?_"}.
     */
    public static String[] splitTriple(String s, int lineNo, boolean allowEmptyObject) {
        int i1 = s.indexOf("..");
        int i2 = i1 < 0 ? -1 : s.indexOf("..", i1 + 2);
        if (i1 < 0 || i2 < 0) throw new RuleSyntaxException(lineNo, "not a triple pattern: '" + s.strip() + "'");
        String subj = s.substring(0, i1).strip();
        String pred = s.substring(i1 + 2, i2).strip();
        String obj = s.substring(i2 + 2).strip();
        if (pred.isEmpty()) pred = DdotLineSerializer.DEFAULT_PREDICATE; // '....' untyped link
        if (obj.isEmpty() && allowEmptyObject) obj = "?_";
        if (subj.isEmpty() || obj.isEmpty())
            throw new RuleSyntaxException(lineNo, "empty subject/object in '" + s.strip() + "'");
        return new String[]{subj, pred, obj};
    }

    /** {@link #splitTriple(String, int, boolean)} with {@code allowEmptyObject=false} (the legacy behavior). */
    public static String[] splitTriple(String s, int lineNo) {
        return splitTriple(s, lineNo, false);
    }

    public static MatchTerm matchTerm(String t, int lineNo) {
        if (t.equals("?_")) return MatchTerm.any();

        Matcher glob = GLOB_FILTER.matcher(t);
        if (glob.matches()) return MatchTerm.var(glob.group(1), globMatcher(glob.group(2)));
        Matcher re = REGEX_FILTER.matcher(t);
        if (re.matches()) return MatchTerm.var(re.group(1), StringMatcher.regex(re.group(2)));
        Matcher fn = TYPE_FN.matcher(t);
        if (fn.matches()) {
            StringMatcher typing = typingMatcher(fn.group(1), lineNo);
            return MatchTerm.var(fn.group(2), typing);
        }
        Matcher var = VAR.matcher(t);
        if (var.matches()) return MatchTerm.var(var.group(1));

        Capture c = capture(t);
        if (c != null) {
            // literal prefix + a captured tail bound to the group name
            return new MatchTerm(null, StringMatcher.regex(Pattern.quote(c.prefix()) + "(?<" + c.name() + ">.*)"));
        }
        return MatchTerm.literal(t);
    }

    public static OutTerm outTerm(String t, int lineNo) {
        if (t.equals("?_")) throw new RuleSyntaxException(lineNo, "anonymous '?_' is not allowed in add/output");
        Matcher var = VAR.matcher(t);
        if (var.matches()) return OutTerm.varRef(var.group(1));
        Capture c = capture(t);
        if (c != null) return OutTerm.template(c.prefix() + "${" + c.name() + "}");
        return OutTerm.constant(t);
    }

    /** A {@code prefix$name} capture term (e.g. {@code tool:$foo}), or {@code null} if {@code t} is not one. */
    public record Capture(String prefix, String name) {}

    public static @Nullable Capture capture(String t) {
        int d = t.lastIndexOf('$');
        if (d < 0) return null;
        String name = t.substring(d + 1);
        if (!NAME.matcher(name).matches()) return null; // a literal '$' not followed by a name
        return new Capture(t.substring(0, d), name);
    }

    /** The whole-string regex a typing function ({@code number}/{@code uri}/{@code date}) constrains its var to. */
    private static StringMatcher typingMatcher(String fn, int lineNo) {
        StringMatcher m = RuleFunctions.typing(fn);
        if (m == null) throw new RuleSyntaxException(lineNo, "unknown typing function '" + fn + "()' "
                + "(expected number/uri/date)");
        return m;
    }

    private static StringMatcher globMatcher(String glob) {
        int star = glob.indexOf('*');
        if (star < 0) return StringMatcher.equalsTo(glob);
        if (star == glob.length() - 1 && glob.indexOf('*', star + 1) < 0 && glob.indexOf('?') < 0) {
            return StringMatcher.prefix(glob.substring(0, star)); // "prefix*"
        }
        // general glob → regex
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*') sb.append(".*");
            else if (ch == '?') sb.append('.');
            else sb.append(Pattern.quote(String.valueOf(ch)));
        }
        return StringMatcher.regex(sb.toString());
    }

}
