package com.calpano.ddot.it.refactor;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A string constraint applied to a matched id (subject / predicate / object) in a {@link MatchTerm}. This is
 * how a rule expresses a "string-match on a variable" — e.g. "subject starts with {@code tool:}".
 */
public sealed interface StringMatcher {

    boolean matches(String value);

    static StringMatcher equalsTo(String value) {return new Equals(value);}

    static StringMatcher prefix(String prefix) {return new Prefix(prefix);}

    static StringMatcher suffix(String suffix) {return new Suffix(suffix);}

    /**
     * Whole-string Java regex (the value must match in full). Any <em>named</em> capture group
     * {@code (?<name>...)} binds a variable {@code name} usable in the rule's added templates — e.g.
     * {@code regex("tool:(?<rest>.*)")} binds {@code rest} to the part after {@code tool:}. This is the anchored
     * form used by the {@code number}/{@code uri}/{@code date} typing shapes.
     */
    static StringMatcher regex(String regex) {return new Regex(Pattern.compile(regex));}

    /**
     * Partial ("find") Java regex — matches if the pattern occurs <em>anywhere</em> in the value, the
     * grep / SPARQL {@code REGEX} semantics. This backs the {@code regex(?v, …)} / {@code ?v =~ /…/} FILTER
     * predicates, so {@code ^tool:} matches {@code tool:figma} without a trailing {@code .*}.
     */
    static StringMatcher regexFind(String regex) {return new RegexFind(Pattern.compile(regex));}

    /** String length in {@code [min, max]} (inclusive). Use {@link Integer#MAX_VALUE} for "unbounded above". */
    static StringMatcher length(int min, int max) {return new Length(min, max);}

    /** Count of character {@code ch} in {@code [min, max]} (inclusive). */
    static StringMatcher count(char ch, int min, int max) {return new CharCount(ch, min, max);}

    /** Value contains {@code needle} as a substring (case-sensitive). */
    static StringMatcher substring(String needle) {return new Substring(needle);}

    /**
     * Parses the value as a number and compares it to {@code bound} with {@code op}. A value that does not parse
     * as a number never matches — so {@code number(?v)} typing and a magnitude compare compose safely.
     */
    static StringMatcher numeric(NumericOp op, double bound) {return new Numeric(op, bound);}

    /** Comparison operators for a {@link Numeric} constraint. */
    enum NumericOp {
        GE(">="), GT(">"), LE("<="), LT("<"), EQ("="), NE("!=");

        private final String symbol;

        NumericOp(String symbol) {this.symbol = symbol;}

        public String symbol() {return symbol;}

        /** Maps a comparison symbol ({@code >=} {@code >} {@code <=} {@code <} {@code =}/{@code ==} {@code !=}) to an op. */
        public static @org.jspecify.annotations.Nullable NumericOp fromSymbol(String s) {
            return switch (s) {
                case ">=" -> GE;
                case ">" -> GT;
                case "<=" -> LE;
                case "<" -> LT;
                case "=", "==" -> EQ;
                case "!=" -> NE;
                default -> null;
            };
        }
    }

    /** Exact, case-sensitive equality. */
    record Equals(String value) implements StringMatcher {
        @Override public boolean matches(String v) {return value.equals(v);}
    }

    /** Case-sensitive starts-with. */
    record Prefix(String prefix) implements StringMatcher {
        @Override public boolean matches(String v) {return v.startsWith(prefix);}
    }

    /** Case-sensitive ends-with. */
    record Suffix(String suffix) implements StringMatcher {
        @Override public boolean matches(String v) {return v.endsWith(suffix);}
    }

    /** Whole-string regex match, exposing named capture groups. */
    record Regex(Pattern pattern) implements StringMatcher {

        /** Names of the named capture groups declared in the pattern (parsed from its source). */
        private static final Pattern GROUP_NAME = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");

        @Override public boolean matches(String v) {return pattern.matcher(v).matches();}

        /** Group names declared as {@code (?<name>...)} in this pattern. */
        public List<String> groupNames() {
            Matcher gm = GROUP_NAME.matcher(pattern.pattern());
            List<String> names = new java.util.ArrayList<>();
            while (gm.find()) names.add(gm.group(1));
            return names;
        }

        /**
         * Returns the values of the named capture groups if {@code v} matches, else {@code null}. An empty map
         * means "matches, but no named groups".
         */
        public @org.jspecify.annotations.Nullable Map<String, String> capture(String v) {
            Matcher m = pattern.matcher(v);
            if (!m.matches()) return null;
            Map<String, String> out = new java.util.HashMap<>();
            for (String name : groupNames()) {
                String g = m.group(name);
                if (g != null) out.put(name, g);
            }
            return out;
        }
    }

    /** Constrains the string's length to {@code [min, max]}. */
    record Length(int min, int max) implements StringMatcher {
        @Override public boolean matches(String v) {
            int n = v.length();
            return n >= min && n <= max;
        }
    }

    /** Constrains the count of a specific character to {@code [min, max]}. */
    record CharCount(char ch, int min, int max) implements StringMatcher {
        @Override public boolean matches(String v) {
            int c = 0;
            for (int i = 0; i < v.length(); i++) if (v.charAt(i) == ch) c++;
            return c >= min && c <= max;
        }
    }

    /** Partial ("find") regex: matches if the pattern occurs anywhere in the value (no full-string anchor). */
    record RegexFind(Pattern pattern) implements StringMatcher {
        @Override public boolean matches(String v) {return pattern.matcher(v).find();}
    }

    /** Case-sensitive substring containment. */
    record Substring(String needle) implements StringMatcher {
        @Override public boolean matches(String v) {return v.contains(needle);}
    }

    /** Numeric comparison: parses the value as a {@code double} and compares to {@link #bound}; non-numbers fail. */
    record Numeric(NumericOp op, double bound) implements StringMatcher {
        @Override public boolean matches(String v) {
            double d;
            try {
                d = Double.parseDouble(v.strip());
            } catch (NumberFormatException e) {
                return false;
            }
            return switch (op) {
                case GE -> d >= bound;
                case GT -> d > bound;
                case LE -> d <= bound;
                case LT -> d < bound;
                case EQ -> d == bound;
                case NE -> d != bound;
            };
        }
    }

}
