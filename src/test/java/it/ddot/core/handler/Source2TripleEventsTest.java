package it.ddot.core.handler;

import it.ddot.core.DdotVocabulary;
import it.ddot.core.event.IDdotEvent;
import it.ddot.core.source.ISnippet;
import it.ddot.core.io.ISourceHandler;
import it.ddot.core.source.ISourceLocation;
import it.ddot.core.source.ISourceResourceAddress;
import it.ddot.core.io.handler.Source2TripleEvents;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class Source2TripleEventsTest {

    private static final ISnippet DOC = ISnippet.ofPlainUri("test://doc");

    private List<IDdotEvent> collected;

    /**
     * The events, once the source is complete. {@link Source2TripleEvents#sourceEnd()} matters: a triple
     * is not final until a line arrives that does not extend it, because a following {@code ,,} or
     * {@code ;;} attaches metadata to it. Real readers always end their resource; a unit test that stops
     * feeding lines has to say so too, or it is asserting on a half-read document.
     */
    private List<IDdotEvent> events() {
        handler.sourceEnd();
        return collected;
    }
    private Source2TripleEvents handler;

    @Test
    void blankLine_ignored() {
        handler.onLine("   ", 1);
        assertThat(events()).isEmpty();
    }

    /**
     * Only a block opener that ENDS its line opens a block. With anything after it — here a {@code ,,} —
     * it is ordinary field text, because "a Command is a part of the text of the field holding it, not a
     * replacement for it" (Parse Spec). Corpus case 30-block-meta-object shows the shape that DOES open a
     * block: the opener is last on its line and the body follows.
     */
    @Test
    void blockOpenerNotEndingItsLine_isFieldText() {
        handler.onLine("aaa ..bbb.. ddot.it/block ,, ..since.. 2025", 1);
        handler.onLine("content", 2);
        handler.onLine("", 3);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().to()).isEqualTo("ddot.it/block");
        assertThat(java.util.Objects.requireNonNull(events().getFirst().meta().get("since"))).isEqualTo("2025");
    }

    @Test
    void block_continuationSubjectPreservedAfterBlock() {
        handler.onLine("john ..address.. ddot.it/block", 1);
        handler.onLine("Berlin", 2);
        handler.onLine("", 3);
        handler.onLine(".. age .. 11", 4); // continuation reuses 'john'
        assertThat(events()).hasSize(2);
        assertThat(events().get(1).from()).isEqualTo("john");
        assertThat(events().get(1).to()).isEqualTo("11");
    }

    @Test
    void block_customEndMarker_notIncluded() {
        handler.onLine("john ..address.. ddot.it/block?end=THE-END", 1);
        handler.onLine("Broadway 1", 2);
        handler.onLine("", 3); // blank line is just content with a custom marker
        handler.onLine("Germany", 4);
        handler.onLine("THE-END", 5);
        handler.onLine("john ..age.. 11", 6);

        assertThat(events()).hasSize(2);
        assertThat(events().getFirst().to()).isEqualTo("Broadway 1\n\nGermany");
        assertThat(events().get(1).to()).isEqualTo("11");
    }

    @Test
    void block_multiLineValue_joinedWithNewlines() {
        handler.onLine("john ..address.. ddot.it/block", 1);
        handler.onLine("Broadway 1", 2);
        handler.onLine("Berlin", 3);
        handler.onLine("Germany", 4);
        handler.onLine("", 5); // blank line ends the block
        handler.onLine("john ..age.. 11", 6);

        assertThat(events()).hasSize(2);
        assertThat(events().getFirst().from()).isEqualTo("john");
        assertThat(events().getFirst().type()).isEqualTo("address");
        assertThat(events().getFirst().to()).isEqualTo("Broadway 1\nBerlin\nGermany");
        assertThat(events().get(1).from()).isEqualTo("john");
        assertThat(events().get(1).to()).isEqualTo("11");
    }

    @Test
    void block_shortFormAbbreviation() {
        handler.onLine("aaa ..bbb.. !!block", 1);
        handler.onLine("line-1", 2);
        handler.onLine("line-2", 3);
        handler.onLine("", 4);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().to()).isEqualTo("line-1\nline-2");
    }

    @Test
    void block_singleLine_sameAsPlainValue() {
        handler.onLine("aaa ..bbb.. ddot.it/block?end=123", 1);
        handler.onLine("Hello World", 2);
        handler.onLine("123", 3);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().to()).isEqualTo("Hello World");
    }

    @Test
    void block_triplesInsideBlockAreLiteral() {
        handler.onLine("aaa ..bbb.. ddot.it/block", 1);
        handler.onLine("X .. p .. Y", 2); // NOT recognised as a triple inside a block
        handler.onLine("", 3);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().to()).isEqualTo("X .. p .. Y");
    }

    @Test
    void block_unterminated_flushedOnSourceEnd() {
        handler.onLine("aaa ..bbb.. ddot.it/block", 1);
        handler.onLine("only line", 2);
        handler.sourceEnd();
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().to()).isEqualTo("only line");
    }

    @Test
    void continuationLine_usesLastSubject() {
        handler.onLine("Alice .. knows .. Bob", 1);
        handler.onLine(".. knows .. Carol", 2);
        assertThat(events()).hasSize(2);
        assertThat(events().get(1).from()).isEqualTo("Alice");
        assertThat(events().get(1).to()).isEqualTo("Carol");
    }

    @Test
    void continuationLine_withoutPriorSubject_ignored() {
        handler.onLine(".. knows .. Bob", 1);
        assertThat(events()).isEmpty();
    }

    /**
     * {@code ddot.it/this} reaches the event stream VERBATIM; resolving it against the source URI is the
     * collector's job, not the parser's. Pinned by corpus cases 05-command and 16-this, which expect
     * {@code "from":"ddot.it/this"}. This handler used to substitute the URI here — a divergence that
     * only disappeared once it stopped carrying its own parser.
     */
    @Test
    void ddotItThis_isEmittedVerbatim() {
        handler.onLine("ddot.it/this .. is-a .. doc", 1);
        assertThat(events().getFirst().from()).isEqualTo("ddot.it/this");
    }

    @Test
    void dotsRunningIntoSeparator_ignored() {
        // The operator must be followed by a non-dot: any dot-run continuing into the separator → not a triple.
        handler.onLine("S ...... O", 1);  // six dots
        handler.onLine("S ..... O", 2);   // five dots
        handler.onLine("S ... B", 3);     // three dots
        assertThat(events()).isEmpty();
    }

    @Test
    void emitTriple_emitsHasContentWithNewlines() {
        handler.emitTriple("aaa", "has content", "Hello Mr. <em>Smith</em>!\nSee link.", 1);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().from()).isEqualTo("aaa");
        assertThat(events().getFirst().type()).isEqualTo("has content");
        assertThat(events().getFirst().to()).isEqualTo("Hello Mr. <em>Smith</em>!\nSee link.");
    }

    /**
     * "Commands are not resolved at this layer. `!!this` is emitted as the literal `from` value …
     * substituting `source` for it is the collector's job" (Parse Spec). This holds for the programmatic
     * entry point too, or a reader's events would disagree with every other reader's. The substitution
     * itself is asserted in TripleEvent2KgraphTest, in ddotit-kbase.
     */
    @Test
    void emitTriple_leavesDdotItThisToTheCollector() {
        handler.emitTriple("ddot.it/this", "key1", "value1", 1);
        assertThat(events().getFirst().from()).isEqualTo("ddot.it/this");
    }

    @Test
    void emptyObject_ignored() {
        // "a..b.." has an empty object after the second separator → not a triple
        handler.onLine("a .. b ..", 1);
        assertThat(events()).isEmpty();
    }

    @Test
    void fullUrlOffOn_toggleProcessing() {
        // the ddot.it/… command is a self-documenting URL; the pasted full-URL form must work too
        handler.onLine("Alice .. knows .. Bob", 1);
        handler.onLine("https://ddot.it/off", 2);
        handler.onLine("X .. p .. Y", 3);
        handler.onLine("http://ddot.it/on", 4);
        handler.onLine("C .. p .. D", 5);
        assertThat(events()).hasSize(2);
        assertThat(events().get(1).from()).isEqualTo("C");
    }

    @Test
    void fullUrlOffTopic_isNotACommand() {
        // https://ddot.it/off-topic is a distinct token (not "off") → a plain triple line, not a toggle
        handler.onLine("https://ddot.it/off-topic", 1); // not a valid triple → ignored, but must NOT disable
        handler.onLine("A .. p .. B", 2);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().from()).isEqualTo("A");
    }

    @Test
    void fullUrlThis_isEmittedVerbatim() {
        handler.onLine("https://ddot.it/this .. author .. Alice", 1);
        assertThat(events()).hasSize(1);
        // Verbatim, like the bare spelling above — the full-URL form is still just the command token.
        assertThat(events().getFirst().from()).isEqualTo("https://ddot.it/this");
        assertThat(events().getFirst().to()).isEqualTo("Alice");
    }

    @Test
    void fullUrlWithWww_isRecognised() {
        handler.onLine("https://www.ddot.it/off", 1);
        handler.onLine("A .. p .. B", 2);
        assertThat(events()).isEmpty();
    }

    // ---- ".." in object vs. meta value ---------------------------------------

    @Test
    void labelRelation_longForm_emittedVerbatimAsPredicate() {
        handler.onLine("a ..ddot.it/label.. Alice", 1);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().type()).isEqualTo("ddot.it/label");
        assertThat(events().getFirst().to()).isEqualTo("Alice");
    }

    @Test
    void labelRelation_mixedWithNormalEdges_case13() {
        handler.onLine("a ..knows.. b", 1);
        handler.onLine("b ..knows.. c", 2);
        handler.onLine("a ..!!label.. Alice", 3);
        handler.onLine("b ..!!label.. Bob", 4);
        handler.onLine("c ..!!label.. Carol", 5);
        assertThat(events()).hasSize(5);
        assertThat(events().getFirst().type()).isEqualTo("knows");
        assertThat(events().get(2).from()).isEqualTo("a");
        assertThat(events().get(2).type()).isEqualTo("!!label");
        assertThat(events().get(2).to()).isEqualTo("Alice");
        assertThat(events().get(4).to()).isEqualTo("Carol");
    }

    @Test
    void labelRelation_shortForm_emittedVerbatimAsPredicate() {
        handler.onLine("a ..!!label.. Alice", 3);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().from()).isEqualTo("a");
        assertThat(events().getFirst().type()).isEqualTo("!!label");
        assertThat(events().getFirst().to()).isEqualTo("Alice");
    }

    @Test
    void lineWithoutDots_ignored() {
        handler.onLine("just a plain sentence", 1);
        assertThat(events()).isEmpty();
    }

    @Test
    void metaValueContainingDoubleDots_preserved() {
        // ".." IS allowed in a meta value (after ",,"): it is split off before the triple is parsed.
        handler.onLine("A .. p .. B ,, .. ref .. http://h/x..y", 1);
        assertThat(events()).hasSize(1);
        assertThat(java.util.Objects.requireNonNull(events().getFirst().meta().get("ref"))).isEqualTo("http://h/x..y");
    }

    @Test
    void meta_parsedFromDoublComma() {
        handler.onLine("A .. p .. B ,, .. since .. 2025", 1);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().meta()).isNotNull();
        assertThat(java.util.Objects.requireNonNull(events().getFirst().meta().get("since"))).isEqualTo("2025");
    }

    @Test
    void objectContainingDoubleDots_preserved() {
        // The object may contain ".." internally (e.g. a URL); only the first two ".." are separators.
        handler.onLine("page .. links to .. http://h/a..b..c", 1);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().from()).isEqualTo("page");
        assertThat(events().getFirst().type()).isEqualTo("links to");
        assertThat(events().getFirst().to()).isEqualTo("http://h/a..b..c");
    }

    // ---- ddot.it/block --------------------------------------------------------

    @Test
    void offDisablesProcessing() {
        handler.onLine("Alice .. knows .. Bob", 1);
        handler.onLine("ddot.it/off", 2);
        handler.onLine("X .. p .. Y", 3);
        assertThat(events()).hasSize(1);
    }

    @Test
    void offMarker_mustBeAloneOnLine_notMidTriple() {
        // A marker appearing inside a triple (here as the object) is literal slot text, not a toggle.
        handler.onLine("page .. see .. http://ddot.it/off-topic", 1);
        handler.onLine("A .. p .. B", 2);
        assertThat(events()).hasSize(2);
        assertThat(events().getFirst().to()).isEqualTo("http://ddot.it/off-topic");
        assertThat(events().get(1).from()).isEqualTo("A");
    }

    @Test
    void offResetsSubject() {
        handler.onLine("Alice .. knows .. Bob", 1);
        handler.onLine("ddot.it/off", 2);
        handler.onLine("ddot.it/on", 3);
        handler.onLine(".. knows .. Carol", 4); // no active subject after off
        assertThat(events()).hasSize(1);
    }

    @Test
    void onReenablesProcessing() {
        handler.onLine("ddot.it/off", 1);
        handler.onLine("A .. p .. B", 2);
        handler.onLine("ddot.it/on", 3);
        handler.onLine("C .. p .. D", 4);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().from()).isEqualTo("C");
    }

    @Test
    void onlyOneSeparator_ignored() {
        handler.onLine("a .. b", 1);
        assertThat(events()).isEmpty();
    }

    @Test
    void relativePathObject_preserved() {
        // A relative-path object is fine: the space after ".." means dots don't run into the separator.
        handler.onLine("x .. links to .. ../../y", 1);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().to()).isEqualTo("../../y");
    }

    @BeforeEach
    void setUp() {
        collected = new ArrayList<>();
        ISourceHandler sink = new ISourceHandler() {
            @Override
            public void activeSubject(String s) {}

            @Override
            public @Nullable String activeSubject() {return null;}

            @Override
            public void onLine(String l, int n) {}

            @Override
            public void onTripleEvent(IDdotEvent t) {collected.add(t);}

            @Override
            public void sourceEnd() {}

            @Override
            public void sourceResourceEnd() {}

            @Override
            public void sourceResourceStart(ISnippet a) {}

            @Override
            public void sourceStart() {}
        };
        handler = new Source2TripleEvents(sink);
        handler.sourceStart();
        handler.sourceResourceStart(DOC);
    }

    @Test
    void shorthandOffOn_toggleProcessing() {
        handler.onLine("!!off", 1);
        handler.onLine("A .. p .. B", 2);
        handler.onLine("!!on", 3);
        handler.onLine("C .. p .. D", 4);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().from()).isEqualTo("C");
    }

    // ---- ddot.it/label (v1.2): a command used as a relation -------------------
    // Per spec test-case 13-label, the predicate is preserved verbatim and emitted as an
    // ordinary triple; the node-id-vs-label interpretation happens in downstream tools.

    @Test
    void simpleLine_parsedAsTriple() {
        handler.onLine("Alice .. knows .. Bob", 1);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().from()).isEqualTo("Alice");
        assertThat(events().getFirst().type()).isEqualTo("knows");
        assertThat(events().getFirst().to()).isEqualTo("Bob");
    }

    @Test
    void sourceLocation_lineNumberSet() {
        handler.onLine("A .. p .. B", 7);
        assertThat(events().getFirst().startLineInSnippet()).isEqualTo(7);
        // DOC is a whole-file snippet starting at line 1, so the resource line coincides with the snippet line.
        assertThat(events().getFirst().startLineInResource()).isEqualTo(7);
        assertThat(events().getFirst().sourceLocation().lineNumber()).isEqualTo(7);
    }

    /**
     * The two line numbers are distinct: for a snippet lifted out of the middle of a file, an event's
     * snippet-relative line and its file line differ by the snippet's own offset.
     */
    @Test
    void sourceLocation_snippetOffsetAddedForEmbeddedSnippet() {
        // a snippet whose first line is line 40 of the file
        handler.sourceResourceStart(ISnippet.of("unknownKind",
                ISourceLocation.of(ISourceResourceAddress.of("test://doc"), 40)));
        handler.onLine("A .. p .. B", 3); // third line WITHIN the snippet
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().startLineInSnippet()).isEqualTo(3);
        assertThat(events().getFirst().startLineInResource()).isEqualTo(42);
        assertThat(events().getFirst().sourceLocation().lineNumber()).isEqualTo(42);
    }

    @Test
    void sourceLocation_resourceUri_matchesAddress() {
        handler.onLine("A .. p .. B", 1);
        assertThat(events().getFirst().sourceUri())
                .isEqualTo("test://doc");
    }

    // ---- emitTriple (used by the HTML <ddot-it> custom element) ---------------

    @Test
    void sourceStart_reEnablesAfterOff() {
        handler.onLine("ddot.it/off", 1);
        handler.sourceStart();
        handler.sourceResourceStart(DOC);
        handler.onLine("A .. p .. B", 2);
        assertThat(events()).hasSize(1);
    }

    /** An untyped {@code ....} link is not a missing relation: it is spelled out as "links to" on the event. */
    @Test
    void untypedLine_typeIsLinksTo() {
        handler.onLine("Alice .... Bob", 1);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().type()).isEqualTo(DdotVocabulary.LINKS_TO);
    }

}
