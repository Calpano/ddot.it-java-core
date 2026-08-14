package it.ddot.core.lint;


import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.event.IDdotEvent;
import it.ddot.core.event.IMeta;
import it.ddot.core.source.ISnippet;
import it.ddot.core.io.ISourceHandler;
import it.ddot.core.io.handler.Source2TripleEvents;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

/**
 * Unit tests for {@link DdotLinter}: the canonical-form FIX transform (DL001–DL009), the raw-line pitfall diagnostics
 * (DL010–DL012), and — most importantly — the hard invariants from {@code linting.adoc} (meaning-preserving,
 * idempotent, prose-safe, object-verbatim, verbatim/block sacred).
 */
class DdotLinterTest {

    private record T(String from, @Nullable String type, String to, @Nullable Map<String, String> meta) {}

    private static final List<String> SAMPLES = List.of("a  ..  knows  ..  b", "a....b", "a .. .. b", "..author..Alice", "John Doe ..leads.. Project Eagle ,,..since..2025", "a ..knows.. b ,, just a note", "page ..links to.. http://h/a..b", "x ..links to.. ../../y", "ddot.it/this ..author..Alice", "prose line, nothing here\nsecond prose line", "x ..has content.. !!block?end=END\n  raw ..line.. body\nEND", "!!off\nmessy ..a.. b\n!!on\nc..d..e");

    // ---- DL001–DL009: canonical spelling --------------------------------------------------------------
    private final DdotLinter linter = new DdotLinter();

