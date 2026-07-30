package com.calpano.ddot.it.refactor;

import org.junit.jupiter.api.Test;

import static com.google.common.truth.Truth.assertThat;

/** The internal function library: FILTER-clause predicate parsing + the typing shapes. */
class RuleFunctionsTest {

    private static boolean matches(String filterExpr, String value) {
        RewriteRule.Filter f = RuleFunctions.parseFilter(filterExpr);
        assertThat(f).isNotNull();
        return f.matcher().matches(value);
    }

    private static void var(String filterExpr, String expectedVar) {
        RewriteRule.Filter f = RuleFunctions.parseFilter(filterExpr);
        assertThat(f).isNotNull();
        assertThat(f.var()).isEqualTo(expectedVar);
    }

    // ---- numeric comparison --------------------------------------------------

    @Test
    void numericCompare_operators() {
        assertThat(matches("?pop >= 8", "8")).isTrue();
        assertThat(matches("?pop >= 8", "7")).isFalse();
        assertThat(matches("?pop > 8", "8")).isFalse();
        assertThat(matches("?pop <= 8", "8")).isTrue();
        assertThat(matches("?pop < 8", "8")).isFalse();
        assertThat(matches("?pop = 8", "8")).isTrue();
        assertThat(matches("?pop == 8", "8.0")).isTrue();
        assertThat(matches("?pop != 8", "9")).isTrue();
        assertThat(matches("?pop != 8", "8")).isFalse();
    }

    @Test
    void numericCompare_decimalsAndNegatives() {
        assertThat(matches("?x >= -2.5", "-2.5")).isTrue();
        assertThat(matches("?x < 0", "-1")).isTrue();
        var("?score > 3.14", "score");
    }

    @Test
    void numericCompare_nonNumberNeverMatches() {
        assertThat(matches("?pop >= 8", "lots")).isFalse();
    }

    // ---- regex ---------------------------------------------------------------

    @Test
    void regex_functionForm() {
        assertThat(matches("regex(?s, \"^tool:.*\")", "tool:figma")).isTrue();
        assertThat(matches("regex(?s, \"^tool:.*\")", "app:x")).isFalse();
    }

    @Test
    void regex_matchOpSugar() {
        assertThat(matches("?s =~ /^tool:/", "tool:figma")).isTrue();
        assertThat(matches("?s =~ /^tool:/", "app:x")).isFalse();
        var("?s =~ /x/", "s");
    }

    // ---- string predicates ---------------------------------------------------

    @Test
    void strStarts_strEnds_contains() {
        assertThat(matches("strStarts(?s, \"tool:\")", "tool:x")).isTrue();
        assertThat(matches("strStarts(?s, \"tool:\")", "app:x")).isFalse();
        assertThat(matches("strEnds(?f, \".gml\")", "a.gml")).isTrue();
        assertThat(matches("contains(?o, \"mid\")", "a-mid-b")).isTrue();
        assertThat(matches("contains(?o, \"mid\")", "abc")).isFalse();
    }

    // ---- typing predicates ---------------------------------------------------

    @Test
    void typingPredicates_inFilterPosition() {
        assertThat(matches("number(?v)", "42")).isTrue();
        assertThat(matches("number(?v)", "big")).isFalse();
        assertThat(matches("uri(?v)", "https://x/y")).isTrue();
        assertThat(matches("date(?v)", "2026-07-06")).isTrue();
        assertThat(matches("date(?v)", "yesterday")).isFalse();
    }

    // ---- len / count (still work, now via the library) -----------------------

    @Test
    void lenAndCount() {
        assertThat(matches("len(?o) >= 5", "Alexander")).isTrue();
        assertThat(matches("len(?o) >= 5", "Bob")).isFalse();
        assertThat(matches("count(?o, \",\") == 2", "a,b,c")).isTrue();
        assertThat(matches("count(?o, \",\") == 2", "a,b")).isFalse();
    }

    // ---- typing shape lookup -------------------------------------------------

    @Test
    void typing_lookup() {
        assertThat(RuleFunctions.typing("number")).isInstanceOf(StringMatcher.Regex.class);
        assertThat(RuleFunctions.typing("nope")).isNull();
    }

    // ---- unrecognised → null (caller reports non-executable) -----------------

    @Test
    void unrecognised_returnsNull() {
        assertThat(RuleFunctions.parseFilter("?old != slugify(?f)")).isNull(); // cross-var / host function
        assertThat(RuleFunctions.parseFilter("len(?o) != 5")).isNull();        // no single-range form
        assertThat(RuleFunctions.parseFilter("bogus(?o)")).isNull();           // unknown function
    }
}
