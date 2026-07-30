package com.calpano.ddot.it.event;

import com.calpano.ddot.it.token.DdotTokenizer;
import com.calpano.ddot.it.token.DdotTokenizer.Token;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Emits the ddot.it event stream (see
 * <a href="https://ddot.it/developer-guide.html#events">the events spec</a>) as
 * JSONL, asserted against {@code expected.events.jsonl} in the shared corpus by
 * {@code CorpusConformanceTest} here and by {@code PreviewHtmlGoldenTest} in the
 * IntelliJ plugin.
 *
 * <p><strong>This is a fold over {@link DdotTokenizer}, not a parser.</strong>
 * It used to be an independent line-oriented regex parser, and that is precisely
 * why it silently dropped four spec'd constructs: {@code !!block} bodies (the
 * literal {@code "!!block"} was exported as the field value), free meta text,
 * {@code ;;}-separated inline meta pairs, and the untyped {@code ,, .... value}
 * shortcut. The tokenizer already implements all of them and is pinned to the
 * canonical TextMate grammar by the token corpus, so consuming its output makes
 * those divergences impossible rather than merely fixed.
 *
 * <p>Reconstruction is positional: the tokenizer's slot phases are recovered by
 * replaying its separator transitions over each line's tokens. This matters for
 * slots whose entire text is a command ({@code !!this}, {@code !!block}), which
 * the tokenizer reports as {@code command} — the token name alone no longer says
 * which slot it filled, but its position does.
 */
public final class DdotEventExporter {
    private DdotEventExporter() {}

    /** The built-in relation carried by free meta text (Parse Spec, Information Model). */
    private static final String TEXT_RELATION = "text";

    private static final Pattern OFF_CMD =
            Pattern.compile("(?:(?:https?://)?ddot\\.it/|!!)off");
    private static final Pattern ON_CMD =
            Pattern.compile("(?:(?:https?://)?ddot\\.it/|!!)on");
    private static final Pattern BLOCK_OPENER =
            Pattern.compile("(?:(?:https?://)?ddot\\.it/|!!)block(?:\\?end=\\S+)?");

    // Token names produced by DdotTokenizer (see test-data/tokens.md).
    private static final String T_SUBJECT = "subject";
    private static final String T_RELATION = "relation";
    private static final String T_OBJECT = "object";
    private static final String T_DOUBLEDOT = "doubledot";
    private static final String T_META_DOUBLEDOT = "meta-doubledot";
    private static final String T_META_DELIM = "meta-delim";
    private static final String T_META_SEPARATOR = "meta-separator";
    private static final String T_META_RELATION = "meta-relation";
    private static final String T_META_OBJECT = "meta-object";
    private static final String T_META_TEXT = "meta-text";
    private static final String T_COMMAND = "command";
    private static final String T_COMMAND_PARAM = "command-param";
    private static final String T_VERBATIM = "verbatim";
    private static final String T_BLOCK_END = "block-end";
    private static final String T_EXCLUDED = "excluded";

    /** Slot phases, mirroring {@code DdotTokenizer.Phase}. */
    private enum Phase { SUBJECT, RELATION, OBJECT, META_START, META_REL, META_OBJ }

    public static List<DdotEvent> parse(String text,
                                                 String kind,
                                                 String source) {
        return parse(text, kind, source, false);
    }