    private static List<T> triples(String text) {
        List<IDdotEvent> events = new ArrayList<>();
        Kgraph kgraph = new Kgraph("t");
        ISourceHandler sink = new ISourceHandler() {
            @Override
            public void activeSubject(String s) {}

            @Override
            public @Nullable String activeSubject() {return null;}

            @Override
            public void onLine(String l, int n) {}

            @Override
            public void onTripleEvent(IDdotEvent t) {events.add(t);}

            @Override
            public void sourceEnd() {}

            @Override
            public void sourceResourceEnd() {}

            @Override
            public void sourceResourceStart(ISnippet a) {}

            @Override
            public void sourceStart() {}
        };
        Source2TripleEvents h = new Source2TripleEvents(sink);
        h.sourceStart();
        h.sourceResourceStart(ISnippet.ofPlainUri("test://doc"));
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) h.onLine(lines[i], i + 1);
        h.sourceEnd();
        List<T> out = new ArrayList<>();
        for (IDdotEvent e : events) {
            IMeta m = e.meta();
            out.add(new T(e.from(), e.type(), e.to(), m == null ? null : m.asMap()));
        }
        return out;
    }

    private static String warnId(String line) {
        List<Diagnostic> ds = new DdotLinter().lint(line).diagnostics();
        assertThat(ds).hasSize(1);
        return ds.getFirst().ruleId();
    }

    @Test
    void alreadyCanonicalIsNotRewritten() {
        LintResult r = linter.lint("a ..knows.. b\nProject Eagle .... Moonshot\n");
        assertThat(r.changes()).isEmpty();
    }

    @Test
    void blockInteriorIsUntouched() {
        String in = "x ..has content.. !!block?end=END\n  messy   ..line.. inside\nplain text\nEND\n";
        LintResult r = linter.lint(in);
        // command line canonicalised, interior verbatim, end marker verbatim
        assertThat(r.canonicalLines().getFirst()).isEqualTo("x ..has content.. !!block?end=END");
        assertThat(r.canonicalLines().get(1)).isEqualTo("  messy   ..line.. inside");
        assertThat(r.canonicalLines().get(2)).isEqualTo("plain text");
        assertThat(r.canonicalLines().get(3)).isEqualTo("END");
    }

    @Test
    void ddotItThisSubjectIsPreservedVerbatim() {
        assertThat(lint("ddot.it/this ..author..Alice")).isEqualTo("ddot.it/this ..author.. Alice");
    }

    @Test
    void dl001_typedTripleSpacing() {
        assertThat(lint("a  ..  knows  ..  b")).isEqualTo("a ..knows.. b");
        assertThat(lint("a..knows..b")).isEqualTo("a ..knows.. b");
        assertThat(lint("Project Eagle ..started in..2024")).isEqualTo("Project Eagle ..started in.. 2024");
    }

    @Test
    void dl002_untypedLinkSpacing() {
        assertThat(lint("a....b")).isEqualTo("a .... b");
        assertThat(lint("Project Eagle....Moonshot")).isEqualTo("Project Eagle .... Moonshot");
    }

    @Test
    void dl003_spacedUntypedOperatorCollapses() {
        assertThat(lint("a .. .. b")).isEqualTo("a .... b");
    }

    @Test
    void dl004_freeNoteMetadataIsCanonicalisedToBareComma() {
        assertThat(lint("a ..knows.. b ,,   just a note")).isEqualTo("a ..knows.. b ,, just a note");
    }

    @Test
    void dl004_metadataSeparatorSpacing() {
        assertThat(lint("John Doe ..leads.. Project Eagle ,,..since..2025")).isEqualTo("John Doe ..leads.. Project Eagle ,, ..since.. 2025");
    }

    @Test
    void dl006_continuationSpacing() {
        assertThat(lint("..author..Alice")).isEqualTo("..author.. Alice");
        assertThat(lint("  ..author..  Alice")).isEqualTo("..author.. Alice");
    }

    // ---- object verbatim ------------------------------------------------------------------------------

    @Test
    void dl008_tabsAtSlotEdgesBecomeSpaces() {
        assertThat(lint("a\t..knows..\tb")).isEqualTo("a ..knows.. b");
    }

    @Test
    void dl009_trailingWhitespaceStripped() {
        assertThat(lint("a ..knows.. b   ")).isEqualTo("a ..knows.. b");
    }

    @Test
    void dl010_deadDotRunIsWarnedNotFixed() {
        LintResult r = linter.lint("S ... O");
        assertThat(r.canonicalText()).isEqualTo("S ... O"); // never auto-fixed
        assertThat(r.diagnostics()).hasSize(1);
        assertThat(r.diagnostics().getFirst().ruleId()).isEqualTo("DL010");
        assertThat(r.diagnostics().getFirst().severity()).isEqualTo(Severity.WARN);
    }

    // ---- prose safety ---------------------------------------------------------------------------------

    @Test
    void dl010_fiveDotsIsDeadDotRun() {
        assertThat(warnId("S ..... O")).isEqualTo("DL010");
    }

    @Test
    void dl011_separatorButtsDots() {
        assertThat(warnId("S ...... O")).isEqualTo("DL011");
    }

    // ---- verbatim / block sacred ----------------------------------------------------------------------

    @Test
    void dl012_doesNotFireOnUniformlyGluedValidTriple() {
        LintResult r = linter.lint("a..knows..b");
        assertThat(r.hasWarningsOrErrors()).isFalse(); // valid intentional triple, no ".." pitfall
    }

    @Test
    void dl012_doesNotFireOnUrlObject() {
        LintResult r = linter.lint("x ..links to.. http://h/a..b");
        assertThat(r.hasWarningsOrErrors()).isFalse();
    }

    @Test
    void dl012_dotsInSubjectOrRelation() {
        assertThat(warnId("a..b ..reads.. o")).isEqualTo("DL012");
    }

    @Test
    void fullUrlOffRegion_isHonoured_inSyncWithReader() {
        // https://ddot.it/off must fence a region for the linter too (mirrors the reader)
        String in = "https://ddot.it/off\nmessy  ..line..  here\nhttps://ddot.it/on\na  ..b..  c\n";
        LintResult r = linter.lint(in);
        assertThat(r.canonicalLines().get(1)).isEqualTo("messy  ..line..  here"); // untouched inside off
        assertThat(r.canonicalLines().get(3)).isEqualTo("a ..b.. c");             // linted after on
    }

    // ---- DL010–DL012 diagnostics ----------------------------------------------------------------------

    @Test
    void invariant_idempotent() {
        for (String in : SAMPLES) {
            String once = lint(in);
            String twice = lint(once);
            assertThat(twice).isEqualTo(once);
        }
    }

    @Test
    void invariant_meaningPreserving() {
        for (String in : SAMPLES) {
            String out = lint(in);
            assertThat(triples(out)).isEqualTo(triples(in));
        }
    }

    @Test
    void invariant_meaningPreserving_reference() {
        String ref = """
                ddot.it/this ..author.. Alice
                Project Eagle ..started in.. 2024
                ..doc site.. example.com/docbase/8dcjsid
                John Doe ..leads.. Project Eagle ,, ..since.. 2025
                Project Eagle .... Moonshot
                """;
        // messy version of the same corpus
        String messy = """
                ddot.it/this ..author..Alice
                Project Eagle..started in..2024
                  ..doc site..  example.com/docbase/8dcjsid
                John Doe ..leads..Project Eagle ,,..since..2025
                Project Eagle .. .. Moonshot
                """;
        assertThat(lint(messy)).isEqualTo(ref);
        assertThat(triples(messy)).isEqualTo(triples(ref));
    }

    @Test
    void labelRelationTokenIsPreserved() {
        assertThat(lint("a ..!!label..  Alice")).isEqualTo("a ..!!label.. Alice");
    }

    @Test
    void objectInternalSpacingIsPreserved() {
        assertThat(lint("x ..has content..  two   spaces  ")).isEqualTo("x ..has content.. two   spaces");
    }

    @Test
    void objectRelativePathIsVerbatim() {
        assertThat(lint("x ..links to.. ../../y")).isEqualTo("x ..links to.. ../../y");
    }

    @Test
    void objectWithDotsIsVerbatim() {
        assertThat(lint("page ..links to.. http://h/a..b")).isEqualTo("page ..links to.. http://h/a..b");
    }

    @Test
    void offRegionIsUntouchedByDefault() {
        String in = "!!off\nmessy  ..line..  here\n!!on\na  ..b..  c\n";
        LintResult r = linter.lint(in);
        assertThat(r.canonicalLines().get(1)).isEqualTo("messy  ..line..  here"); // untouched inside off
        assertThat(r.canonicalLines().get(3)).isEqualTo("a ..b.. c");             // linted after on
    }

    // ---- hard invariants ------------------------------------------------------------------------------

    @Test
    void pureProseIsByteForByteNoOp() {
        String prose = "This is prose.\nIt mentions a.b and 3.0 but no triples.\n  Indented line.\n";
        LintResult r = linter.lint(prose);
        assertThat(r.canonicalText()).isEqualTo(prose);
        assertThat(r.changes()).isEmpty();
        assertThat(r.hasWarningsOrErrors()).isFalse();
    }

    @Test
    void ruleSelectScopesDiagnostics() {
        DdotLinter only12 = new DdotLinter(new DdotLinter.Options(false, java.util.Set.of("DL012"), null));
        assertThat(only12.lint("S ... O").hasWarningsOrErrors()).isFalse(); // DL010 not selected
    }

    @Test
    void singleDotPrefixTokensAreNotTriples() {
        assertThat(lint("3.0 is a number")).isEqualTo("3.0 is a number");
        assertThat(lint("wait.. what")).isEqualTo("wait.. what");
    }

    @Test
    void standaloneCommandIsTrimmed() {
        assertThat(lint("   !!on   ")).isEqualTo("!!on");
    }

    // ---- meaning oracle: parse text into ordered triple events via the real reader --------------------

    @Test
    void verbatimLineIsUntouched() {
        assertThat(lint("a ..has content.. !!verbatim  raw  ..stuff..  here")).isEqualTo("a ..has content.. !!verbatim  raw  ..stuff..  here");
    }

    private String lint(String s) {
        return linter.lint(s).canonicalText();
    }

}
