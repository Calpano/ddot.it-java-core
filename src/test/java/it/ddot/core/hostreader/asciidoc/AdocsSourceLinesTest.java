package it.ddot.core.hostreader.asciidoc;

import it.ddot.core.hostreader.asciidoc.doc.AdocDocument;
import it.ddot.core.hostreader.asciidoc.doc.Adocs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdocDocument.Block#startLine} and {@link AdocDocument.Block#endLine}.
 *
 * <p>The span has to own the block's text outright, so a caller can replace one block in a file
 * without reformatting anything around it. Two properties matter: the spans are ordered and never
 * overlap, and each one quotes back exactly the source it stands for.
 */
class AdocsSourceLinesTest {

    private static final List<String> LINES = List.of(
            "= Title",              // 1
            ":toc:",                // 2
            "",                     // 3
            "[[intro]]",            // 4
            "== Introduction",      // 5
            "",                     // 6
            "First paragraph",      // 7
            "over two lines",       // 8
            "",                     // 9
            "[source,java]",        // 10
            "----",                 // 11
            "class Foo {}",         // 12
            "----",                 // 13
            "",                     // 14
            "// a comment",         // 15
            "// still commenting",  // 16
            "",                     // 17
            ".Block title",         // 18
            "* one",                // 19
            "* two");               // 20

    @Test
    void aHeadlineSpanCoversItsAnchorLineToo() {
        AdocDocument.Block heading = blockAt(0);
        // The [[intro]] on line 4 belongs to the heading on line 5, or replacing the heading would
        // orphan its anchor.
        assertEquals(4, heading.startLine);
        assertEquals(5, heading.endLine);
    }

    @Test
    void aParagraphSpansItsOwnLines() {
        AdocDocument.Block paragraph = blockAt(1);
        assertEquals(7, paragraph.startLine);
        assertEquals(8, paragraph.endLine);
        assertEquals(LINES.subList(6, 8), paragraph.lines());
    }

    @Test
    void aFencedBlockSpansAttributeLineThroughClosingDelimiter() {
        AdocDocument.Block code = blockAt(2);
        assertEquals(10, code.startLine);
        assertEquals(13, code.endLine);
        assertEquals("[source,java]", LINES.get(code.startLine - 1));
        assertEquals("----", LINES.get(code.endLine - 1));
    }

    @Test
    void aRunOfLineCommentsIsOneSpan() {
        AdocDocument.Block comment = blockAt(3);
        assertEquals(15, comment.startLine);
        assertEquals(16, comment.endLine);
    }

    @Test
    void aBlockTitleSpansItsSingleLine() {
        AdocDocument.Block title = blockAt(4);
        assertEquals(18, title.startLine);
        assertEquals(18, title.endLine);
    }

    /** Ordered, non-overlapping and in bounds: the invariant a caller splices against. */
    @Test
    void spansAreOrderedAndDoNotOverlap() {
        int previousEnd = 0;
        for (AdocDocument.Block block : Adocs.parse(LINES).contents()) {
            assertTrue(block.startLine > previousEnd,
                    block.kind + " starts at " + block.startLine + ", after a block ending at " + previousEnd);
            assertTrue(block.endLine >= block.startLine, block.kind + " ends before it starts");
            assertTrue(block.endLine <= LINES.size(), block.kind + " ends past the last line");
            previousEnd = block.endLine;
        }
    }

    /** A document that was built rather than parsed reports 0, not a misleading line 1. */
    @Test
    void anUnparsedBlockHasNoSpan() {
        AdocDocument.Block block = new AdocDocument.Block(AdocDocument.Kind.Plain);
        assertEquals(0, block.startLine);
        assertEquals(0, block.endLine);
    }

    /**
     * The three container fences AsciiDoc has besides the verbatim ones. A bare {@code ====} is the
     * example delimiter, not a headline — a headline is {@code ==== Text}, with a title.
     */
    @Test
    void recognisesTheContainerFences() {
        List<String> lines = List.of(
                "= T", "", "--", "open", "--", "", "____", "quoted", "____",
                "", "====", "example", "====", "", "==== A Headline");
        AdocDocument doc = Adocs.parse(lines);
        List<AdocDocument.Kind> kinds = doc.contents().stream().map(b -> b.kind).toList();
        assertEquals(List.of(AdocDocument.Kind.Dash2, AdocDocument.Kind.Underscore4,
                AdocDocument.Kind.Equals4, AdocDocument.Kind.H4), kinds);
        assertEquals(List.of("open"), doc.contents().get(0).lines());
        assertEquals(List.of("A Headline"), doc.contents().get(3).lines());
    }

    private static AdocDocument.Block blockAt(int index) {
        return Adocs.parse(LINES).contents().get(index);
    }

}
