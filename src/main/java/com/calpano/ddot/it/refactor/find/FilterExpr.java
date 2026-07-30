package com.calpano.ddot.it.refactor.find;

import com.calpano.ddot.it.refactor.RuleSyntaxException;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A small expression sub-language for {@code !!find} {@code FILTER (…)}, {@code BIND (… AS ?v)} and
 * {@code HAVING (…)} clauses — the curated slice of SPARQL 1.1's expression grammar that ddot.it's rules use.
 *
 * <p>Supported: {@code ?var}, number and string ({@code "…"} / {@code '…'}) literals; boolean
 * {@code && || ! AND OR NOT}; comparisons {@code = == != < <= > >=} (numeric when both sides parse as numbers,
 * else string); arithmetic {@code + - * /}; and functions
 * {@code STRSTARTS STRENDS CONTAINS STRLEN len STRAFTER STRBEFORE SUBSTR LCASE UCASE REGEX BOUND COALESCE
 * count number uri date abs}. Names are case-insensitive. Unbound variables make a comparison false and a
 * {@code BIND} value {@code null}.
 */
public final class FilterExpr {

    private FilterExpr() {}

    // ---- public API ----------------------------------------------------------

    /** Evaluate {@code expr} to a boolean against {@code sol} (for FILTER / HAVING). Unbound/typeless ⇒ false. */
    public static boolean evalBool(String expr, Map<String, String> sol) {
        Object v = new Parser(expr).parseAll().eval(sol);
        return truthy(v);
    }

    /** Evaluate {@code expr} to a string against {@code sol} (for BIND / templates), or {@code null}. */
    public static @Nullable String evalValue(String expr, Map<String, String> sol) {
        Object v = new Parser(expr).parseAll().eval(sol);
        return v == null ? null : asStr(v);
    }

    // ---- AST -----------------------------------------------------------------

    private interface Node {
        @Nullable Object eval(Map<String, String> sol);
    }

    // ---- tokenizer -----------------------------------------------------------

    private enum Kind {NUM, STR, VAR, NAME, OP, LP, RP, COMMA, END}

    private record Tok(Kind kind, String text) {}

