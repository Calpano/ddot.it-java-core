package it.ddot.core.lint;

import it.ddot.core.embed.LocatedTriple;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** What {@link DdotAnalysis} reports per file and per project, and where. */
class DdotAnalysisTest {

    /** Files as name → text; the extension is the name's. */
    private static DdotAnalysis analyse(Map<String, String> files) {
        Map<String, List<LocatedTriple>> byFile = new LinkedHashMap<>();
        files.forEach((name, text) -> byFile.put(name, LocatedTriple.extract(extension(name), text, name)));
        return DdotAnalysis.of(byFile);
    }

    private static String extension(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** Every finding of every file, one {@link Finding#describe()} line each. */
    private static String report(Map<String, String> files) {
        DdotAnalysis analysis = analyse(files);
        StringBuilder sb = new StringBuilder();
        files.forEach((name, text) -> {
            for (Finding f : analysis.findings(name, extension(name), text)) sb.append(f.describe()).append('\n');
        });
        return sb.toString();
    }

    private static String report(String name, String text) {
        return report(Map.of(name, text));
    }

    // ---- DL023: a relation on both sides ------------------------------------------------------

    @Test
    void relationPlacedUnderBothSides() {
        String message = "DL023 (WARN) 'depends on' lies under both 'links to' and 'is linked from', so it has no"
                + " direction. One of the statements that place it is probably the wrong way round.\n";
        assertEquals("a.ddot:1: " + message + "a.ddot:2: " + message, report("a.ddot", String.join("\n",
                "links to ..has subtype.. depends on",
                "is linked from ..has subtype.. depends on",
                "x ..depends on.. y", "")));
    }

    @Test
    void relationOnBothSidesThroughAnInverseThatContradictsItsSide() {
        List<Finding> found = analyse(Map.of("a.ddot", String.join("\n",
                "links to ..has subtype.. depends on",
                "links to ..has subtype.. required by",
                "depends on ..has inverse.. required by", ""))).projectFindings();
        assertThat(found.stream().map(f -> f.line() + " " + f.message().substring(0, f.message().indexOf(" lies"))).toList())
                .containsExactly("1 'depends on'", "2 'depends on'", "3 'depends on'",
                        "1 'required by'", "2 'required by'", "3 'required by'");
        // Line 2 places `depends on` too: through the inverse, it is what puts it under `is linked from`.
        assertThat(found.getFirst().related()).containsExactly(new Location("a.ddot", 2), new Location("a.ddot", 3));
    }

    @Test
    void relationOnBothSidesThroughAnAlias() {
        List<Finding> found = analyse(Map.of("a.ddot", String.join("\n",
                "links to ..has subtype.. uses",
                "used by ..is alias of.. uses",
                "is linked from ..has subtype.. used by", ""))).projectFindings();
        assertThat(found.stream().map(Finding::ruleId).distinct().toList()).containsExactly(DdotAnalysis.RELATION_ON_BOTH_SIDES);
        assertThat(found.stream().map(Finding::line).distinct().toList()).containsExactly(1, 2, 3);
    }

    @Test
    void relationsWithADirectionAreFine() {
        assertEquals("", report("a.ddot", String.join("\n",
                "links to ..has subtype.. depends on",
                "depends on ..has inverse.. required by",
                "has part ..has subtype.. contains",
                "x ..depends on.. y",
                "y ..required by.. z",
                "a ..mentions.. b",
                "a ..has similar.. c",
                "a ..has type.. T", "")));
    }

    @Test
    void relationsOnBothSidesAcrossFiles() {
        assertEquals("""
                a.ddot:1: DL023 (WARN) 'foo' lies under both 'links to' and 'is linked from', so it has no direction. One of the statements that place it is probably the wrong way round.
                b.md:2: DL023 (WARN) 'foo' lies under both 'links to' and 'is linked from', so it has no direction. One of the statements that place it is probably the wrong way round.
                """, report(new LinkedHashMap<>(Map.of(
                "a.ddot", "links to ..has subtype.. foo\n",
                "b.md", "```ddot\nfoo ..has supertype.. is linked from\n```\n"))).lines().sorted()
                .reduce("", (a, b) -> a + b + "\n"));
    }

    // ---- DL022: letter case -------------------------------------------------------------------

    @Test
    void caseVariantsAreMarkedOnEveryOccurrenceWithColumns() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a.ddot", "Project Eagle ..leads.. Moon\n");
        files.put("b.md", "# Notes\n```ddot\nAnn ..works on.. project eagle\n```\n");
        assertEquals("""
                a.ddot:1:1: DL022 (WARN) 'Project Eagle' differs only in letter case from 'project eagle'. ddot.it treats them as different entities.
                b.md:3:18: DL022 (WARN) 'project eagle' differs only in letter case from 'Project Eagle'. ddot.it treats them as different entities.
                """, report(files));
        Finding inMd = analyse(files).findings("b.md", "md", files.get("b.md")).getFirst();
        assertEquals(new Location("a.ddot", 1), inMd.related().getFirst());
        assertEquals("project eagle", "Ann ..works on.. project eagle".substring(inMd.startColumn(), inMd.endColumn()));
    }

    @Test
    void aliasStatementsNameACaseVariantOnPurpose() {
        assertEquals("", report("a.ddot", "Foo ..has alias.. foo\nfoo ..is alias of.. Foo\nFoo ..r.. x\n"));
    }

    // ---- per-file rules -----------------------------------------------------------------------

    @Test
    void incompleteTripleInAnEmbeddedBlockPointsAtTheSeparator() {
        String md = "# T\n\n```ddot\nAlice ..knows..\n```\n";
        List<Finding> found = DdotAnalysis.fileFindings("t.md", "md", md);
        assertEquals(1, found.size(), found::toString);
        Finding f = found.getFirst();
        assertEquals(DdotAnalysis.INCOMPLETE_TRIPLE, f.ruleId());
        assertEquals(4, f.line());
        assertEquals("..", "Alice ..knows..".substring(f.startColumn(), f.endColumn()));
    }

    @Test
    void linterWarningsAreMappedToHostLines() {
        String md = "# T\n```ddot\nS ... O\n```\n";
        List<Finding> found = DdotAnalysis.fileFindings("t.md", "md", md);
        assertThat(found.stream().map(f -> f.ruleId() + "@" + f.line()).toList()).containsExactly("DL010@3");
    }

    @Test
    void chunksThatStartOffAreNotLinted() {
        String md = "<!-- !!off -->\n```ddot\nS ... O\nAlice ..knows..\n```\n";
        assertThat(DdotAnalysis.fileFindings("t.md", "md", md)).isEmpty();
    }

    @Test
    void proseIsNotLinted() {
        assertThat(DdotAnalysis.fileFindings("t.md", "md", "# T\nsome ... prose ..knows..\n")).isEmpty();
    }

    // ---- DL024: a replaced name ---------------------------------------------------------------

    @Test
    void replacedNameIsMarkedWhereUsedButNotWhereDeclared() {
        assertEquals("a.ddot:2:7: DL024 (WARN) 'a' is replaced by 'has type'.\n", report("a.ddot", String.join("\n",
                "a ..is replaced by.. has type",
                "Ann ..a.. Person",
                "Bob ..has type.. Person", "")));
    }

    @Test
    void replacedNameLinksToTheReplacementStatement() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("vocab.ddot", "a ..is replaced by.. has type\n");
        files.put("data.ddot", "Ann ..a.. Person\n");
        List<Finding> found = analyse(files).findings("data.ddot", "ddot", files.get("data.ddot"));
        assertThat(found).hasSize(1);
        assertEquals(List.of(new Location("vocab.ddot", 1)), found.getFirst().related());
    }

