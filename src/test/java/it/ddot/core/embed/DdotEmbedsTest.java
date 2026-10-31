package it.ddot.core.embed;

import it.ddot.core.DdotVocabulary;
import it.ddot.core.event.DdotTriple;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a host file's ddot.it is ({@link DdotEmbeds}) and the triples in it ({@link LocatedTriple#extract}):
 * the three ddot-hosting file types, host lines, and host-level {@code !!off} / {@code !!on} around and
 * across chunks. Moved here from the IntelliJ plugin.
 */
class DdotEmbedsTest {

    @Test
    void ddotFileIsOneChunk() {
        String src = "Alice ..knows.. Bob\n";
        assertEquals(List.of(src), DdotEmbeds.extract("ddot", src));
    }

    @Test
    void markdownExtractsDdotFencesAndIgnoresOthers() {
        String md = String.join("\n",
                "# Title",
                "```java",
                "not ddot",
                "```",
                "```ddot",
                "Alice ..knows.. Bob",
                "```",
                "text",
                "~~~ddot.it",
                "Carol ..likes.. Tea",
                "~~~");
        assertEquals(List.of("Alice ..knows.. Bob", "Carol ..likes.. Tea"), DdotEmbeds.extract("md", md));
    }

    @Test
    void asciidocExtractsListingBlocks() {
        String adoc = String.join("\n",
                "= Doc",
                "[source,ddot]",
                "----",
                "Alice ..knows.. Bob",
                "----",
                "",
                "[ddot]",
                "....",
                "Carol ..likes.. Tea",
                "....");
        assertEquals(List.of("Alice ..knows.. Bob", "Carol ..likes.. Tea"), DdotEmbeds.extract("adoc", adoc));
    }

    @Test
    void unsupportedExtensionYieldsNothing() {
        assertTrue(DdotEmbeds.extract("txt", "Alice ..knows.. Bob").isEmpty());
        assertFalse(DdotEmbeds.isSupportedExtension("txt"));
        assertTrue(DdotEmbeds.isSupportedExtension("MD"));
        assertTrue(DdotEmbeds.isSupportedExtension("ddot"));
        assertTrue(DdotEmbeds.isSupportedExtension("adoc"));
    }

    // ---- LocatedTriple.extract -------------------------------------------------------------

    @Test
    void extractsFullTriples() {
        String src = String.join("\n",
                "Alice ..likes.. Tea",
                "Project Eagle .... Moonshot");   // untyped → DdotVocabulary.LINKS_TO
        assertEquals(List.of(
                        new LocatedTriple(new DdotTriple("Alice", "likes", "Tea"), 1),
                        new LocatedTriple(new DdotTriple("Project Eagle", DdotVocabulary.LINKS_TO, "Moonshot"), 2)),
                LocatedTriple.extract("ddot", src, "input.ddot"));
    }

    @Test
    void locatesTriplesInTheHostFile() {
        String md = String.join("\n",
                "# Title",                  // 1
                "",                         // 2
                "```ddot",                  // 3
                "Alice ..likes.. Tea",      // 4
                "..drinks.. Coffee",        // 5
                "```",                      // 6
                "text",                     // 7
                "```ddot",                  // 8
                "Bob ..knows.. Alice",      // 9
                "```");
        assertEquals(List.of(4, 5, 9), lines(LocatedTriple.extract("md", md, "t.md")));

        String adoc = String.join("\n",
                "= Doc",                    // 1
                "",                         // 2
                "[source,ddot]",            // 3
                "----",                     // 4
                "",                         // 5
                "Alice ..likes.. Tea",      // 6
                "----");
        assertEquals(List.of(6), lines(LocatedTriple.extract("adoc", adoc, "t.adoc")));
    }

    @Test
    void carriesMetadata() {
        assertEquals(List.of(new LocatedTriple.Meta("since", "2025")),
                LocatedTriple.extract("ddot", "A ..r.. B ,, ..since.. 2025\n", "t.ddot").getFirst().meta());
    }

    @Test
    void skipsDisabledSpans() {
        String src = String.join("\n",
                "ddot.it/off",
                "X ..secret.. Y",
                "ddot.it/on",
                "A ..r.. B");
        assertEquals(List.of(new LocatedTriple(new DdotTriple("A", "r", "B"), 4)),
                LocatedTriple.extract("ddot", src, "input.ddot"));
    }

    // ---- host-level `!!off` / `!!on` around and across chunks -----------------------------

    @Test
    void hostCommentOffExcludesFollowingChunks() {
        String adoc = String.join("\n",
                "// https://ddot.it/off",
                "[ddot]",
                "----",
                "aaa ..fooRel.. bbb",
                "----",
                "prose",
                "// ddot.it/on",
                "[ddot]",
                "----",
                "Alice ..knows.. Bob",
                "----", "");
        List<DdotEmbeds.Chunk> chunks = DdotEmbeds.extractChunks("adoc", adoc);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).startsOff());
        assertFalse(chunks.get(1).startsOff());
        assertEquals(List.of(new DdotTriple("Alice", "knows", "Bob")), triples(LocatedTriple.extract("adoc", adoc, "t.adoc")));
    }

    @Test
    void offStateThreadsAcrossChunks() {
        String md = String.join("\n",
                "```ddot",
                "!!off",
                "X ..hiddenRel.. Y",
                "```",
                "prose in between",
                "```ddot",
                "A ..stillHidden.. B",
                "```",
                "<!-- !!on -->",
                "```ddot",
                "C ..visible.. D",
                "```", "");
        List<DdotEmbeds.Chunk> chunks = DdotEmbeds.extractChunks("md", md);
        assertEquals(3, chunks.size());
        assertFalse(chunks.get(0).startsOff());   // the !!off is INSIDE it
        assertTrue(chunks.get(1).startsOff(), "off left open in chunk 1 keeps chunk 2 off");
        assertFalse(chunks.get(2).startsOff());
        assertEquals(List.of(new DdotTriple("C", "visible", "D")), triples(LocatedTriple.extract("md", md, "test.md")));
    }

    @Test
    void onInsideAChunkReenablesItsTail() {
        String md = String.join("\n",
                "<!-- !!off -->",
                "```ddot",
                "hidden ..h.. x",
                "!!on",
                "seen ..s.. y",
                "```", "");
        List<DdotEmbeds.Chunk> chunks = DdotEmbeds.extractChunks("md", md);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).startsOff());
        assertEquals(List.of(new DdotTriple("seen", "s", "y")), triples(LocatedTriple.extract("md", md, "t.md")));
    }

    private static List<Integer> lines(List<LocatedTriple> located) {
        return located.stream().map(LocatedTriple::line).toList();
    }

    private static List<DdotTriple> triples(List<LocatedTriple> located) {
        return located.stream().map(LocatedTriple::triple).toList();
    }
}
