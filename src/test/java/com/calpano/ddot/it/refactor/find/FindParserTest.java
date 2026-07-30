package com.calpano.ddot.it.refactor.find;

import com.calpano.ddot.it.refactor.MatchTerm;
import com.calpano.ddot.it.refactor.RuleSyntaxException;
import com.calpano.ddot.it.refactor.StringMatcher;
import com.calpano.ddot.it.refactor.TriplePattern;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parses the {@code !!find} surface (Position 15 of {@code refactor/rules-next.adoc}). Each test mirrors one
 * worked example (Q1–Q18) from that document. Execution of the rewrite verbs is covered by
 * {@link FindRewriteExecutionTest}.
 */
@SuppressWarnings("OverlyCoupledClass")
class FindParserTest {

    // ---- Q1–Q11: query verbs -------------------------------------------------

    @Test
    void q1_verbOmitted_defaultsToSelectAll() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  tool:figma ..?p.. ?o
                """);
        assertThat(q.verb()).isInstanceOf(DoVerb.Select.class);
        assertThat(((DoVerb.Select) q.verb()).isSelectAll()).isTrue();
        assertThat(q.where()).hasSize(1);
    }

    @Test
    void q2_join_verbLast() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?t ..has type.. tool
                  ?t ..reads.. ?fmt
                SELECT ?t ?fmt
                """);
        assertThat(q.where()).hasSize(2);
        assertThat(q.verb()).isEqualTo(new DoVerb.Select(List.of("?t", "?fmt")));
    }

    @Test
    void q3_ask() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  tool:figma ..online.. yes
                ASK
                """);
        assertThat(q.verb()).isInstanceOf(DoVerb.Ask.class);
    }

    @Test
    void q4_describe() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?t ..reads.. gml
                DESCRIBE ?t
                """);
        assertThat(q.verb()).isEqualTo(new DoVerb.Describe(List.of("?t")));
    }

    @Test
    void q5_filter_orderBy_limit() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?f ..popularity.. ?pop
                FILTER (?pop >= 8)
                SELECT ?f ?pop
                ORDER BY DESC(?pop)
                LIMIT 5
                """);
        assertThat(q.where()).containsExactly(
                new MatchElement.Pattern(pattern("?f", "popularity", "?pop")),
                new MatchElement.Filter("?pop >= 8"));
        assertThat(q.modifiers()).containsExactly("ORDER BY DESC(?pop)", "LIMIT 5").inOrder();
    }

    @Test
    void q6_optional() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?f ..has type.. format
                OPTIONAL { ?f ..pill label.. ?label }
                SELECT ?f ?label
                """);
        MatchElement opt = q.where().get(1);
        assertThat(opt).isInstanceOf(MatchElement.Optional.class);
        assertThat(((MatchElement.Optional) opt).group()).hasSize(1);
    }

    @Test
    void q7_filterNotExists_theCheckCase() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?f ..has type.. format
                FILTER NOT EXISTS { ?f ..has type.. gio:Format }
                SELECT ?f
                """);
        MatchElement ex = q.where().get(1);
        assertThat(ex).isInstanceOf(MatchElement.Exists.class);
        assertThat(((MatchElement.Exists) ex).negated()).isTrue();
    }

    @Test
    void q8_union() {
        FindQuery q = FindParser.parseOne("""
                !!find
                { ?x ..has type.. tool } UNION { ?x ..has type.. service }
                SELECT ?x
                """);
        assertThat(q.where().getFirst()).isInstanceOf(MatchElement.Union.class);
        MatchElement.Union u = (MatchElement.Union) q.where().getFirst();
        assertThat(u.left()).hasSize(1);
        assertThat(u.right()).hasSize(1);
    }

    @Test
    void q9_construct_braceless() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?t ..reads.. ?fmt
                  ?t ..writes.. ?fmt
                CONSTRUCT
                  ?t ..round trips.. ?fmt
                """);
        assertThat(q.verb()).isInstanceOf(DoVerb.Construct.class);
        assertThat(((DoVerb.Construct) q.verb()).template()).hasSize(1);
        assertThat(q.where()).hasSize(2);
    }

    @Test
    void q10_aggregation() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?t ..reads.. ?fmt
                SELECT ?t (COUNT(?fmt) AS ?n)
                GROUP BY ?t
                HAVING (COUNT(?fmt) > 3)
                ORDER BY DESC(?n)
                """);
        assertThat(q.verb()).isEqualTo(new DoVerb.Select(List.of("?t", "(COUNT(?fmt) AS ?n)")));
        assertThat(q.modifiers()).containsExactly("GROUP BY ?t", "HAVING (COUNT(?fmt) > 3)", "ORDER BY DESC(?n)")
                .inOrder();
    }

    @Test
    void q11_tripleMetadataMatch() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?who ..leads.. Project Eagle ,, ..since.. ?since
                SELECT ?who ?since
                """);
        MatchElement.Pattern p = (MatchElement.Pattern) q.where().getFirst();
        assertThat(p.pattern()).isEqualTo(pattern("?who", "leads", "Project Eagle"));
        assertThat(p.meta()).hasSize(1);
        assertThat(p.meta().getFirst().key()).isEqualTo(MatchTerm.literal("since"));
        assertThat(p.meta().getFirst().value()).isEqualTo(MatchTerm.var("since"));
    }

    // ---- Q12–Q15: rewrite verbs (parse) --------------------------------------

    @Test
    void q12_replace_braceless() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?t ..online.. yes
                REPLACE
                  ?t ..has type.. online tool
                """);
        assertThat(q.isRewrite()).isTrue();
        assertThat(((DoVerb.Replace) q.verb()).template()).hasSize(1);
    }

    @Test
    void q13_replace_withFilterExistsGuard() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?s ..name.. ?o
                FILTER EXISTS { ?s ..has type.. Person }
                REPLACE
                  ?s ..full name.. ?o
                """);
        assertThat(q.where().get(1)).isInstanceOf(MatchElement.Exists.class);
        assertThat(((MatchElement.Exists) q.where().get(1)).negated()).isFalse();
    }

    @Test
    void q14_pureDeletion_bareReplace_andAddOnlyInsert() {
        FindQuery del = FindParser.parseOne("""
                !!find
                  ?s ..deprecated.. yes
                REPLACE
                """);
        assertThat(((DoVerb.Replace) del.verb()).template()).isEmpty();

        FindQuery ins = FindParser.parseOne("""
                !!find
                  ?t ..has type.. tool
                INSERT
                  ?t ..verified.. no
                """);
        assertThat(ins.verb()).isInstanceOf(DoVerb.Insert.class);
    }

    @Test
    void q15a_typingFunctionAsPatternTerm() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?f ..popularity.. number(?pop)
                FILTER (?pop >= 8)
                SELECT ?f ?pop
                """);
        MatchElement.Pattern p = (MatchElement.Pattern) q.where().getFirst();
        // number(?pop) → a var bound with a numeric regex constraint
        assertThat(p.pattern().o().var()).isEqualTo("pop");
        assertThat(p.pattern().o().constraint()).isInstanceOf(StringMatcher.Regex.class);
        assertThat(p.pattern().o().matches("42")).isTrue();
        assertThat(p.pattern().o().matches("big")).isFalse();
    }

    @Test
    void replace_inlineBraces() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?t ..online.. yes
                REPLACE { ?t ..has type.. online tool }
                """);
        assertThat(((DoVerb.Replace) q.verb()).template()).hasSize(1);
    }

    // ---- Q16–Q18: layout + warn ----------------------------------------------

    @Test
    void q16_layout_withOthersLast() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?x ..has type.. A
                LAYOUT
                  ?x ..a.. ?_
                  ?x ..b.. ?_
                  ?x ..c.. ?_
                  ?x ..?other.. ?_
                """);
        DoVerb.Layout l = (DoVerb.Layout) q.verb();
        assertThat(l.relations()).containsExactly("a", "b", "c").inOrder();
        assertThat(l.othersIndex()).isEqualTo(3);
    }

    @Test
    void q17_layout_othersInTheMiddle() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?x ..has type.. B
                LAYOUT
                  ?x ..c.. ?_
                  ?x ..name.. ?_
                  ?x ..?other.. ?_
                  ?x ..b.. ?_
                """);
        DoVerb.Layout l = (DoVerb.Layout) q.verb();
        assertThat(l.relations()).containsExactly("c", "name", "b").inOrder();
        assertThat(l.othersIndex()).isEqualTo(2);
    }

    @Test
    void q18_warn_onTypeConflict() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?x ..has type.. A
                  ?x ..has type.. B
                WARN ?x is typed both A and B — property layout is ambiguous
                """);
        DoVerb.Warn w = (DoVerb.Warn) q.verb();
        assertThat(w.severity()).isEqualTo(DoVerb.Warn.Severity.WARN);
        assertThat(w.message()).isEqualTo("?x is typed both A and B — property layout is ambiguous");
        assertThat(q.where()).hasSize(2);
    }

    // ---- region mechanics ----------------------------------------------------

    @Test
    void params_nameAndPlacement() {
        FindQuery q = FindParser.parseOne("""
                !!find?name=PromoteOnline&placement=end-of-file
                  ?t ..online.. yes
                REPLACE
                  ?t ..has type.. online tool
                """);
        assertThat(q.name()).isEqualTo("PromoteOnline");
        assertThat(q.params()).containsEntry("placement", "end-of-file");
    }

    @Test
    void customEndTerminator_allowsBlankLinesInside() {
        FindQuery q = FindParser.parseOne("""
                !!find?end=DONE
                  ?t ..has type.. tool

                  ?t ..reads.. ?fmt
                SELECT ?t ?fmt
                DONE
                """);
        assertThat(q.where()).hasSize(2);
    }

    @Test
    void blankLineTerminatesRegion() {
        List<FindQuery> qs = FindParser.parse("""
                !!find
                  ?t ..has type.. tool
                SELECT ?t

                This prose is outside the region and ignored.

                !!find
                  ?f ..has type.. format
                ASK
                """);
        assertThat(qs).hasSize(2);
        assertThat(qs.get(0).verb()).isInstanceOf(DoVerb.Select.class);
        assertThat(qs.get(1).verb()).isInstanceOf(DoVerb.Ask.class);
    }

    @Test
    void multipleRegions_inOneDocument() {
        assertThat(FindParser.parse("""
                intro text

                !!find
                  ?t ..online.. yes
                REPLACE
                  ?t ..has type.. online tool

                more text

                !!find
                  ?s ..deprecated.. yes
                REPLACE
                """)).hasSize(2);
    }

    @Test
    void syntaxError_reportsLine() {
        RuleSyntaxException ex = assertThrows(RuleSyntaxException.class, () -> FindParser.parseOne("""
                !!find
                  not a triple
                SELECT ?x
                """));
        assertThat(ex.line()).isEqualTo(2);
    }

    // ---- comments (// only, whitespace-preceded) -----------------------------

    @Test
    void headerComment_doesNotHideRegion() {
        FindQuery q = FindParser.parseOne("""
                !!find                 // promote online tools
                  ?t ..online.. yes
                REPLACE
                  ?t ..has type.. online tool
                """);
        assertThat(q.isRewrite()).isTrue();
        assertThat(q.where()).hasSize(1);
    }

    @Test
    void headerComment_withParams() {
        FindQuery q = FindParser.parseOne("""
                !!find?name=Promote      // a named region
                  ?t ..online.. yes
                ASK
                """);
        assertThat(q.name()).isEqualTo("Promote");
    }

    @Test
    void endOfLineComments_areStripped() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?t ..has type.. tool     // a comment
                  ?t ..reads.. ?fmt        // another comment
                SELECT ?t ?fmt             // projection
                """);
        assertThat(q.where()).containsExactly(
                new MatchElement.Pattern(pattern("?t", "has type", "tool")),
                new MatchElement.Pattern(pattern("?t", "reads", "?fmt")));
        assertThat(q.verb()).isEqualTo(new DoVerb.Select(List.of("?t", "?fmt")));
    }

    @Test
    void fullLineComment_isSkipped_notARegionTerminator() {
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?t ..has type.. tool
                  // this explains the next line
                  ?t ..reads.. ?fmt
                SELECT ?t ?fmt
                """);
        assertThat(q.where()).hasSize(2);
    }

    @Test
    void hashIsNotAComment() {
        // '#' is no longer a comment marker; a trailing '# …' stays part of the object
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?t ..has tag.. #important
                SELECT ?t
                """);
        MatchElement.Pattern p = (MatchElement.Pattern) q.where().getFirst();
        assertThat(p.pattern().o()).isEqualTo(MatchTerm.literal("#important"));
    }

    @Test
    void slashesInsideTerm_areNotAComment() {
        // '//' inside a term (no whitespace before) stays part of the object (a URL)
        FindQuery q = FindParser.parseOne("""
                !!find
                  ?p ..links to.. http://h/a
                SELECT ?p
                """);
        MatchElement.Pattern p = (MatchElement.Pattern) q.where().getFirst();
        assertThat(p.pattern().o()).isEqualTo(MatchTerm.literal("http://h/a"));
    }

    private static TriplePattern pattern(String s, String p, String o) {
        return com.calpano.ddot.it.refactor.RuleBuilder.pattern().s(term(s)).p(term(p)).o(term(o)).build();
    }

    /** Mirrors {@code TermParser} for the constant/var cases the assertions use. */
    private static MatchTerm term(String t) {
        return t.startsWith("?") ? MatchTerm.var(t.substring(1)) : MatchTerm.literal(t);
    }
}