    private static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '(') { out.add(new Tok(Kind.LP, "(")); i++; continue; }
            if (c == ')') { out.add(new Tok(Kind.RP, ")")); i++; continue; }
            if (c == ',') { out.add(new Tok(Kind.COMMA, ",")); i++; continue; }
            if (c == '"' || c == '\'') {
                char q = c; int j = i + 1; StringBuilder b = new StringBuilder();
                while (j < n && s.charAt(j) != q) {
                    char d = s.charAt(j);
                    if (d == '\\' && j + 1 < n) { b.append(s.charAt(j + 1)); j += 2; }
                    else { b.append(d); j++; }
                }
                if (j >= n) throw new RuleSyntaxException(1, "unterminated string in expression: " + s);
                out.add(new Tok(Kind.STR, b.toString())); i = j + 1; continue;
            }
            if (c == '?') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                out.add(new Tok(Kind.VAR, s.substring(i + 1, j))); i = j; continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int j = i;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                out.add(new Tok(Kind.NUM, s.substring(i, j))); i = j; continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == ':')) j++;
                out.add(new Tok(Kind.NAME, s.substring(i, j))); i = j; continue;
            }
            // multi-char operators first
            String two = i + 1 < n ? s.substring(i, i + 2) : "";
            if (two.equals("<=") || two.equals(">=") || two.equals("!=") || two.equals("==")
                    || two.equals("&&") || two.equals("||")) { out.add(new Tok(Kind.OP, two)); i += 2; continue; }
            if ("=<>!+-*/".indexOf(c) >= 0) { out.add(new Tok(Kind.OP, String.valueOf(c))); i++; continue; }
            throw new RuleSyntaxException(1, "unexpected character '" + c + "' in expression: " + s);
        }
        out.add(new Tok(Kind.END, ""));
        return out;
    }

    // ---- parser (precedence climbing) ----------------------------------------

    private static final class Parser {
        private final List<Tok> toks;
        private int pos = 0;

        Parser(String s) { this.toks = lex(s); }

        Node parseAll() {
            Node n = parseOr();
            expect(Kind.END);
            return n;
        }

        private Tok peek() { return toks.get(pos); }
        private Tok next() { return toks.get(pos++); }
        private boolean isOp(String op) { Tok t = peek(); return t.kind == Kind.OP && t.text.equals(op); }
        private boolean isName(String kw) { Tok t = peek(); return t.kind == Kind.NAME && t.text.equalsIgnoreCase(kw); }

        private void expect(Kind k) {
            if (peek().kind != k) throw new RuleSyntaxException(1, "expected " + k + " but got '" + peek().text + "'");
            pos++;
        }

        private Node parseOr() {
            Node a = parseAnd();
            while (isOp("||") || isName("OR")) { next(); Node b = parseAnd(); a = orNode(a, b); }
            return a;
        }

        private Node parseAnd() {
            Node a = parseNot();
            while (isOp("&&") || isName("AND")) { next(); Node b = parseNot(); a = andNode(a, b); }
            return a;
        }

        private Node parseNot() {
            if (isOp("!") || isName("NOT")) { next(); Node a = parseNot(); return sol -> !truthy(a.eval(sol)); }
            return parseCmp();
        }

        private Node parseCmp() {
            Node a = parseAdd();
            Tok t = peek();
            if (t.kind == Kind.OP && List.of("=", "==", "!=", "<", "<=", ">", ">=").contains(t.text)) {
                next(); Node b = parseAdd(); String op = t.text;
                return sol -> compare(op, a.eval(sol), b.eval(sol));
            }
            return a;
        }

        private Node parseAdd() {
            Node a = parseMul();
            while (isOp("+") || isOp("-")) {
                String op = next().text; Node b = parseMul();
                Node l = a; a = sol -> arith(op, l.eval(sol), b.eval(sol));
            }
            return a;
        }

        private Node parseMul() {
            Node a = parseUnary();
            while (isOp("*") || isOp("/")) {
                String op = next().text; Node b = parseUnary();
                Node l = a; a = sol -> arith(op, l.eval(sol), b.eval(sol));
            }
            return a;
        }

        private Node parseUnary() {
            if (isOp("-")) { next(); Node a = parseUnary(); return sol -> { Double d = asNum(a.eval(sol)); return d == null ? null : -d; }; }
            return parsePrimary();
        }

        private Node parsePrimary() {
            Tok t = peek();
            switch (t.kind) {
                case NUM -> { next(); double d = Double.parseDouble(t.text); return sol -> d; }
                case STR -> { next(); return sol -> t.text; }
                case VAR -> { next(); return sol -> sol.get(t.text); }
                case LP -> { next(); Node n = parseOr(); expect(Kind.RP); return n; }
                case NAME -> { return parseCallOrKeyword(); }
                default -> throw new RuleSyntaxException(1, "unexpected token '" + t.text + "' in expression");
            }
        }

        private Node parseCallOrKeyword() {
            Tok name = next();
            if (name.text.equalsIgnoreCase("true")) return sol -> Boolean.TRUE;
            if (name.text.equalsIgnoreCase("false")) return sol -> Boolean.FALSE;
            if (peek().kind == Kind.LP) {
                next();
                List<Node> args = new ArrayList<>();
                if (peek().kind != Kind.RP) {
                    args.add(parseOr());
                    while (peek().kind == Kind.COMMA) { next(); args.add(parseOr()); }
                }
                expect(Kind.RP);
                String fn = name.text.toLowerCase();
                return sol -> callFn(fn, args, sol);
            }
            // a bare name is a constant string (a ddot id used as a value)
            return sol -> name.text;
        }
    }

    // ---- evaluation helpers --------------------------------------------------

    private static Node orNode(Node a, Node b) { return sol -> truthy(a.eval(sol)) || truthy(b.eval(sol)); }
    private static Node andNode(Node a, Node b) { return sol -> truthy(a.eval(sol)) && truthy(b.eval(sol)); }

    private static @Nullable Object callFn(String fn, List<Node> args, Map<String, String> sol) {
        List<Object> a = new ArrayList<>();
        for (Node n : args) a.add(n.eval(sol));
        switch (fn) {
            case "bound": return !a.isEmpty() && a.getFirst() != null;
            case "strstarts": return str(a, 0).startsWith(str(a, 1));
            case "strends": return str(a, 0).endsWith(str(a, 1));
            case "contains": return str(a, 0).contains(str(a, 1));
            case "strlen": case "len": return (double) str(a, 0).length();
            case "strafter": { String s = str(a, 0), p = str(a, 1); int i = s.indexOf(p); return i < 0 ? "" : s.substring(i + p.length()); }
            case "strbefore": { String s = str(a, 0), p = str(a, 1); int i = s.indexOf(p); return i < 0 ? "" : s.substring(0, i); }
            case "substr": { String s = str(a, 0); int start = (int) num(a, 1) - 1; if (start < 0) start = 0; if (start > s.length()) return ""; return a.size() >= 3 ? s.substring(start, Math.min(s.length(), start + (int) num(a, 2))) : s.substring(start); }
            case "lcase": return str(a, 0).toLowerCase();
            case "ucase": return str(a, 0).toUpperCase();
            case "abs": return Math.abs(num(a, 0));
            case "regex": return str(a, 0).matches(a.size() >= 2 ? ".*(" + str(a, 1) + ").*" : ".*");
            case "count": { String s = str(a, 0), ch = str(a, 1); long c = s.chars().filter(x -> x == ch.charAt(0)).count(); return (double) c; }
            case "coalesce": { for (Object o : a) if (o != null) return o; return null; }
            case "number": return a.isEmpty() ? null : asNum(a.getFirst());
            case "uri": { Object arg0 = a.isEmpty() ? null : a.getFirst(); String s = asStr(arg0); return s != null && s.matches("\\w+:.*") ? s : null; }
            case "date": { Object arg0 = a.isEmpty() ? null : a.getFirst(); String s = asStr(arg0); return s != null && s.matches("\\d{4}-\\d{2}-\\d{2}.*") ? s : null; }
            default: throw new RuleSyntaxException(1, "unknown function '" + fn + "(…)' in expression");
        }
    }

    private static String str(List<Object> a, int i) { return i < a.size() ? asStr(a.get(i)) : ""; }
    private static double num(List<Object> a, int i) { Double d = i < a.size() ? asNum(a.get(i)) : null; return d == null ? 0 : d; }

    private static @Nullable Object compare(String op, @Nullable Object a, @Nullable Object b) {
        if (a == null || b == null) return op.equals("!=") ? (a != b) : Boolean.FALSE;
        Double da = asNum(a), db = asNum(b);
        int cmp;
        if (da != null && db != null) cmp = Double.compare(da, db);
        else cmp = asStr(a).compareTo(asStr(b));
        return switch (op) {
            case "=", "==" -> cmp == 0;
            case "!=" -> cmp != 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            default -> Boolean.FALSE;
        };
    }

    private static @Nullable Object arith(String op, @Nullable Object a, @Nullable Object b) {
        Double da = asNum(a), db = asNum(b);
        if (da == null || db == null) return null;
        return switch (op) {
            case "+" -> da + db;
            case "-" -> da - db;
            case "*" -> da * db;
            case "/" -> db == 0 ? null : da / db;
            default -> null;
        };
    }

    private static boolean truthy(@Nullable Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Double d) return d != 0;
        String s = v.toString();
        return !s.isEmpty() && !s.equalsIgnoreCase("false");
    }

    private static @Nullable Double asNum(@Nullable Object v) {
        if (v == null) return null;
        if (v instanceof Double d) return d;
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        try { return Double.parseDouble(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    private static @Nullable String asStr(@Nullable Object v) {
        if (v == null) return null;
        if (v instanceof Double d) return d == Math.rint(d) && !d.isInfinite() ? Long.toString((long) (double) d) : d.toString();
        return v.toString();
    }
}
