package it.ddot.core.conformance;

import it.ddot.core.event.DdotEvent;
import it.ddot.core.event.IMetaPair;
import it.ddot.core.io.DdotEventExporter;
import it.ddot.core.io.DdotParser;
import it.ddot.core.token.DdotTokenizer;
import it.ddot.core.token.PreCommand;
import it.ddot.core.token.Token;
import it.ddot.core.token.TokenName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parse-Spec conformance checks for {@link DdotTokenizer} and {@link DdotEventExporter} on edge inputs the
 * shared golden corpus does not cover ({@code CorpusConformanceTest} covers the ones it does), so they are
 * pinned here instead.
 */
final class DdotSpecConformanceTest {

    private static List<Token> tok(String src) {
        return DdotTokenizer.tokenize(src);
    }

    private static List<Token> ofType(List<Token> toks, TokenName type) {
        return toks.stream().filter(t -> t.tokenName() ==type).collect(Collectors.toList());
    }

    private static List<DdotEvent> events(String src) {
        return DdotParser.parse(src, "ddot", "test");
    }

    // --- EM2 guards: a 3+ run of `!` is plain text --------------------------

    @Test
    void tripleBangOffIsNotADirective() {
        List<Token> toks = tok("A ..p.. B\n!!!off\nC ..q.. D");
        assertTrue(ofType(toks, TokenName.COMMAND).isEmpty(), "no command token for !!!off: " + toks);
        // The line after `!!!off` is NOT excluded — it parses normally.
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 2 && t.tokenName() == TokenName.SUBJECT && t.text().equals("C")));
        assertTrue(ofType(toks, TokenName.EXCLUDED).isEmpty());
    }

    @Test
    void tripleBangOnDoesNotCloseAnOffRegion() {
        List<Token> toks = tok("!!off\nhidden ..x.. y\n!!!on\nstill ..x.. y\n!!on\nback ..p.. o");
        // `!!!on` stays excluded content; only the real `!!on` closes.
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 2 && t.tokenName() == TokenName.EXCLUDED && t.text().equals("!!!on")));
        assertTrue(toks.stream().anyMatch(t -> t.line() == 3 && t.tokenName() == TokenName.EXCLUDED));
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 5 && t.tokenName() == TokenName.SUBJECT && t.text().equals("back")));
    }

    @Test
    void tripleBangCommandInSlotStaysPlainSlotText() {
        List<Token> toks = tok("A ..p.. !!!this");
        assertTrue(ofType(toks, TokenName.COMMAND).isEmpty());
        assertEquals("!!!this", ofType(toks, TokenName.OBJECT).getFirst().text());
    }

    @Test
    void offOnHelpersMatchAllFourSpellingsExactly() {
        for (String s : List.of("!!off", "ddot.it/off", "http://ddot.it/off", "https://ddot.it/off",
                "ddot.it/off?x=1", "  !!off  ")) {
            assertTrue(PreCommand.isOffCommand(s), s);
        }
        for (String s : List.of("!!!off", "!!office", "ddot.it", "ddot.it/", "!!on", "off", "x !!off")) {
            assertFalse(PreCommand.isOffCommand(s), s);
        }
        assertTrue(PreCommand.isOnCommand("!!on"));
        assertTrue(PreCommand.isOnCommand("https://ddot.it/on"));
        assertFalse(PreCommand.isOnCommand("!!!on"));
        assertFalse(PreCommand.isOnCommand("!!online"));
    }

    // --- nested in-text commands (Highlight Spec D6) ------------------------

    @Test
    void commandInsideSlotTextEmitsNestedCommandToken() {
        List<Token> toks = tok("a ..spec.. see ddot.it/this for details");
        Token obj = ofType(toks, TokenName.OBJECT).getFirst();
        assertEquals("see ddot.it/this for details", obj.text());
        Token cmd = ofType(toks, TokenName.COMMAND).getFirst();
        assertEquals("ddot.it/this", cmd.text());
        // The nested token covers exactly the command span inside the slot.
        assertEquals(obj.start() + "see ".length(), cmd.start());
        assertEquals(cmd.start() + "ddot.it/this".length(), cmd.end());
    }

    @Test
    void nestedCommandRecognisedInAllFourSpellings() {
        for (String spelling : List.of("!!x", "ddot.it/x", "http://ddot.it/x", "https://ddot.it/x")) {
            List<Token> toks = tok("a ..p.. before " + spelling + " after");
            assertEquals(List.of(spelling),
                    ofType(toks, TokenName.COMMAND).stream().map(Token::text).collect(Collectors.toList()),
                    spelling);
        }
        // A bare `ddot.it` (no slash+name) is NOT a command, even in text.
        assertTrue(ofType(tok("a ..p.. see ddot.it for info"), TokenName.COMMAND).isEmpty());
    }

    // --- command-param for every command ------------------------------------

    @Test
    void commandQueryAndFragmentAreACommandParamToken() {
        List<Token> toks = tok("a ..p.. ddot.it/foo?q=1#frag");
        Token cmd = ofType(toks, TokenName.COMMAND).getFirst();
        Token param = ofType(toks, TokenName.COMMAND_PARAM).getFirst();
        assertEquals("ddot.it/foo", cmd.text());
        assertEquals("?q=1#frag", param.text());
        assertEquals(cmd.end(), param.start());
    }

    @Test
    void nestedCommandSplitsItsParamToo() {
        List<Token> toks = tok("a ..p.. see !!foo#frag here");
        assertEquals("!!foo", ofType(toks, TokenName.COMMAND).getFirst().text());
        assertEquals("#frag", ofType(toks, TokenName.COMMAND_PARAM).getFirst().text());
    }

    // --- MetaNextInline: after `;;` comes an entry, of either kind ------------

    @Test
    void anEntryAfterTheInlineMetaSeparatorIsAPairOrANote() {
        // Text there is the next entry — a note — not the death of the line.
        assertEquals(List.of("junk"), ofType(tok("s ..p.. o ,, ..a.. b ;; junk"), TokenName.META_TEXT)
                .stream().map(Token::text).collect(Collectors.toList()));
        // …and a `..` inside that note stays inside it.
        assertEquals(List.of("junk ..c.. d"), ofType(tok("s ..p.. o ,, ..a.. b ;; junk ..c.. d"), TokenName.META_TEXT)
                .stream().map(Token::text).collect(Collectors.toList()));
        // Control: a well-formed second pair keeps the line.
        List<Token> ok = tok("s ..p.. o ,, ..a.. b ;; ..c.. d");
        assertEquals(List.of("a", "c"), ofType(ok, TokenName.META_RELATION).stream()
                .map(Token::text).collect(Collectors.toList()));
    }

    /**
     * {@code ;;} parts entries and demands nothing of them, so an EMPTY entry — a line ending in {@code ;;}, or a
     * {@code ;;} straight after the {@code ,,} — contributes no metadata and is dropped. The triple and the entries
     * around it are untouched: an empty entry is not an error, and a separator is not a promise.
     */
    @Test
    void anEmptyMetaEntryIsDroppedAndKeepsItsLine() {
        // Trailing: the pair survives, and the separator is still tokenized.
        List<DdotEvent> trailing = events("s ..p.. o ,, ..a.. b ;;\n");
        assertEquals(1, trailing.size());
        assertEquals(List.of("a"), trailing.getFirst().meta().pairs().stream().map(IMetaPair::type).toList());
        assertEquals(1, ofType(tok("s ..p.. o ,, ..a.. b ;;"), TokenName.META_SEPARATOR).size());

        // Leading: an empty first entry, then the note `x` — NOT one note reading `;; x`.
        List<DdotEvent> leading = events("s ..p.. o ,, ;; x\n");
        assertEquals(1, leading.getFirst().meta().size());
        assertEquals("x", leading.getFirst().meta().get(0).to());

        // Nothing but separators: a bare triple, no metadata, no lost line.
        List<DdotEvent> empty = events("s ..p.. o ,, ;;\n");
        assertEquals(1, empty.size());
        assertTrue(empty.getFirst().meta().isEmpty());
    }

    // --- blank-line rules for `,,` blocks ------------------------------------

    @Test
    void blankLineBreaksStandaloneCommaAttachment() {
        List<Token> toks = tok("s ..p.. o\n\n,,\nnote\n,,");
        assertTrue(ofType(toks, TokenName.META_DELIM).isEmpty(), "unattached `,,` yields no tokens: " + toks);
        assertTrue(ofType(toks, TokenName.META_TEXT).isEmpty());
        // Control: immediately after the triple it attaches.
        List<Token> ok = tok("s ..p.. o\n,,\nnote\n,,");
        assertEquals(2, ofType(ok, TokenName.META_DELIM).size());
        assertEquals("note", ofType(ok, TokenName.META_TEXT).getFirst().text());
    }

    @Test
    void commaAfterNoTripleIsNotATriple() {
        assertTrue(tok(",,\nnote\n,,").isEmpty());
    }

    @Test
    void blankLineInsideMetaBlockAbortsIt() {
        List<Token> toks = tok("s ..p.. o\n,,\nnote\n\n..x.. y");
        // After the aborting blank line, `..x.. y` is a plain (subject-less)
        // triple line again, not meta content.
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 4 && t.tokenName().equals(TokenName.RELATION) && t.text().equals("x")));
        assertTrue(toks.stream().noneMatch(
                t -> t.line() == 4 && t.tokenName().id.startsWith("meta-")));
    }

    // --- `!!block` opener positions ------------------------------------------

    @Test
    void blockOpenerInvalidInMetaRelationPosition() {
        List<Token> toks = tok("s ..p.. o ,, ..!!block\nnext ..is.. here");
        // Not an opener: no verbatim region; it is command-shaped slot text.
        assertTrue(ofType(toks, TokenName.VERBATIM).isEmpty());
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 0 && t.tokenName() == TokenName.COMMAND && t.text().equals("!!block")));
        assertTrue(toks.stream().anyMatch(
                t -> t.line() == 1 && t.tokenName() == TokenName.SUBJECT && t.text().equals("next")));
    }

    @Test
    void blockOpenerStillValidInValuePositions() {
        // Object position (existing behavior, must survive the phase guard).
        List<Token> toks = tok("s ..p.. !!block\nbody line\n\nnext ..is.. here");
        assertEquals("body line", ofType(toks, TokenName.VERBATIM).getFirst().text());
    }

    // --- exporter: subject register resets at `!!off` -------------------------

    @Test
    void subjectRegisterDoesNotSurviveAnOffRegion() {
        List<DdotEvent> evs = events("A ..p.. B\n!!off\nX\n!!on\n..q.. C\n");
        assertEquals(1, evs.size());
        assertEquals("A", evs.getFirst().from);
        // Control: without the off region the shorthand line binds to A.
        List<DdotEvent> control = events("A ..p.. B\n..q.. C\n");
        assertEquals(2, control.size());
        assertEquals("A", control.get(1).from);
    }

    // --- exporter: unterminated / aborted `,,` blocks -------------------------

    @Test
    void unterminatedMetaBlockEmitsNoTriple() {
        assertTrue(events("A ..p.. B ,,\n..k.. v\n").isEmpty());
        assertTrue(events("A ..p.. B\n,,\nnote\n").isEmpty());
        // Control: the closed block emits the triple with its meta.
        List<DdotEvent> ok = events("A ..p.. B ,,\n..k.. v\n,,\n");
        assertEquals(1, ok.size());
        assertEquals(1, ok.getFirst().meta().size());
        assertEquals("k", ok.getFirst().meta().get(0).type());
        assertEquals("v", ok.getFirst().meta().get(0).to());
    }

    @Test
    void blankLineAbortsMetaBlockAndDropsItsTriple() {
        List<DdotEvent> evs = events("A ..p.. B ,,\nnote\n\nC ..q.. D\n");
        assertEquals(1, evs.size());
        assertEquals("C", evs.getFirst().from);
    }

    // --- meta entries: pairs and notes mix, `;;` separates them ---------------

    /**
     * {@code MetaInline := MetaEntryInline (SC2 MetaEntryInline)*} with
     * {@code MetaEntryInline := MetaTripleInline | MetaTextInline} — a note is one KIND of entry, not the
     * alternative to the whole chain, so pairs and notes mix in any order and {@code ;;} separates them
     * unconditionally ({@code EntryWord := Word - SC2}). Corpus cases 36-inline-meta-notes and
     * 37-inline-meta-mixed.
     */
    @Test
    void inlineNotesAndPairsMixAndAreSeparatedBySemicolons() {
        List<DdotEvent> notes = events("a ..b.. c ,, first ;; second ;; third\n");
        assertEquals(List.of("first", "second", "third"), notes.getFirst().meta().pairs().stream().map(IMetaPair::to).toList());
        assertTrue(notes.getFirst().meta().pairs().stream().allMatch(p -> p.type().equals("text")));

        // A pair, a note and an untyped pair, in written order.
        List<DdotEvent> mixed = events("a ..b.. c ,, ..k.. v ;; a note ;; .... o\n");
        assertEquals(List.of("k", "text", "links to"), mixed.getFirst().meta().pairs().stream().map(IMetaPair::type).toList());
        assertEquals(List.of("v", "a note", "o"), mixed.getFirst().meta().pairs().stream().map(IMetaPair::to).toList());

        // The tokenizer says the same: three entries, two separators, no note swallowing a `..`.
        List<Token> toks = tok("a ..b.. c ,, ..k.. v ;; a note ;; .... o\n");
        assertEquals(List.of("a note"), ofType(toks, TokenName.META_TEXT).stream().map(Token::text).toList());
        assertEquals(2, ofType(toks, TokenName.META_SEPARATOR).size());
    }

    /**
     * The one lookahead, {@code MetaStart --> MetaRelationInline : DT2 [a MetaTripleInline derives]}: a leading
     * {@code ..} opens a pair only when the pair actually closes inside the entry. Otherwise the ordered choice
     * falls through to {@code MetaTextInline} — {@code ,, ..bla} is the note {@code ..bla}, not a dead line.
     */
    @Test
    void aDoubleDotThatNeverClosesIsANote() {
        List<DdotEvent> evs = events("a ..b.. c ,, ..bla\n");
        assertEquals(1, evs.getFirst().meta().size());
        assertEquals("text", evs.getFirst().meta().get(0).type());
        assertEquals("..bla", evs.getFirst().meta().get(0).to());

        // The guard is bounded by the entry: the closing `..` of the SECOND entry cannot rescue the first.
        List<DdotEvent> bounded = events("a ..b.. c ,, ..bla ;; ..k.. v\n");
        assertEquals(List.of("text", "k"), bounded.getFirst().meta().pairs().stream().map(IMetaPair::type).toList());
        assertEquals("..bla", bounded.getFirst().meta().get(0).to());
    }

    /** Control: with only whitespace after `,,`, the first `..` DOES start a pair. */
    @Test
    void inlineMetaPairStillStartsWhenNoTextPrecedesIt() {
        List<DdotEvent> evs = events("a ..b.. c ,, ..k.. v ;; ..j.. w\n");
        assertEquals(2, evs.getFirst().meta().size());
        assertEquals("k", evs.getFirst().meta().get(0).type());
        assertEquals("j", evs.getFirst().meta().get(1).type());
    }

    /**
     * Once a note has begun, {@code MetaTextInline} self-loops on {@code DT2 | DT4 | CM2 | EM2 | TX}: only a
     * {@code ;;} leaves it, so a `..` inside the note is content and never re-opens a pair.
     */
    @Test
    void aDoubleDotInsideANoteIsContent() {
        List<DdotEvent> evs = events("a ..b.. c ,, see ../../x.adoc for this ;; ..k.. v\n");
        assertEquals(List.of("text", "k"), evs.getFirst().meta().pairs().stream().map(IMetaPair::type).toList());
        assertEquals("see ../../x.adoc for this", evs.getFirst().meta().get(0).to());
    }

    /**
     * In a block, NL separates and {@code ;;} is content ({@code MetaObjectInBlock := Text}); ADJACENT text lines
     * are ONE note and a pair line ends the run. Corpus cases 38-block-meta-notes and 39-block-meta-mixed.
     */
    @Test
    void blockNotesRunAcrossAdjacentLinesAndArePartedByPairLines() {
        List<DdotEvent> evs = events("a ..b.. c ,,\nfirst\nstill first\n..k.. v\nsecond\n,,\n");
        assertEquals(List.of("text", "k", "text"), evs.getFirst().meta().pairs().stream().map(IMetaPair::type).toList());
        assertEquals("first\nstill first", evs.getFirst().meta().get(0).to());
        assertEquals("second", evs.getFirst().meta().get(2).to());

        // `;;` inside a block line is ordinary content, not a separator (28-semicolon-in-meta-block).
        List<DdotEvent> semi = events("a ..b.. c ,,\none ;; two\n,,\n");
        assertEquals(1, semi.getFirst().meta().size());
        assertEquals("one ;; two", semi.getFirst().meta().get(0).to());
    }

    @Test
    void blankBlockTerminatorInsideMetaDoesNotAbortIt() {
        // 30-block-meta-object shape: the blank line TERMINATES the unmarked
        // `!!block`, it is not a free blank line inside a `,,` block.
        List<DdotEvent> evs = events("Dirk ..works at.. SAP ,, ..note.. !!block\nline one\n\nnext ..is.. here\n");
        assertEquals(2, evs.size());
        assertEquals("line one", evs.getFirst().meta().get(0).to());
    }
}
