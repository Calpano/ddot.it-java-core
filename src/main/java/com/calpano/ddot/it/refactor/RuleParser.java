package com.calpano.ddot.it.refactor;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the ddot.it rewrite-rule surface syntax (see {@code refactor/TODO-rules.adoc}) into {@link RewriteRule}s
 * that drive {@link GraphRewriter}. Supports the block form and the single-line arrow sugar.
 *
 * <pre>{@code
 * rewrite [Name]
 * when   <s> .. <p> .. <o>        # one or more; first is primary, rest are guards
 * remove | deprecate              # optional (default: keep)
 * add    <s> .. <p> .. <o>        # zero or more templates
 * placement next-to-primary|end-of-file
 *
 * # arrow sugar (single pattern):
 * ?s ..knows.. ?o => ?s ..is friend of.. ?o   # rename (remove + add)
 * + tool:$x ..?p.. ?o => tool:$x ..has type.. tool  # assert (add only)
 * - ?s ..deprecated.. yes                      # delete
 * }</pre>
 *
 * Blank lines separate rules; lines whose first non-space char is {@code #} are comments.
 */
public final class RuleParser {

    private RuleParser() {}

    private enum Section {WHEN, ADD}

    /** Parses a rule file (possibly many rules). */
    public static List<RewriteRule> parse(String text) {
        List<RewriteRule> rules = new ArrayList<>();
        Builder cur = null;
        Section section = null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i].strip();
            if (line.isEmpty()) {
                if (cur != null) { rules.add(cur.build(lineNo)); cur = null; section = null; }
                continue;
            }
            if (line.startsWith("#")) continue;

            String kw = firstWord(line);
            String rest = line.substring(kw.length()).strip();
            switch (kw) {
                case "rewrite" -> {
                    if (cur != null) rules.add(cur.build(lineNo));
                    cur = new Builder();
                    section = null;
                }
                case "when" -> {
                    require(cur, lineNo, "'when' outside a rewrite block");
                    section = Section.WHEN;
                    if (!rest.isEmpty()) cur.when.add(parsePattern(rest, lineNo));
                }
                case "add" -> {
                    require(cur, lineNo, "'add' outside a rewrite block");
                    section = Section.ADD;
                    if (!rest.isEmpty()) cur.add.add(parseTemplate(rest, lineNo));
                }
                case "remove" -> {require(cur, lineNo, "'remove' outside a rewrite block"); cur.removal = RewriteRule.RemovalMode.DELETE; section = null;}
                case "deprecate" -> {require(cur, lineNo, "'deprecate' outside a rewrite block"); cur.removal = RewriteRule.RemovalMode.DEPRECATE; section = null;}
                case "placement" -> {require(cur, lineNo, "'placement' outside a rewrite block"); cur.placement = parsePlacement(rest, lineNo); section = null;}
                case "filter" -> {require(cur, lineNo, "'filter' outside a rewrite block"); cur.filters.add(parseFilter(rest, lineNo)); section = null;}
                case "name", "message" -> require(cur, lineNo, "'" + kw + "' outside a rewrite block"); // accepted, ignored
                default -> {
                    if (cur != null && section == Section.WHEN) {
                        cur.when.add(parsePattern(line, lineNo));
                    } else if (cur != null && section == Section.ADD) {
                        cur.add.add(parseTemplate(line, lineNo));
                    } else if (cur == null) {
                        rules.add(parseArrow(line, lineNo));
                    } else {
                        throw new RuleSyntaxException(lineNo, "unexpected '" + line + "'");
                    }
                }
            }
        }
        if (cur != null) rules.add(cur.build(lines.length));
        return rules;
    }

    /** Parses exactly one rule. */
    public static RewriteRule parseRule(String text) {
        List<RewriteRule> rules = parse(text);
        if (rules.size() != 1) throw new RuleSyntaxException(1, "expected exactly one rule, got " + rules.size());
        return rules.getFirst();
    }

    // ---- arrow sugar ---------------------------------------------------------

    private static RewriteRule parseArrow(String line, int lineNo) {
        if (line.startsWith("- ")) { // delete
            return RewriteRule.of(parsePattern(line.substring(2), lineNo), true);
        }
        boolean addOnly = line.startsWith("+ ");
        String body = addOnly ? line.substring(2) : line;
        int arrow = body.indexOf("=>");
        if (arrow < 0) {
            throw new RuleSyntaxException(lineNo, "expected '=>' (or a 'rewrite' block) in '" + line + "'");
        }
        TriplePattern left = parsePattern(body.substring(0, arrow), lineNo);
        TripleTemplate right = parseTemplate(body.substring(arrow + 2), lineNo);
        return RewriteRule.of(left, !addOnly, right); // plain '=>' renames (remove), '+' asserts (keep)
    }

    // ---- patterns / templates ------------------------------------------------

    private static TriplePattern parsePattern(String s, int lineNo) {
        String[] p = TermParser.splitTriple(s, lineNo);
        return new TriplePattern(TermParser.matchTerm(p[0], lineNo), TermParser.matchTerm(p[1], lineNo),
                TermParser.matchTerm(p[2], lineNo));
    }

    private static TripleTemplate parseTemplate(String s, int lineNo) {
        String[] p = TermParser.splitTriple(s, lineNo);
        return new TripleTemplate(TermParser.outTerm(p[0], lineNo), TermParser.outTerm(p[1], lineNo),
                TermParser.outTerm(p[2], lineNo));
    }

    // ---- helpers -------------------------------------------------------------

    /** Parses a {@code filter} expression: {@code len(?v) OP n} or {@code count(?v, "c") OP n}. */
    private static RewriteRule.Filter parseFilter(String s, int lineNo) {
        RewriteRule.Filter f = tryValueFilter(s);
        if (f == null) throw new RuleSyntaxException(lineNo,
                "bad filter '" + s + "' (expected len(?v) OP n or count(?v, \"c\") OP n)");
        return f;
    }

    /**
     * A post-binding value filter compiled from a {@code FILTER}/{@code filter} expression, or {@code null} if
     * {@code s} is not a recognised single-variable predicate. Delegates to the internal function library
     * {@link RuleFunctions#parseFilter} — {@code len}/{@code count}, numeric compare, {@code regex}/{@code =~},
     * {@code strStarts}/{@code strEnds}/{@code contains}, and {@code number}/{@code uri}/{@code date}. Shared
     * with the {@code !!find} bridge ({@code find.FindQuery#toRewriteRule()}).
     */
    public static RewriteRule.@Nullable Filter tryValueFilter(String s) {
        return RuleFunctions.parseFilter(s);
    }

    private static RewriteRule.Placement parsePlacement(String s, int lineNo) {
        return switch (s) {
            case "next-to-primary" -> RewriteRule.Placement.NEXT_TO_PRIMARY;
            case "end-of-file" -> RewriteRule.Placement.END_OF_FILE;
            default -> throw new RuleSyntaxException(lineNo, "unknown placement '" + s + "'");
        };
    }

    private static String firstWord(String line) {
        int sp = line.indexOf(' ');
        int tab = line.indexOf('\t');
        int end = sp < 0 ? tab : (tab < 0 ? sp : Math.min(sp, tab));
        return end < 0 ? line : line.substring(0, end);
    }

    private static void require(Builder cur, int lineNo, String message) {
        if (cur == null) throw new RuleSyntaxException(lineNo, message);
    }

    private static final class Builder {
        final List<TriplePattern> when = new ArrayList<>();
        final List<TripleTemplate> add = new ArrayList<>();
        final List<RewriteRule.Filter> filters = new ArrayList<>();
        RewriteRule.RemovalMode removal = RewriteRule.RemovalMode.KEEP;
        RewriteRule.Placement placement = RewriteRule.Placement.NEXT_TO_PRIMARY;

        RewriteRule build(int lineNo) {
            if (when.isEmpty()) throw new RuleSyntaxException(lineNo, "rule has no 'when' pattern");
            return new RewriteRule(List.copyOf(when), removal, placement, List.copyOf(add), List.copyOf(filters));
        }
    }

}
