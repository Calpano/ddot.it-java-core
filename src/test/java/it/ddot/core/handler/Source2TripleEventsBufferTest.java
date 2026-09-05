package it.ddot.core.handler;

import it.ddot.core.event.IDdotEvent;
import it.ddot.core.io.ISourceHandler;
import it.ddot.core.io.handler.Source2TripleEvents;
import it.ddot.core.source.ISnippet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The buffer is bounded by the construct being read, not by the document.
 *
 * <p>The class says so and it was not true. A triple is held back after it is parsed, because a
 * {@code ,,} or {@code ;;} on the next line still attaches metadata to it -- and the buffer is
 * re-parsed from the start on every line that arrives. When the held triple sat on the buffer's
 * first line there was nothing above it to trim, so the buffer never shrank and every further line
 * re-parsed the whole document read so far.
 *
 * <p>Quadratic, and it showed: one {@code a ..b.. c} at the top of a 16 000-line file took 421
 * seconds where the same file without it took 240 milliseconds. The fix is that a held triple is
 * finished the moment a line arrives that cannot continue it, which is every line that does not
 * begin {@code ,,} or {@code ;;}.
 *
 * <p>These assert the bound rather than the clock. A timing test would pass on a fast machine with
 * the bug still in.
 */
class Source2TripleEventsBufferTest {

    private static final ISnippet DOC = ISnippet.ofPlainUri("test://doc");

    /** Comfortably above any construct these tests open, and far below the line counts they feed. */
    private static final int BOUND = 8;

    private List<IDdotEvent> collected;
    private Source2TripleEvents handler;

    @BeforeEach
    void setUp() {
        collected = new ArrayList<>();
        handler = new Source2TripleEvents(new ISourceHandler() {
            private @Nullable String subject;

            @Override
            public void activeSubject(String s) {
                subject = s;
            }

            @Override
            public @Nullable String activeSubject() {
                return subject;
            }

            @Override
            public void onLine(String line, int lineNumber) {}

            @Override
            public void onTripleEvent(IDdotEvent event) {
                collected.add(event);
            }

            @Override
            public void sourceStart() {}

            @Override
            public void sourceEnd() {}

            @Override
            public void sourceResourceStart(ISnippet snippet) {}

            @Override
            public void sourceResourceEnd() {}
        });
        handler.sourceStart();
        handler.sourceResourceStart(DOC);
    }

    /** The case that was quadratic: a statement first, then a document that never mentions one. */
    @Test
    void prosePastAStatementDoesNotAccumulate() {
        handler.onLine("a ..knows.. b", 1);
        int worst = 0;
        for (int line = 2; line <= 2_000; line++) {
            handler.onLine("just some prose on line " + line, line);
            worst = Math.max(worst, handler.bufferedLines());
        }
        assertThat(worst).isAtMost(BOUND);
        handler.sourceEnd();
        assertThat(collected).hasSize(1);
    }

    /** And with a statement every few lines, which is what a real ddot file looks like. */
    @Test
    void manyStatementsDoNotAccumulateEither() {
        int worst = 0;
        for (int line = 1; line <= 2_000; line++) {
            handler.onLine(line % 5 == 0 ? "s" + line + " ..knows.. o" + line : "prose", line);
            worst = Math.max(worst, handler.bufferedLines());
        }
        assertThat(worst).isAtMost(BOUND);
        handler.sourceEnd();
        assertThat(collected).hasSize(400);
    }

    /**
     * The reason the triple was held in the first place, which the fix must not lose: a `,,` on the
     * very next line is still its metadata.
     */
    @Test
    void aContinuationOnTheNextLineStillAttaches() {
        handler.onLine("a ..knows.. b", 1);
        handler.onLine(",, ..since.. 2025", 2);
        handler.onLine("prose", 3);
        handler.sourceEnd();
        assertThat(collected).hasSize(1);
        assertThat(collected.getFirst().meta()).isNotEmpty();
    }

    /** A `,,` two lines late is not a continuation, and was never treated as one. */
    @Test
    void aContinuationAfterAnInterveningLineDoesNot() {
        handler.onLine("a ..knows.. b", 1);
        handler.onLine("prose", 2);
        handler.onLine(",, ..since.. 2025", 3);
        handler.sourceEnd();
        assertThat(collected.getFirst().meta()).isEmpty();
    }

    /** An open construct is held for its own reason, and for as long as its body takes. */
    @Test
    void anOpenBlockIsStillBufferedUntilItCloses() {
        handler.onLine("a ..knows.. ddot.it/block", 1);
        for (int line = 2; line <= 40; line++) handler.onLine("body line " + line, line);
        assertThat(handler.bufferedLines()).isGreaterThan(BOUND);
        handler.sourceEnd();
        assertThat(collected).hasSize(1);
    }

}
