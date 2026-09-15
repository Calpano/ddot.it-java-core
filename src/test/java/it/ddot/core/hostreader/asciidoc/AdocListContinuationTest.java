package it.ddot.core.hostreader.asciidoc;

import it.ddot.core.hostreader.asciidoc.doc.AdocDocument;
import it.ddot.core.hostreader.asciidoc.doc.Adocs;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The list continuation: a lone {@code +} attaching the block below it to the item above.
 *
 * <p>From the AsciiDoc language documentation:
 *
 * <blockquote>A list continuation is a + symbol on a line by itself, immediately adjacent to the
 * block being attached. […] If an empty line after a list item is followed by the start of a block,
 * such as a paragraph or delimited block rather than another list item, the list will terminate at
 * this point.</blockquote>
 *
 * <p>This parser splits a file into blocks and does not model lists -- that is its caller's job --
 * but the split has to leave the caller something it can read. A delimited block claimed by a
 * {@code +} is part of the list above it, and cutting the file between the two hands the caller a
 * list, a code block and a second list, with nothing left to say they were ever one thing.
 *
 * <p>So a fence directly under a continuation stays with the run that claimed it. What arrives is
 * one block holding the list and the fence, which is what the lines say.
 */
class AdocListContinuationTest {

    private static final String SOURCE = """
            = Steps

            . Install the thing
            +
            ----
            brew install thing
            ----
            . Configure it
            """;

    /** One run, not three: the fence claimed by the `+` stays with the lines that claimed it. */
    @Test
    void aFenceUnderAContinuationStaysWithItsList() {
        AdocDocument doc = Adocs.parse(SOURCE);

        List<AdocDocument.Block> blocks = doc.contents();
        assertEquals(1, blocks.size(), () -> "blocks: " + kinds(blocks));
        assertEquals(AdocDocument.Kind.Plain, blocks.getFirst().kind);
        assertEquals(List.of(". Install the thing", "+", "----", "brew install thing", "----",
                ". Configure it"), blocks.getFirst().lines());
    }

    /** And a fence that nothing claimed is its own block, which is what a fence normally is. */
    @Test
    void aFenceWithNoContinuationIsItsOwnBlock() {
        AdocDocument doc = Adocs.parse("""
                = Steps

                . Install the thing

                ----
                brew install thing
                ----
                """);

        List<AdocDocument.Block> blocks = doc.contents();
        assertEquals(2, blocks.size(), () -> "blocks: " + kinds(blocks));
        assertEquals(AdocDocument.Kind.Plain, blocks.get(0).kind);
        assertEquals(AdocDocument.Kind.Dash4, blocks.get(1).kind);
    }

    /** Several of them, each behind its own `+`, all in the one run. */
    @Test
    void severalContinuationsStayInTheSameRun() {
        AdocDocument doc = Adocs.parse("""
                = Steps

                . Install the thing
                +
                ----
                one
                ----
                +
                ----
                two
                ----
                . Configure it
                """);

        assertEquals(1, doc.contents().size(), () -> "blocks: " + kinds(doc.contents()));
    }

    /** A description entry claims one the same way an item does. */
    @Test
    void aDescriptionEntryClaimsItTheSameWay() {
        AdocDocument doc = Adocs.parse("""
                = Terms

                term:: what it means
                +
                ----
                run this
                ----
                other:: second
                """);

        assertEquals(1, doc.contents().size(), () -> "blocks: " + kinds(doc.contents()));
    }

    /** Whatever the split, the file is written back exactly: that is this parser's contract. */
    @Test
    void itIsWrittenBackAsItWasRead() {
        StringWriter out = new StringWriter();
        Adocs.write(Adocs.parse(SOURCE), out);

        assertEquals(SOURCE, out.toString());
    }

    private static String kinds(List<AdocDocument.Block> blocks) {
        return blocks.stream().map(block -> block.kind + block.lines().toString()).toList().toString();
    }
}
