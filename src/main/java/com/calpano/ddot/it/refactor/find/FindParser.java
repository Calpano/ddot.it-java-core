package com.calpano.ddot.it.refactor.find;

import com.calpano.ddot.it.refactor.RuleSyntaxException;
import com.calpano.ddot.it.refactor.TermParser;
import com.calpano.ddot.it.refactor.TriplePattern;
import com.calpano.ddot.it.refactor.TripleTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code !!find} surface syntax — Position 15 of {@code refactor/rules-next.adoc}: minimal-diff
 * SPARQL, ddot-delimited terms, DO-verb last, blank-line terminated. Produces {@link FindQuery}s; the rewrite
 * verbs are runnable via {@link FindQuery#toRewriteRule()}.
 *
 * <pre>{@code
 * !!find[?name=..&end=..&mode=..&placement=..]
 *   <pattern>                 # S ..P.. O, two-space indent by convention (stripped)
 *   FILTER (..) | FILTER [NOT] EXISTS { .. } | OPTIONAL { .. } | { .. } UNION { .. } | BIND (.. AS ?v)
 *   ..
 * <VERB> [inline]             # SELECT|ASK|DESCRIBE|CONSTRUCT|REPLACE|INSERT|WARN|ERROR|LAYOUT (default SELECT *)
 *   <template>                # braceless CONSTRUCT/REPLACE/INSERT/LAYOUT lines, until blank line
 * <MODIFIER>                  # ORDER BY | GROUP BY | HAVING | LIMIT | OFFSET (after a SELECT verb)
 * }</pre>
 *
 * A region ends at the first blank line, or at a custom terminator declared via {@code ?end=MARKER}.
 * Indentation is cosmetic (ddot strips leading whitespace); it never affects parsing.
 *
 * <h2>Comments</h2>
 * Plain ddot.it has no comments, but {@code !!find} regions do. A comment starts at {@code //}
 * <em>only when preceded by whitespace</em> (a space or a tab) or at the start of the line — the whitespace is
 * mandatory because a ddot term may itself contain {@code //} (a URL such as {@code http://h}). The comment
 * runs to end of line and is allowed anywhere in a region, including on the {@code !!find} header:
 * <pre>{@code
 * !!find                 // this whole region promotes online tools   (header comment: OK)
 *   ?t ..online.. yes    // end-of-line comment on a pattern
 *   // a full-line comment is skipped, not a terminator
 * REPLACE
 *   ?t ..has type.. online tool
 * }</pre>
 * A full-line comment does <em>not</em> terminate the region (only a truly blank line does). Consequence of the
 * "whitespace before the marker" rule: a value (or a {@code WARN}/{@code ERROR} message) cannot contain
 * {@code " //"}.
 */
@SuppressWarnings("OverlyCoupledClass")
public final class FindParser {

    private FindParser() {}

    private static final String FIND = "!!find";
    /** Recognised DO-verbs (upper-case; the region's terminating action). */
    private static final java.util.Set<String> VERBS =
            java.util.Set.of("SELECT", "ASK", "DESCRIBE", "CONSTRUCT", "REPLACE", "INSERT", "WARN", "ERROR", "LAYOUT");
    /** First word of a SPARQL solution modifier line (follows a SELECT verb). */
    private static final java.util.Set<String> MODIFIER_HEADS =
            java.util.Set.of("ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET");
    private static final Pattern BIND = Pattern.compile("(?i)BIND\\s*\\(\\s*(.*)\\s+AS\\s+(\\?[a-zA-Z_]\\w*)\\s*\\)");

    /** Parses every {@code !!find} region found in {@code text} (other content is ignored). */
    public static List<FindQuery> parse(String text) {
        List<FindQuery> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String stripped = stripComment(lines[i]).strip();
            if (stripped.equals(FIND) || stripped.startsWith(FIND + "?")) {
                int[] end = new int[1];
                out.add(parseRegion(lines, i, end));
                i = end[0];
            } else {
                i++;
            }
        }
        return out;
    }

    /** Parses exactly one region; the text must contain a single {@code !!find}. */
    public static FindQuery parseOne(String text) {
        List<FindQuery> qs = parse(text);
        if (qs.size() != 1) throw new RuleSyntaxException(1, "expected exactly one !!find region, got " + qs.size());
        return qs.getFirst();
    }

    /** Parses the region starting at {@code lines[start]} (a {@code !!find} line); sets {@code end[0]} past it. */
    private static FindQuery parseRegion(String[] lines, int start, int[] end) {
        Map<String, String> params = parseParams(stripComment(lines[start]).strip(), start + 1);
        String terminator = params.get("end"); // null → blank line terminates

        // collect body lines until the terminator
        List<String> body = new ArrayList<>();
        List<Integer> bodyLineNo = new ArrayList<>();
        int i = start + 1;
        for (; i < lines.length; i++) {
            String raw = lines[i];
            String s = stripComment(raw).strip();
            // a comment-only line (non-blank raw, empty after stripping) is skipped, NOT a region terminator
            if (s.isEmpty() && !raw.isBlank()) continue;
            boolean stop = terminator == null ? s.isEmpty() : s.equals(terminator);
            if (stop) { i++; break; }
            if (s.isEmpty()) continue; // with a custom terminator, blank lines are allowed inside
            body.add(s);
            bodyLineNo.add(i + 1);
        }
        end[0] = i;

        // split body into WHERE elements and the trailing DO-verb (+ its template / modifiers)
        int verbIdx = indexOfVerb(body);
        List<MatchElement> where = new ArrayList<>();
        for (int k = 0; k < (verbIdx < 0 ? body.size() : verbIdx); k++) {
            where.add(parseMatchElement(body.get(k), bodyLineNo.get(k)));
        }

        DoVerb verb;
        List<String> modifiers = new ArrayList<>();
        if (verbIdx < 0) {
            verb = DoVerb.Select.all(); // omitted verb → SELECT *
        } else {
            verb = parseVerb(body, bodyLineNo, verbIdx, modifiers);
        }
        FindQuery q = new FindQuery(params.get("name"), params, List.copyOf(where), verb, List.copyOf(modifiers));
        validateRun(q, start + 1);
        return q;
    }

    /**
     * Enforces the {@code ?run=} contract at parse time (ddot-rules.adoc §Recursion): only the add-only verbs
     * may iterate, because a fixpoint needs a monotone round to have a least fixpoint at all.
     */
    private static void validateRun(FindQuery q, int lineNo) {
        String run = q.params().get("run");
        if (run == null) return;
        if (!FindQuery.RUN_ONCE.equals(run) && !FindQuery.RUN_FIXPOINT.equals(run)) {
            throw new RuleSyntaxException(lineNo, "unknown ?run=" + run + " (expected 'once' or 'fixpoint')");
        }
        if (FindQuery.RUN_FIXPOINT.equals(run) && !q.isAddOnly()) {
            String verb = q.verb().getClass().getSimpleName().toUpperCase(Locale.ROOT);
            throw new RuleSyntaxException(lineNo, "?run=fixpoint needs an add-only verb, got " + verb
                    + " — only CONSTRUCT and INSERT may iterate (a read-only verb would see the same graph every "
                    + "round; REPLACE deletes, so the iteration may oscillate and has no least fixpoint)");
        }
        String maxIter = q.params().get("maxIter");
        if (maxIter != null) {
            int n;
            try {
                n = Integer.parseInt(maxIter);
            } catch (NumberFormatException e) {
                throw new RuleSyntaxException(lineNo, "?maxIter must be a positive integer, got '" + maxIter + "'");
            }
            if (n < 1) throw new RuleSyntaxException(lineNo, "?maxIter must be >= 1, got " + n);
        }
    }

    // ---- verb ---------------------------------------------------------------

    /** Index of the DO-verb line in {@code body}, or {@code -1} if the verb is omitted (⇒ {@code SELECT *}). */
    private static int indexOfVerb(List<String> body) {
        for (int k = 0; k < body.size(); k++) {
            if (VERBS.contains(head(body.get(k)).toUpperCase(Locale.ROOT))) return k;
        }
        return -1;
    }

    private static DoVerb parseVerb(List<String> body, List<Integer> lineNo, int verbIdx, List<String> modifiers) {
        String line = body.get(verbIdx);
        int lno = lineNo.get(verbIdx);
        String kw = head(line).toUpperCase(Locale.ROOT);
        String rest = line.substring(head(line).length()).strip();
        // lines after the verb: braceless template triples and/or solution modifiers
        List<String> tail = body.subList(verbIdx + 1, body.size());
        List<Integer> tailNo = lineNo.subList(verbIdx + 1, body.size());

        return switch (kw) {
            case "SELECT" -> {
                collectModifiers(tail, tailNo, modifiers);
                yield new DoVerb.Select(rest.isEmpty() ? List.of() : projectionTokens(rest));
            }
            case "ASK" -> {
                requireNoTemplate(tail, tailNo);
                yield new DoVerb.Ask();
            }
            case "DESCRIBE" -> {
                requireNoTemplate(tail, tailNo);
                yield new DoVerb.Describe(rest.isEmpty() ? List.of() : List.of(rest.split("\\s+")));
            }
            case "CONSTRUCT" -> new DoVerb.Construct(templateOf(rest, tail, tailNo, lno));
            case "REPLACE" -> new DoVerb.Replace(templateOf(rest, tail, tailNo, lno));
            case "INSERT" -> new DoVerb.Insert(templateOf(rest, tail, tailNo, lno));
            case "WARN" -> {
                requireNoTemplate(tail, tailNo);
                yield new DoVerb.Warn(DoVerb.Warn.Severity.WARN, rest);
            }
            case "ERROR" -> {
                requireNoTemplate(tail, tailNo);
                yield new DoVerb.Warn(DoVerb.Warn.Severity.ERROR, rest);
            }
            case "LAYOUT" -> parseLayout(rest, tail, tailNo, lno);
            default -> throw new RuleSyntaxException(lno, "unknown verb '" + kw + "'");
        };
    }

    /** A verb's template: inline {@code { … }} on the verb line, else the braceless following lines. */
    private static List<TripleTemplate> templateOf(String inline, List<String> tail, List<Integer> tailNo, int lno) {
        List<TripleTemplate> template = new ArrayList<>();
        String braces = unwrapBraces(inline);
        if (braces != null) {
            if (!braces.isBlank()) template.add(parseTemplate(braces, lno));
            return template; // REPLACE {} / REPLACE { one triple } — inline form is single-line
        }
        if (!inline.isEmpty())
            throw new RuleSyntaxException(lno, "expected '{' or newline after verb, got '" + inline + "'");
        for (int k = 0; k < tail.size(); k++) {
            template.add(parseTemplate(tail.get(k), tailNo.get(k)));
        }
        return template;
    }

    private static DoVerb parseLayout(String inline, List<String> tail, List<Integer> tailNo, int lno) {
        // LAYOUT lines are patterns whose only meaningful slot is the relation; ..?other.. marks the "others" slot
        List<String> lines = new ArrayList<>();
        String braces = unwrapBraces(inline);
        if (braces != null && !braces.isBlank()) lines.add(braces);
        else if (!inline.isEmpty()) throw new RuleSyntaxException(lno, "expected '{' or newline after LAYOUT");
        lines.addAll(tail);
        List<Integer> nos = new ArrayList<>();
        if (braces != null && !braces.isBlank()) nos.add(lno);
        nos.addAll(tailNo);

        List<String> relations = new ArrayList<>();
        int othersIndex = -1;
        for (int k = 0; k < lines.size(); k++) {
            String[] p = TermParser.splitTriple(lines.get(k), nos.get(k), true);
            String rel = p[1];
            if (rel.equals("?other") || rel.equals("?_")) {
                if (othersIndex >= 0) throw new RuleSyntaxException(nos.get(k), "duplicate '..?other..' in LAYOUT");
                othersIndex = relations.size();
            } else {
                relations.add(rel);
            }
        }
        return new DoVerb.Layout(List.copyOf(relations), othersIndex);
    }

    private static void collectModifiers(List<String> tail, List<Integer> tailNo, List<String> modifiers) {
        for (int k = 0; k < tail.size(); k++) {
            String s = tail.get(k);
            if (!MODIFIER_HEADS.contains(head(s).toUpperCase(Locale.ROOT))) {
                throw new RuleSyntaxException(tailNo.get(k), "expected a solution modifier (ORDER BY / GROUP BY / "
                        + "HAVING / LIMIT / OFFSET), got '" + s + "'");
            }
            modifiers.add(s);
        }
    }

    private static void requireNoTemplate(List<String> tail, List<Integer> tailNo) {
        for (int k = 0; k < tail.size(); k++) {
            String s = tail.get(k);
            if (MODIFIER_HEADS.contains(head(s).toUpperCase(Locale.ROOT))) continue;
            throw new RuleSyntaxException(tailNo.get(k), "this verb takes no template, got '" + s + "'");
        }
    }

    /** Splits a SELECT projection into tokens, keeping parenthesised aggregates like {@code (COUNT(?x) AS ?n)} whole. */
    private static List<String> projectionTokens(String rest) {
        List<String> tokens = new ArrayList<>();
        int depth = 0, tokenStart = -1;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '(') { if (depth == 0 && tokenStart < 0) tokenStart = i; depth++; }
            else if (c == ')') depth--;
            else if (Character.isWhitespace(c) && depth == 0) {
                if (tokenStart >= 0) { tokens.add(rest.substring(tokenStart, i)); tokenStart = -1; }
            } else if (tokenStart < 0) tokenStart = i;
        }
        if (tokenStart >= 0) tokens.add(rest.substring(tokenStart));
        return tokens;
    }

    // ---- match elements ------------------------------------------------------

    private static MatchElement parseMatchElement(String line, int lineNo) {
        String kw = head(line).toUpperCase(Locale.ROOT);
        switch (kw) {
            case "FILTER" -> {
                String expr = line.substring(head(line).length()).strip();
                String upper = expr.toUpperCase(Locale.ROOT);
                if (upper.startsWith("EXISTS")) {
                    return new MatchElement.Exists(false, groupPatterns(afterKeyword(expr, "EXISTS"), lineNo));
                }
                if (upper.startsWith("NOT EXISTS")) {
                    return new MatchElement.Exists(true, groupPatterns(afterKeyword(expr, "NOT EXISTS"), lineNo));
                }
                return new MatchElement.Filter(stripOuterParens(expr));
            }
            case "OPTIONAL" -> {
                return new MatchElement.Optional(groupPatterns(afterKeyword(line, "OPTIONAL"), lineNo));
            }
            case "BIND" -> {
                Matcher m = BIND.matcher(line);
                if (!m.matches()) throw new RuleSyntaxException(lineNo, "bad BIND '" + line + "' (expected BIND(expr AS ?v))");
                return new MatchElement.Bind(m.group(1).strip(), m.group(2));
            }
            default -> {
                if (line.startsWith("{")) return parseUnion(line, lineNo);
                return parsePatternElement(line, lineNo);
            }
        }
    }

    /** A pattern line, possibly with trailing {@code ,, ..key.. value} metadata constraints (Q11). */
    private static MatchElement.Pattern parsePatternElement(String line, int lineNo) {
        int comma = line.indexOf(",,");
        String triple = comma < 0 ? line : line.substring(0, comma).strip();
        TriplePattern pattern = parsePattern(triple, lineNo);
        List<MatchElement.MetaConstraint> meta = new ArrayList<>();
        if (comma >= 0) {
            for (String pair : splitMetaPairs(line.substring(comma + 2))) {
                String[] kv = TermParser.splitTriple("_ .." + pair, lineNo, true); // reuse S..P..O split with a dummy subject
                meta.add(new MatchElement.MetaConstraint(TermParser.matchTerm(kv[1], lineNo),
                        TermParser.matchTerm(kv[2], lineNo)));
            }
        }
        return new MatchElement.Pattern(pattern, List.copyOf(meta));
    }

    /** Splits an inline metadata run {@code ..k1.. v1 ..k2.. v2} into {@code k1.. v1}, {@code k2.. v2} chunks. */
    private static List<String> splitMetaPairs(String meta) {
        List<String> pairs = new ArrayList<>();
        String s = meta.strip();
        while (s.startsWith("..")) {
            s = s.substring(2);
            int next = s.indexOf("..", indexAfterKey(s));
            if (next < 0) { pairs.add(s.strip()); break; }
            pairs.add(s.substring(0, next).strip());
            s = s.substring(next).strip();
        }
        return pairs;
    }

    /** Index in {@code s} just past the metadata key and its closing {@code ..} (so we skip the key's separator). */
    private static int indexAfterKey(String s) {
        int close = s.indexOf("..");
        return close < 0 ? s.length() : close + 2;
    }

    private static MatchElement parseUnion(String line, int lineNo) {
        int u = line.toUpperCase(Locale.ROOT).indexOf("UNION");
        if (u < 0) throw new RuleSyntaxException(lineNo, "expected UNION in '" + line + "'");
        List<MatchElement> left = groupPatterns(line.substring(0, u), lineNo);
        List<MatchElement> right = groupPatterns(line.substring(u + "UNION".length()), lineNo);
        return new MatchElement.Union(left, right);
    }

    /** Parses the patterns inside a single-line {@code { … }} group (EXISTS/OPTIONAL/UNION side). */
    private static List<MatchElement> groupPatterns(String braced, int lineNo) {
        String inner = unwrapBraces(braced.strip());
        if (inner == null) throw new RuleSyntaxException(lineNo, "expected '{ … }' in '" + braced.strip() + "'");
        List<MatchElement> group = new ArrayList<>();
        if (!inner.isBlank()) group.add(parsePatternElement(inner.strip(), lineNo));
        return group;
    }

    // ---- low-level helpers ---------------------------------------------------

    private static TriplePattern parsePattern(String s, int lineNo) {
        String[] p = TermParser.splitTriple(s, lineNo, true);
        return new TriplePattern(TermParser.matchTerm(p[0], lineNo), TermParser.matchTerm(p[1], lineNo),
                TermParser.matchTerm(p[2], lineNo));
    }

    private static TripleTemplate parseTemplate(String s, int lineNo) {
        String[] p = TermParser.splitTriple(s, lineNo);
        return new TripleTemplate(TermParser.outTerm(p[0], lineNo), TermParser.outTerm(p[1], lineNo),
                TermParser.outTerm(p[2], lineNo));
    }

    /** Parses {@code !!find?name=X&end=Y&…} into a param map; the bare {@code !!find} yields an empty map. */
    private static Map<String, String> parseParams(String findLine, int lineNo) {
        Map<String, String> params = new LinkedHashMap<>();
        int q = findLine.indexOf('?');
        if (q < 0) return params;
        for (String kv : findLine.substring(q + 1).split("&")) {
            if (kv.isBlank()) continue;
            int eq = kv.indexOf('=');
            if (eq < 0) throw new RuleSyntaxException(lineNo, "bad !!find param '" + kv + "' (expected key=value)");
            params.put(kv.substring(0, eq).strip(), kv.substring(eq + 1).strip());
        }
        return params;
    }

    /** Returns the content of a leading {@code { … }} (trimmed), or {@code null} if {@code s} is not brace-wrapped. */
    private static String unwrapBraces(String s) {
        String t = s.strip();
        if (!t.startsWith("{")) return null;
        int close = t.lastIndexOf('}');
        if (close < 0) return null;
        return t.substring(1, close).strip();
    }

    /** Strips one pair of outer parentheses from a FILTER expression, if present. */
    private static String stripOuterParens(String expr) {
        String t = expr.strip();
        if (t.startsWith("(") && t.endsWith(")")) return t.substring(1, t.length() - 1).strip();
        return t;
    }

    private static String afterKeyword(String s, String keyword) {
        int idx = s.toUpperCase(Locale.ROOT).indexOf(keyword);
        return s.substring(idx + keyword.length()).strip();
    }

    /** The first whitespace-delimited word of {@code line}. */
    private static String head(String line) {
        int i = 0;
        while (i < line.length() && !Character.isWhitespace(line.charAt(i))) i++;
        return line.substring(0, i);
    }

    /**
     * Removes a trailing (or whole-line) {@code //} comment: a {@code //} marker that is either the first
     * non-whitespace content of the line or immediately preceded by whitespace. Returns the line up to the
     * marker (callers {@code strip()} the result); a line with no such marker is returned unchanged. The
     * whitespace requirement keeps {@code //} inside ddot terms (e.g. a URL {@code http://h}) intact.
     */
    static String stripComment(String line) {
        int firstNonWs = 0;
        while (firstNonWs < line.length() && Character.isWhitespace(line.charAt(firstNonWs))) firstNonWs++;
        if (isMarkerAt(line, firstNonWs)) return line.substring(0, firstNonWs); // whole-line comment
        for (int i = firstNonWs; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i)) && isMarkerAt(line, i + 1)) return line.substring(0, i);
        }
        return line;
    }

    /** {@code true} if a {@code //} comment marker begins at {@code idx}. */
    private static boolean isMarkerAt(String line, int idx) {
        return idx + 1 < line.length() && line.charAt(idx) == '/' && line.charAt(idx + 1) == '/';
    }

}
