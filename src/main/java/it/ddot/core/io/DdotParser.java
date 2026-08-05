package it.ddot.core.io;

import it.ddot.core.DdotSlot;
import it.ddot.core.DdotVocabulary;
import it.ddot.core.event.DdotEvent;
import it.ddot.core.event.IDdotTriple;
import it.ddot.core.event.IMetaPair;
import it.ddot.core.event.Meta;
import it.ddot.core.source.ISnippet;
import it.ddot.core.source.ISourceLocation;
import it.ddot.core.source.ISourceResourceAddress;
import it.ddot.core.source.Snippet;
import it.ddot.core.token.DdotTokenizer;
import it.ddot.core.token.PreCommand;
import it.ddot.core.token.Token;
import it.ddot.core.token.TokenName;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DdotParser {

    public static final class Line {

        final Meta meta = new Meta();
        @Nullable String subject;
        /**
         * A QuadDot line skips the relation slot entirely, so the slot keeps this default: an untyped link is
         * reported as {@link DdotVocabulary#LINKS_TO}, never as a null relation (see {@link IDdotTriple#type()}).
         */
        @NonNull String relation = DdotVocabulary.LINKS_TO;
        @Nullable String object;
        boolean opensMetaBlock;

    }

    /** The tokenizer's transition table; a QuadDot skips the relation slot. */
    private static DdotSlot advance(DdotSlot slot, boolean quad) {
        return switch (slot) {
            case SUBJECT -> quad ? DdotSlot.OBJECT : DdotSlot.RELATION;
            case RELATION -> DdotSlot.OBJECT;
            case OBJECT -> DdotSlot.OBJECT;
            case META_START -> quad ? DdotSlot.META_OBJECT : DdotSlot.META_RELATION;
            case META_RELATION, META_OBJECT -> DdotSlot.META_OBJECT;
            case META_NEXT -> DdotSlot.META_RELATION;
        };
    }

    /**
     * Map each block opener's line to its body text, and mark the body lines consumed so the event pass skips them.
     *
     * <p>The body is taken from the RAW lines, not from the {@code verbatim}
     * tokens: a block is verbatim, and the tokens are trimmed, which would drop indentation. Blank lines inside a
     * marked ({@code ?end=}) block survive, and are recovered from the gaps in the verbatim token line numbers — the
     * unmarked form cannot produce such a gap, because a blank line ends it.
     */
    private static Map<Integer, String> collectBlockBodies(String[] lines, List<List<Token>> byLine, Set<Integer> consumed) {
        Map<Integer, String> bodies = new HashMap<>();
        int openerLine = -1;
        boolean marked = false;
        int firstBodyLine = -1, lastBodyLine = -1;

        for (int i = 0; i < lines.length; i++) {
            List<Token> toks = byLine.get(i);

            if (openerLine >= 0) {
                boolean isBlockEnd = !toks.isEmpty() && toks.getFirst().tokenName().equals(TokenName.BLOCK_END);
                boolean isBody = !toks.isEmpty() && toks.getFirst().tokenName().equals(TokenName.VERBATIM);
                if (isBody) {
                    if (firstBodyLine < 0) firstBodyLine = i;
                    lastBodyLine = i;
                    consumed.add(i);
                    continue;
                }
                // A blank line ends the unmarked form, but is ordinary body
                // content in the marked one — which is exactly why `?end=`
                // exists. Only the END marker closes a marked block. Such
                // interior blank lines belong to the block, so they are
                // consumed too — they must not look like free blank lines to
                // the event pass (which would abort an enclosing `,,` block).
                if (marked && !isBlockEnd) {
                    consumed.add(i);
                    continue;
                }

                bodies.put(openerLine, orEmpty(join(lines, firstBodyLine, lastBodyLine)));
                if (isBlockEnd) consumed.add(i);
                    // The blank TERMINATOR of an unmarked block is part of the
                    // block construct, not a free blank line — consume it.
                else if (toks.isEmpty()) consumed.add(i);
                openerLine = -1;
                marked = false;
                firstBodyLine = -1;
                lastBodyLine = -1;
                if (isBlockEnd || toks.isEmpty()) continue;
                // else: fall through — this line is ordinary content again
            }

            if (opensBlock(toks)) {
                openerLine = i;
                marked = toks.stream().anyMatch(t -> t.tokenName().equals(TokenName.BLOCK_END));
            }
        }
        if (openerLine >= 0) bodies.put(openerLine, orEmpty(join(lines, firstBodyLine, lastBodyLine)));
        return bodies;
    }

    private static void flushMetaText(DdotEvent event, List<String> lines) {
        if (lines.isEmpty()) return;
        event.addMeta(DdotVocabulary.TEXT, String.join("\n", lines));
        lines.clear();
    }

    private static List<List<Token>> groupByLine(List<Token> tokens, int lineCount) {
        List<List<Token>> byLine = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) byLine.add(new ArrayList<>());
        for (Token t : tokens) {
            if (t.line() >= 0 && t.line() < lineCount) byLine.get(t.line()).add(t);
        }
        return byLine;
    }

    private static boolean isBlockOpener(Token t) {
        return t.tokenName().equals(TokenName.COMMAND) && PreCommand.BLOCK_OPENER.matcher(t.text()).matches();
    }

    private static boolean isQuad(String sep) {
        return sep.length() == 4 || sep.indexOf(' ') >= 0 || sep.indexOf('\t') >= 0;
    }

    /** Raw lines {@code [from, to]}, or null when the block has no body at all. */
    private static @Nullable String join(String[] lines, int from, int to) {
        if (from < 0) return null;
        StringBuilder b = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (i > from) b.append('\n');
            b.append(stripTrailingCr(lines[i]));
        }
        return b.toString();
    }

    /**
     * True when this line ends by opening a block. The opener is not always the LAST token: the marked form
     * {@code !!block?end=END} is tokenized as {@code command} + {@code command-param} + {@code block-end}, so the
     * opener is followed by its own parameter tokens and nothing else.
     */
    private static boolean opensBlock(List<Token> toks) {
        for (int i = toks.size() - 1; i >= 0; i--) {
            TokenName name = toks.get(i).tokenName();
            if (name.equals(TokenName.COMMAND_PARAM) || name.equals(TokenName.BLOCK_END)) continue;
            return isBlockOpener(toks.get(i));
        }
        return false;
    }

    /** A block that opened but has no body lines still IS a block — with an empty value. */
    private static String orEmpty(@Nullable String body) {
        return body != null ? body : "";
    }

    public static List<DdotEvent> parse(String text, String kind, String source) {
        return parse(text, kind, source, false);
    }

    /**
     * Parse with an explicit initial excluded state: {@code startExcluded} is {@code true} for a chunk that begins
     * inside a host-level `!!off` … `!!on` region (see {@link DdotTokenizer#tokenize(String, boolean)}).
     */
    public static List<DdotEvent> parse(String text, String sourceKind, String sourceUri, boolean startExcluded) {
        String[] lines = text.split("\n", -1);
        List<List<Token>> byLine = groupByLine(DdotTokenizer.tokenize(text, startExcluded), lines.length);

        Set<Integer> consumed = new HashSet<>();
        Map<Integer, String> blockBodies = collectBlockBodies(lines, byLine, consumed);

        List<DdotEvent> events = new ArrayList<>();
        String currentSubject = null;
        DdotEvent openMetaEvent = null;   // the event a `,,` block is filling
        DdotEvent lastEvent = null;
        // A `,,` block holds either triples or text, never both, and a whole
        // MetaTextBlock is ONE node (`MetaTextBlock *-- "1..n" MetaTextBlockLine`)
        // — so its lines join into a single `text` entry, not one per line.
        List<String> metaTextLines = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            List<Token> toks = byLine.get(i);
            // A blank line ABORTS an open (unmarked) `,,` block: all its lines,
            // the opening triple line included, become NotATriple. Blank lines
            // consumed as a `!!block` terminator are part of that block's
            // construct and do not count.
            if (openMetaEvent != null && !consumed.contains(i) && toks.isEmpty() && lines[i].isBlank()) {
                events.remove(openMetaEvent);
                if (lastEvent == openMetaEvent) lastEvent = null;
                openMetaEvent = null;
                metaTextLines.clear();
                continue;
            }
            if (toks.isEmpty() || consumed.contains(i)) continue;
            // `!!off` … `!!on`: the directives and everything between them are
            // processed by the reader and never emitted (developer guide).
            if (toks.getFirst().tokenName().equals(TokenName.EXCLUDED)) continue;
            if (toks.getFirst().tokenName().equals(TokenName.COMMAND) && PreCommand.OFF_CMD.matcher(toks.getFirst().text()).matches()) {
                // `!!off` opens a new snippet: the subject register does NOT
                // survive it (parsing-procedure: `mode = OFF; subject = null`),
                // and neither does the "previous triple" a `,,` could attach to.
                // A `,,` block still open here is unterminated — see below.
                currentSubject = null;
                lastEvent = null;
                if (openMetaEvent != null) {
                    events.remove(openMetaEvent);
                    openMetaEvent = null;
                    metaTextLines.clear();
                }
                continue;
            }
            // An `!!on` directive line (the tokenizer emits only the command and
            // its optional param for it). An `!!on`-shaped SUBJECT of a real
            // triple line is not a directive here — it has slot tokens too.
            if (toks.getFirst().tokenName().equals(TokenName.COMMAND) && PreCommand.ON_CMD.matcher(toks.getFirst().text()).matches() && toks.stream().allMatch(t -> t.tokenName().equals(TokenName.COMMAND) || t.tokenName().equals(TokenName.COMMAND_PARAM)))
                continue;

            // A `,,` alone on a line opens a metadata block for the preceding
            // triple, or closes the one that is open.
            if (toks.size() == 1 && toks.getFirst().tokenName().equals(TokenName.META_DELIM)) {
                if (openMetaEvent != null) {
                    flushMetaText(openMetaEvent, metaTextLines);
                    openMetaEvent = null;
                } else {
                    openMetaEvent = lastEvent;
                }
                continue;
            }

            boolean inMetaBlock = openMetaEvent != null;
            Line parsed = readLine(toks, blockBodies, inMetaBlock ? DdotSlot.META_START : DdotSlot.SUBJECT);

            if (inMetaBlock) {
                for (IMetaPair mp : parsed.meta) {
                    if (DdotVocabulary.TEXT.equals(mp.type())) metaTextLines.add(mp.to());
                    else openMetaEvent.addMeta(mp.type(), mp.to());
                }
                continue;
            }

            // A metadata CONTINUATION line (`,, ..since.. 2016` after a block
            // object) belongs to the triple already emitted, not to a new one.
            if ((toks.getFirst().tokenName().equals(TokenName.META_DELIM) || toks.getFirst().tokenName().equals(TokenName.META_SEPARATOR)) && lastEvent != null && parsed.object == null) {
                lastEvent.addMeta(parsed.meta);
                continue;
            }

            // A block filling the SUBJECT slot puts a subject in scope without
            // producing a triple of its own (`!!block` … then `..knows.. Bob`).
            if (parsed.subject != null) currentSubject = parsed.subject;
            if (parsed.object == null) continue;

            String from = currentSubject;
            if (from == null) continue;   // no subject in scope: not a triple

            ISourceLocation sourceLoc = ISourceLocation.of(ISourceResourceAddress.of(sourceUri), 1);
            ISnippet snippet = new Snippet(sourceKind, sourceLoc);
            DdotEvent event = new DdotEvent(snippet, from, parsed.relation, parsed.object, i + 1);
            event.addMeta(parsed.meta);
            events.add(event);
            lastEvent = event;
            if (parsed.opensMetaBlock) openMetaEvent = event;
        }

        // An unterminated `,,` block produces NO triple: the `Meta` alternative
        // requires the closing `,,`, so the whole construct — the triple that
        // opened it and every accumulated meta line — fails to derive and all
        // its lines are NotATriple. (Highlighters may render on; the EVENT
        // stream must not.)
        if (openMetaEvent != null) {
            events.remove(openMetaEvent);
            metaTextLines.clear();
        }

        return events;
    }

    /**
     * Replay the tokenizer's phase transitions across one line's tokens and read the slot values back out.
     * {@code startPhase} is {@code SUBJECT} for an ordinary line and {@code META_START} for a line inside a `,,` block,
     * which is where the tokenizer itself starts those lines — without it a block's `..year.. 2010` would be read as a
     * relation and an object rather than as a meta pair, and silently dropped.
     */
    private static Line readLine(List<Token> toks, Map<Integer, String> blockBodies, DdotSlot startPhase) {
        Line line = new Line();
        DdotSlot phase = startPhase;
        String pendingMetaRelation = null;
        boolean sawMetaDelim = false;

        for (Token t : toks) {
            switch (t.tokenName()) {
                case TokenName.DOUBLEDOT, TokenName.META_DOUBLEDOT -> phase = advance(phase, isQuad(t.text()));
                case TokenName.META_DELIM -> {
                    phase = DdotSlot.META_START;
                    sawMetaDelim = true;
                }
                // `;;` ends the current pair and starts the next one.
                case TokenName.META_SEPARATOR -> {
                    phase = DdotSlot.META_START;
                    pendingMetaRelation = null;
                }
                // `?end=` / the END marker of a marked block opener, and block body
                // lines: all belong to the block, never to the slot it fills.
                case TokenName.COMMAND_PARAM, TokenName.BLOCK_END, TokenName.VERBATIM -> {
                }
                default -> {
                    String value = valueOf(t, blockBodies);
                    switch (phase) {
                        case SUBJECT -> line.subject = value;
                        case RELATION -> line.relation = value;
                        case OBJECT -> line.object = value;
                        // Text directly after `,,` with no `..` is meta TEXT,
                        // which carries the built-in relation `text`.
                        case META_START -> line.meta.add(DdotVocabulary.TEXT, value);
                        case META_RELATION -> pendingMetaRelation = value;
                        // The untyped meta shortcut (`,, .... value`) leaves no
                        // relation pending; like an untyped triple it carries
                        // `links to` (see IMetaPair#type()), never a null type.
                        case META_OBJECT -> {
                            line.meta.add(pendingMetaRelation != null ? pendingMetaRelation : DdotVocabulary.LINKS_TO,
                                    value);
                            pendingMetaRelation = null;
                        }
                    }
                }
            }
        }

        // A trailing `,,` with nothing after it opens a multi-line meta block.
        line.opensMetaBlock = sawMetaDelim && line.meta.isEmpty() && pendingMetaRelation == null && (phase == DdotSlot.META_START);
        return line;
    }

    private static String stripTrailingCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * The text a slot token contributes: a block opener contributes its block's body, every other token its own text.
     */
    private static String valueOf(Token t, Map<Integer, String> blockBodies) {
        if (t.tokenName().equals(TokenName.COMMAND) && PreCommand.BLOCK_OPENER.matcher(t.text()).matches()) {
            // Only a block opener that ENDS its line opens a block. Elsewhere —
            // `x ..y.. !!block ,, ..k.. v` — `!!block` is ordinary field text,
            // because "a Command is a part of the text of the field holding it,
            // not a replacement for it" (Parse Spec). Falling through to null
            // here would silently drop the whole triple.
            String body = blockBodies.get(t.line());
            return body != null ? body : t.text();
        }
        return t.text();
    }

}
