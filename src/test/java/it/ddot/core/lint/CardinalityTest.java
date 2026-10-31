package it.ddot.core.lint;

import it.ddot.core.embed.LocatedTriple;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cardinality checking per ddot-it-vocabulary.adoc §Cardinality ({@value DdotAnalysis#CARDINALITY},
 * {@value DdotAnalysis#CARDINALITY_VALUE}), under CDS. Moved here from the IntelliJ plugin.
 */
class CardinalityTest {

    private static List<Finding> check(String... files) {
        Map<String, List<LocatedTriple>> byUrl = new LinkedHashMap<>();
        for (int i = 0; i < files.length; i++) {
            byUrl.put("f" + i + ".ddot", LocatedTriple.extract("ddot", files[i], "f" + i + ".ddot"));
        }
        return DdotAnalysis.of(byUrl).projectFindings().stream()
                .filter(f -> f.ruleId().equals(DdotAnalysis.CARDINALITY) || f.ruleId().equals(DdotAnalysis.CARDINALITY_VALUE))
                .toList();
    }

    private static String show(List<Finding> fs) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : fs) {
            sb.append(f.url()).append(':').append(f.line()).append(' ').append(f.message())
                    .append(" [stated at ").append(f.related().getFirst()).append("]\n");
        }
        return sb.toString();
    }

    @Test
    void maxOnRelationAcrossFiles() {
        assertEquals("""
                f1.ddot:1 'John' has 2 values for 'email', but at most 1 is allowed. [stated at f0.ddot:1]
                f1.ddot:2 'John' has 2 values for 'email', but at most 1 is allowed. [stated at f0.ddot:1]
                """, show(check(
                "email ..max cardinality.. 1\n",
                "John ..email.. a@x\n..email.. b@x\nMary ..email.. c@x\n")));
    }

    @Test
    void cardinalityShorthandSetsBothBounds() {
        assertEquals("""
                f0.ddot:2 'A' has 1 value for 'pair', but at least 2 are required. [stated at f0.ddot:1]
                f0.ddot:3 'B' has 3 values for 'pair', but at most 2 are allowed. [stated at f0.ddot:1]
                f0.ddot:4 'B' has 3 values for 'pair', but at most 2 are allowed. [stated at f0.ddot:1]
                f0.ddot:5 'B' has 3 values for 'pair', but at most 2 are allowed. [stated at f0.ddot:1]
                """, show(check(String.join("\n",
                "pair ..cardinality.. 2",
                "A ..pair.. x",
                "B ..pair.. x",
                "..pair.. y",
                "..pair.. z",
                "C ..pair.. x",
                "..pair.. y", ""))));
    }

    @Test
    void singleValuedAndRequiredTypes() {
        assertEquals("""
                f0.ddot:3 'X' has 2 values for 'id', but at most 1 is allowed. [stated at f0.ddot:1]
                f0.ddot:4 'X' has 2 values for 'id', but at most 1 is allowed. [stated at f0.ddot:1]
                """, show(check(String.join("\n",
                "id ..has type.. single valued",
                "name ..has type.. required",
                "X ..id.. 1",
                "..id.. 2",
                "..name.. Ex", ""))));
    }

    @Test
    void boundOnRangeScopedByDomainIncludingSubtypesAndMissingValues() {
        assertEquals("""
                f1.ddot:2 'core' (as a 'Maven module') has 2 values for 'has Maven group', but at most 1 is allowed. [stated at f0.ddot:3]
                f1.ddot:3 'core' (as a 'Maven module') has 2 values for 'has Maven group', but at most 1 is allowed. [stated at f0.ddot:3]
                f1.ddot:4 'web' is a 'Maven module' and needs at least 1 value for 'has Maven group', but has none. [stated at f0.ddot:3]
                """, show(check(String.join("\n",
                        "has Maven group ..has type.. inferred relation",
                        "has Maven group ..domain.. Maven module",
                        "has Maven group ..range.. Maven group ,, ..cardinality.. 1",
                        "has Maven group ..relation.. has",
                        "Maven module ..has subtype.. Java module", ""),
                String.join("\n",
                        "core ..has type.. Maven module",
                        "core ..has Maven group.. it.ddot",
                        "..has Maven group.. com.calpano",
                        "web ..has type.. Java module",
                        "app ..has type.. Maven module",
                        "app ..has Maven group.. it.ddot",
                        "Cat ..has type.. Animal", ""))));
    }

    @Test
    void docDomainAndDocRange() {
        assertEquals("""
                f0.ddot:6 'app' (as a 'Maven module') has 2 values for 'has Maven parent', but at most 1 is allowed. [stated at f0.ddot:5]
                f0.ddot:7 'app' (as a 'Maven module') has 2 values for 'has Maven parent', but at most 1 is allowed. [stated at f0.ddot:5]
                """, show(check(String.join("\n",
                        "has Maven parent ..has type.. relation",
                        "has Maven parent ..has inverse.. is Maven parent of",
                        "has Maven parent ..has supertype.. has context",
                        "has Maven parent ..doc:domain.. Maven module",
                        "has Maven parent ..doc:range.. Maven module ,, ..max cardinality.. 1",
                        "app ..has Maven parent.. parent-a",
                        "app ..has Maven parent.. parent-b",
                        "app ..has type.. Maven module",
                        "lib ..has type.. Maven module", ""))));   // max only: 'lib' without a parent is fine
    }

    @Test
    void metadataOnAnOrdinaryTripleIsNoBound() {
        // This would state that 'Person' has the address 'Address' — it is data, not a bound.
        assertEquals("", show(check(String.join("\n",
                "Person ..has address.. Address ,, ..max cardinality.. 1",
                "Ann ..has type.. Person",
                "Ann ..has address.. A1",
                "Ann ..has address.. A2", ""))));
    }

    @Test
    void withoutDomainOnlySubjectsUsingTheRelationAreChecked() {
        assertEquals("", show(check("email ..min cardinality.. 1\nAnn ..has type.. Person\nBob ..email.. b@x\n")));
    }

    @Test
    void invalidBoundIsReportedAtItsStatement() {
        assertEquals("f0.ddot:1 'max cardinality' must be a whole number, but is 'many'. [stated at f0.ddot:1]\n",
                show(check("x ..max cardinality.. many\n")));
    }

    @Test
    void sameTripleTwiceCountsOnce() {
        assertEquals("", show(check("email ..max cardinality.. 1\nJohn ..email.. a@x\nJohn ..email.. a@x\n")));
    }

    // ---- aliases come from the files ------------------------------------------------------

    @Test
    void aliasesCountOnlyWhereDeclared() {
        String data = "x ..lowerBound.. 2\nA ..x.. one\n";
        assertEquals("", show(check(data)));  // `lowerBound` means nothing by itself
        assertEquals("f1.ddot:2 'A' has 1 value for 'x', but at least 2 are required. [stated at f1.ddot:1]\n",
                show(check("min cardinality ..has alias.. lowerBound\n", data)));
    }

    @Test
    void aliasesOfTypeAndDataRelationsAreResolved() {
        assertEquals("""
                f1.ddot:4 'Ann' (as a 'Person') has 2 values for 'email', but at most 1 is allowed. [stated at f1.ddot:2]
                f1.ddot:5 'Ann' (as a 'Person') has 2 values for 'email', but at most 1 is allowed. [stated at f1.ddot:2]
                """, show(check(String.join("\n",
                        "a ..is alias of.. has type",
                        "email ..has alias.. mail",
                        "has alias ..has alias.. alias",
                        "max cardinality ..alias.. upperBound", ""),
                String.join("\n",
                        "email ..domain.. Person",
                        "email ..range.. Text ,, ..upperBound.. 1",
                        "Ann ..a.. Person",
                        "Ann ..email.. a@x",
                        "Ann ..mail.. b@x", ""))));
    }

    /** In CDS, {@code replaces} is a kind of {@code has similar}, not a second name: {@code a} types nothing. */
    @Test
    void isReplacedByIsNoAlias() {
        assertEquals("""
                f0.ddot:5 'Ann' has 2 values for 'email', but at most 1 is allowed. [stated at f0.ddot:2]
                f0.ddot:6 'Ann' has 2 values for 'email', but at most 1 is allowed. [stated at f0.ddot:2]
                """, show(check(String.join("\n",
                "a ..is replaced by.. has type",
                "email ..max cardinality.. 1",
                "email ..domain.. Person",
                "Ann ..a.. Person",
                "Ann ..email.. a@x",
                "Ann ..email.. b@x", ""))));
    }

    // ---- what CDS entails counts ----------------------------------------------------------

    @Test
    void valuesStatedTheOtherWayRoundCount() {
        assertEquals("""
                f0.ddot:2 'rex' has 2 values for 'has part', but at most 1 is allowed. [stated at f0.ddot:1]
                f0.ddot:3 'rex' has 2 values for 'has part', but at most 1 is allowed. [stated at f0.ddot:1]
                """, show(check(String.join("\n",
                "has part ..max cardinality.. 1",
                "rex ..has part.. tail",
                "head ..is part of.. rex", ""))));
    }

    @Test
    void valuesOfSubRelationsCount() {
        assertEquals("""
                f0.ddot:3 'Ann' has 2 values for 'contact', but at most 1 is allowed. [stated at f0.ddot:1]
                f0.ddot:4 'Ann' has 2 values for 'contact', but at most 1 is allowed. [stated at f0.ddot:1]
                """, show(check(String.join("\n",
                "contact ..max cardinality.. 1",
                "contact ..has subtype.. email",
                "Ann ..email.. a@x",
                "Ann ..contact.. Bob", ""))));
    }

    @Test
    void twoNamesOfOneValueCountOnce() {
        assertEquals("", show(check(String.join("\n",
                "email ..max cardinality.. 1",
                "a@x ..same as.. ann@x",
                "Ann ..email.. a@x",
                "Ann ..email.. ann@x", ""))));
    }

    @Test
    void chainsAreNotFollowed() {
        assertEquals("", show(check(String.join("\n",
                "has part ..max cardinality.. 1",
                "rex ..has part.. tail",
                "tail ..has part.. tip", ""))));
    }
}