    /**
     * Parse with an explicit initial excluded state: {@code startExcluded} is
     * {@code true} for a chunk that begins inside a host-level `!!off` … `!!on`
     * region (see {@link DdotTokenizer#tokenize(String, boolean)}).
     */
    public static List<DdotEvent> parse(String text,
                                                 String kind,
                                                 String source,
                                                 boolean startExcluded) {
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
            if (openMetaEvent != null && !consumed.contains(i)
                    && toks.isEmpty() && lines[i].isBlank()) {
                events.remove(openMetaEvent);
                if (lastEvent == openMetaEvent) lastEvent = null;
                openMetaEvent = null;
                metaTextLines.clear();
                continue;
            }
            if (toks.isEmpty() || consumed.contains(i)) continue;
            // `!!off` … `!!on`: the directives and everything between them are
            // processed by the reader and never emitted (developer guide).
            if (toks.get(0).token().equals(T_EXCLUDED)) continue;
            if (toks.get(0).token().equals(T_COMMAND)
                    && OFF_CMD.matcher(toks.get(0).text()).matches()) {
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
            if (toks.get(0).token().equals(T_COMMAND)
                    && ON_CMD.matcher(toks.get(0).text()).matches()
                    && toks.stream().allMatch(t -> t.token().equals(T_COMMAND)
                            || t.token().equals(T_COMMAND_PARAM))) continue;

            // A `,,` alone on a line opens a metadata block for the preceding
            // triple, or closes the one that is open.
            if (toks.size() == 1 && toks.get(0).token().equals(T_META_DELIM)) {
                if (openMetaEvent != null) {
                    flushMetaText(openMetaEvent, metaTextLines);
                    openMetaEvent = null;
                } else {
                    openMetaEvent = lastEvent;
                }
                continue;
            }

            boolean inMetaBlock = openMetaEvent != null;
            Line parsed = readLine(toks, blockBodies,
                    inMetaBlock ? Phase.META_START : Phase.SUBJECT);

            if (inMetaBlock) {
                for (DdotEvent.MetaPair mp : parsed.meta) {
                    if (TEXT_RELATION.equals(mp.type())) metaTextLines.add(mp.to());
                    else openMetaEvent.meta.add(mp);
                }
                continue;
            }

            // A metadata CONTINUATION line (`,, ..since.. 2016` after a block
            // object) belongs to the triple already emitted, not to a new one.
            if ((toks.get(0).token().equals(T_META_DELIM)
                    || toks.get(0).token().equals(T_META_SEPARATOR))
                    && lastEvent != null && parsed.object == null) {
                lastEvent.meta.addAll(parsed.meta);
                continue;
            }

            // A block filling the SUBJECT slot puts a subject in scope without
            // producing a triple of its own (`!!block` … then `..knows.. Bob`).
            if (parsed.subject != null) currentSubject = parsed.subject;
            if (parsed.object == null) continue;

            String from = currentSubject;
            if (from == null) continue;   // no subject in scope: not a triple

            DdotEvent event = new DdotEvent(from, parsed.relation, parsed.object, kind, source, i + 1);
            event.meta.addAll(parsed.meta);
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

    private static void flushMetaText(DdotEvent event, List<String> lines) {
        if (lines.isEmpty()) return;
        event.meta.add(new DdotEvent.MetaPair(TEXT_RELATION, String.join("\n", lines)));
        lines.clear();
    }

    // --- line reconstruction ------------------------------------------------

    private static final class Line {
        @Nullable String subject, relation, object;
        final List<DdotEvent.MetaPair> meta = new ArrayList<>();
        boolean opensMetaBlock;
    }

    /**
     * Replay the tokenizer's phase transitions across one line's tokens and read
     * the slot values back out. {@code startPhase} is {@code SUBJECT} for an
     * ordinary line and {@code META_START} for a line inside a `,,` block, which
     * is where the tokenizer itself starts those lines — without it a block's
     * `..year.. 2010` would be read as a relation and an object rather than as a
     * meta pair, and silently dropped.
     */
    private static Line readLine(List<Token> toks, Map<Integer, String> blockBodies,
                                 Phase startPhase) {
        Line line = new Line();
        Phase phase = startPhase;
        String pendingMetaRelation = null;
        boolean sawMetaDelim = false;

        for (Token t : toks) {
            switch (t.token()) {
                case T_DOUBLEDOT, T_META_DOUBLEDOT -> phase = advance(phase, isQuad(t.text()));
                case T_META_DELIM -> { phase = Phase.META_START; sawMetaDelim = true; }
                // `;;` ends the current pair and starts the next one.
                case T_META_SEPARATOR -> { phase = Phase.META_START; pendingMetaRelation = null; }
                // `?end=` / the END marker of a marked block opener, and block body
                // lines: all belong to the block, never to the slot it fills.
                case T_COMMAND_PARAM, T_BLOCK_END, T_VERBATIM -> { }
                default -> {
                    String value = valueOf(t, blockBodies);
                    if (value != null) {
                        switch (phase) {
                            case SUBJECT -> line.subject = value;
                            case RELATION -> line.relation = value;
                            case OBJECT -> line.object = value;
                            // Text directly after `,,` with no `..` is meta TEXT,
                            // which carries the built-in relation `text`.
                            case META_START -> line.meta.add(new DdotEvent.MetaPair(TEXT_RELATION, value));
                            case META_REL -> pendingMetaRelation = value;
                            case META_OBJ -> {
                                line.meta.add(new DdotEvent.MetaPair(pendingMetaRelation, value));
                                pendingMetaRelation = null;
                            }
                        }
                    }
                }
            }
        }

        // A trailing `,,` with nothing after it opens a multi-line meta block.
        line.opensMetaBlock = sawMetaDelim && line.meta.isEmpty() && pendingMetaRelation == null
                && (phase == Phase.META_START);
        return line;
    }

    /**
     * The text a slot token contributes: a block opener contributes its block's
     * body, every other token its own text.
     */
    private static @Nullable String valueOf(Token t, Map<Integer, String> blockBodies) {
        if (t.token().equals(T_COMMAND) && BLOCK_OPENER.matcher(t.text()).matches()) {
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

    private static boolean isQuad(String sep) {
        return sep.length() == 4 || sep.indexOf(' ') >= 0 || sep.indexOf('\t') >= 0;
    }

    /** The tokenizer's transition table; a QuadDot skips the relation slot. */
    private static Phase advance(Phase phase, boolean quad) {
        return switch (phase) {
            case SUBJECT -> quad ? Phase.OBJECT : Phase.RELATION;
            case RELATION -> Phase.OBJECT;
            case OBJECT -> Phase.OBJECT;
            case META_START -> quad ? Phase.META_OBJ : Phase.META_REL;
            case META_REL, META_OBJ -> Phase.META_OBJ;
        };
    }

    // --- `!!block` bodies ---------------------------------------------------

    /**
     * Map each block opener's line to its body text, and mark the body lines
     * consumed so the event pass skips them.
     *
     * <p>The body is taken from the RAW lines, not from the {@code verbatim}
     * tokens: a block is verbatim, and the tokens are trimmed, which would drop
     * indentation. Blank lines inside a marked ({@code ?end=}) block survive, and
     * are recovered from the gaps in the verbatim token line numbers — the
     * unmarked form cannot produce such a gap, because a blank line ends it.
     */
    private static Map<Integer, String> collectBlockBodies(String[] lines,
                                                           List<List<Token>> byLine,
                                                           Set<Integer> consumed) {
        Map<Integer, String> bodies = new HashMap<>();
        int openerLine = -1;
        boolean marked = false;
        int firstBodyLine = -1, lastBodyLine = -1;

        for (int i = 0; i < lines.length; i++) {
            List<Token> toks = byLine.get(i);

            if (openerLine >= 0) {
                boolean isBlockEnd = !toks.isEmpty() && toks.get(0).token().equals(T_BLOCK_END);
                boolean isBody = !toks.isEmpty() && toks.get(0).token().equals(T_VERBATIM);
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
                if (marked && !isBlockEnd) { consumed.add(i); continue; }

                bodies.put(openerLine, orEmpty(join(lines, firstBodyLine, lastBodyLine)));
                if (isBlockEnd) consumed.add(i);
                // The blank TERMINATOR of an unmarked block is part of the
                // block construct, not a free blank line — consume it.
                else if (toks.isEmpty()) consumed.add(i);
                openerLine = -1; marked = false; firstBodyLine = -1; lastBodyLine = -1;
                if (isBlockEnd || toks.isEmpty()) continue;
                // else: fall through — this line is ordinary content again
            }

            if (opensBlock(toks)) {
                openerLine = i;
                marked = toks.stream().anyMatch(t -> t.token().equals(T_BLOCK_END));
            }
        }
        if (openerLine >= 0) bodies.put(openerLine, orEmpty(join(lines, firstBodyLine, lastBodyLine)));
        return bodies;
    }

    private static boolean isBlockOpener(Token t) {
        return t.token().equals(T_COMMAND) && BLOCK_OPENER.matcher(t.text()).matches();
    }

    /**
     * True when this line ends by opening a block. The opener is not always the
     * LAST token: the marked form {@code !!block?end=END} is tokenized as
     * {@code command} + {@code command-param} + {@code block-end}, so the opener
     * is followed by its own parameter tokens and nothing else.
     */
    private static boolean opensBlock(List<Token> toks) {
        for (int i = toks.size() - 1; i >= 0; i--) {
            String name = toks.get(i).token();
            if (name.equals(T_COMMAND_PARAM) || name.equals(T_BLOCK_END)) continue;
            return isBlockOpener(toks.get(i));
        }
        return false;
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

    /** A block that opened but has no body lines still IS a block — with an empty value. */
    private static String orEmpty(@Nullable String body) {
        return body != null ? body : "";
    }

    private static String stripTrailingCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static List<List<Token>> groupByLine(List<Token> tokens, int lineCount) {
        List<List<Token>> byLine = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) byLine.add(new ArrayList<>());
        for (Token t : tokens) {
            if (t.line() >= 0 && t.line() < lineCount) byLine.get(t.line()).add(t);
        }
        return byLine;
    }

    // --- JSONL --------------------------------------------------------------

    public static String toJsonl(List<DdotEvent> events) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(toJson(events.get(i)));
        }
        return out.toString();
    }

    /**
     * Field order matches the spec: from, type?, to, meta?, kind, source, location.
     * Hand-written to keep the output deterministic and dependency-free.
     */
    private static String toJson(DdotEvent e) {
        StringBuilder b = new StringBuilder();
        b.append('{');
        appendField(b, "from", e.from, false);
        if (e.type != null) appendField(b, "type", e.type, true);
        appendField(b, "to", e.to, true);
        if (!e.meta.isEmpty()) {
            b.append(',').append('"').append("meta").append("\":[");
            for (int i = 0; i < e.meta.size(); i++) {
                if (i > 0) b.append(',');
                DdotEvent.MetaPair mp = e.meta.get(i);
                b.append('{');
                boolean typed = mp.type() != null;
                if (typed) appendField(b, "type", mp.type(), false);
                appendField(b, "to", mp.to(), typed);
                b.append('}');
            }
            b.append(']');
        }
        appendField(b, "kind", e.kind, true);
        appendField(b, "source", e.source, true);
        b.append(',').append('"').append("location").append("\":").append(e.location);
        b.append('}');
        return b.toString();
    }

    private static void appendField(StringBuilder b, String key, String value, boolean leadingComma) {
        if (leadingComma) b.append(',');
        b.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