    @Test
    void replacesIsTheInverseAndChainsAreFollowed() {
        assertEquals("""
                a.ddot:3:1: DL024 (WARN) 'old' is replaced by 'newest'.
                a.ddot:3:12: DL024 (WARN) 'mid' is replaced by 'newest'.
                """, report("a.ddot", String.join("\n",
                "mid ..replaces.. old",
                "newest ..replaces.. mid",
                "old ..is.. mid", "")));
    }

    @Test
    void namingStatementsAreNotMarked() {
        assertEquals("", report("a.ddot", String.join("\n",
                "a ..is replaced by.. has type",
                "a ..is alias of.. has type",
                "has type ..has alias.. a",
                "has type ..replaces.. a", "")));
    }

    @Test
    void anAliasSpellingOfIsReplacedByWorks() {
        assertEquals("a.ddot:3:9: DL024 (WARN) 'x' is replaced by 'y'.\n", report("a.ddot", String.join("\n",
                "replacedBy ..is alias of.. is replaced by",
                "x ..replacedBy.. y",
                "q ..r.. x", "")));
    }

    @Test
    void replacementsAreOfferedForFixes() {
        assertEquals(Map.of("a", List.of("has type")),
                analyse(Map.of("a.ddot", "a ..is replaced by.. has type\n")).replacements());
    }
}
