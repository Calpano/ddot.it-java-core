package it.ddot.core.io.handler;

import it.ddot.core.DdotVocabulary;
import it.ddot.core.event.DdotEvent;
import it.ddot.core.event.IDdotEvent;
import it.ddot.core.io.DdotParser;
import it.ddot.core.source.ISnippet;
import it.ddot.core.io.ISourceHandler;
import it.ddot.core.token.Commands;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjIntConsumer;

/**
 * Turns a stream of source lines into ddot.it triple events, for readers that push text at us line by
 * line ({@link ISourceHandler}).
 *
 * <p><strong>This class owns no grammar.</strong> It is an adapter over {@link DdotParser}, which reads
 * the token stream from {@code DdotTokenizer} — the one implementation the golden corpus pins. It used
 * to carry a second, hand-rolled parser built from regexes, and the two drifted exactly as you would
 * expect: the tokenizer learned the {@code ;;} inline-meta separator and multi-entry meta blocks, and
 * this side never did, so corpus cases 36–42 produced merged or dropped meta entries here while passing
 * through {@code DdotParser}. Anything that looks like grammar belongs in the tokenizer.
 *
 * <h2>Streaming</h2>
 * Lines stream in and events stream out; what this class adds is a buffer, because the unit a ddot.it
 * grammar can decide is not a line. A {@code ,,} block, a {@code !!block} body and an {@code !!off}
 * region all span lines, and an unterminated one retroactively cancels the triple that opened it — so a
 * line cannot be turned into an event until the construct it belongs to has closed. The buffer is
 * flushed, parsed and emitted at every point where no construct can still be open:
 * <ul>
 *   <li>{@link #sourceResourceEnd()} and {@link #sourceEnd()} — the resource is complete;</li>
 *   <li>{@link #resetSubject()} — a structural boundary from
 *       {@link StructuredSource2TripleEvents#blockEnd()}, e.g. the end of a markdown section;</li>
 *   <li>{@link #emitTriple} — so a reader that mixes structured extraction with line text keeps its
 *       events in source order.</li>
 * </ul>
 * Memory is therefore bounded by the largest chunk between two such boundaries, not by the document.
 */
public class Source2TripleEvents implements ISourceHandler {

    private final ISourceHandler sourceHandler;

    /** Lines awaiting the close of whatever construct they may be part of. */
    private final List<String> buffered = new ArrayList<>();
    /** Line number of {@link #buffered}'s first line, so events carry snippet-relative numbers. */
    private int bufferFirstLine = 1;

    /**
     * Whether the NEXT chunk starts inside an {@code !!off} region. The parser resolves
     * {@code !!off} / {@code !!on} within a chunk, but the region can outlive a flush boundary, so the
     * state has to be carried across — using the tokenizer's own matchers, never a private copy.
     */
    private boolean excluded;
    private boolean enabled;
    private @Nullable String subject;
    private ISnippet snippet = ISnippet.createUndefined();

    public Source2TripleEvents(ISourceHandler sourceHandler) {
        this.sourceHandler = sourceHandler;
    }

    /**
     * Normalises a token to its bare command spelling for command recognition: strips a leading {@code http://} /
     * {@code https://} scheme (and an optional {@code www.}) so the full-URL form of a command — e.g.
     * {@code https://ddot.it/off} — is recognised exactly like the bare {@code ddot.it/off} token. A {@code ddot.it/…}
     * command IS a self-documenting URL, so authors naturally paste the full URL; without this it was silently treated
     * as a value. Tokens without a scheme (including every {@code !!} shorthand) are returned unchanged, so this never
     * alters an ordinary object URL that is not a command.
     *
     * <p>Public so tooling that mirrors the reader's command recognition ({@code lint.DdotLinter}) stays in sync.
     */
    public static String commandToken(String token) {
        return Commands.normalizeCommandToken(token.strip());
    }

    @Override
    public void activeSubject(String subject) {
        this.subject = subject;
    }

    @Override
    public @Nullable String activeSubject() {
        return subject;
    }

    /**
     * Emit a triple programmatically, bypassing line parsing. Used by readers that recognise structured triple content
     * where the object cannot be expressed on a single {@code S .. P .. O} line — e.g. the HTML {@code <ddot-it>}
     * custom element, whose {@code has content} value may contain newlines and markup. Resolves {@code ddot.it/this} in
     * the subject and records it as the active subject. No-op when processing is disabled or subject/value are empty.
     *
     * @param type       {@code null} or empty means an untyped link and is emitted as {@link DdotVocabulary#LINKS_TO}
     * @param lineNumber counted from 1 at the start of the current {@link ISnippet}, exactly like
     *                   {@link #onLine(String, int)} — not a file line, unless the snippet is the whole file
     */
    public void emitTriple(String rawFrom, @Nullable String type, String value, int lineNumber) {
        if (!enabled) return;
        if (rawFrom == null || rawFrom.isEmpty() || value == null || value.isEmpty()) return;
        // Order matters: any buffered line text precedes this call in the source.
        flush();
        // Verbatim, exactly like the line-parsing path: resolving `!!this` is the collector's job
        // (Parse Spec, "Commands are not resolved at this layer"). TripleEvent2Kgraph does it.
        String from = rawFrom;
        subject = from;
        String relation = (type == null || type.isEmpty()) ? DdotVocabulary.LINKS_TO : type;
        onTripleEvent(new DdotEvent(snippet, from, relation, value, lineNumber));
    }

    /**
     * IMPL note: Param order fits {@link ObjIntConsumer}.
     *
     * @param line       of text
     * @param lineNumber counted from 1 at the start of the current {@link ISnippet}, which is what an emitted event
     *                   reports as {@link IDdotEvent#startLineInSnippet()}. A reader that lifts an embedded chunk
     *                   out of a file numbers the chunk's own lines here and puts the chunk's file position into the
     *                   snippet; the two are added back together by {@link IDdotEvent#startLineInResource()}.
     */
    @Override
    public void onLine(String line, int lineNumber) {
        if (!enabled) return;
        // A gap in line numbers means the reader skipped host material that is not ours (markdown prose
        // between two ddot fences, say). Joining across it would let a `,,` block swallow the gap, so the
        // pending chunk is closed first and the new one starts its own numbering.
        if (!buffered.isEmpty() && lineNumber != bufferFirstLine + buffered.size()) flush();
        if (buffered.isEmpty()) bufferFirstLine = lineNumber;
        buffered.add(line);
        emitSettled();
    }

    /**
     * Emit everything that no later line can still change, and keep the rest buffered.
     *
     * <p>Two things can still change: a construct that is open ({@code endsOpen}), and the LAST event —
     * a following {@code ,,} or {@code ;;} line attaches metadata to the triple before it, so a triple
     * is only final once a line arrives that does not extend it. Holding one event back is what makes
     * that work; without it, corpus cases 09 and 33 lost the note entirely, because the triple had
     * already been emitted and flushed out of the buffer by the time its `,,` arrived.
     */
    private void emitSettled() {
        DdotParser.Chunk chunk = parseBuffer();
        if (chunk.endsOpen()) return;

        List<DdotEvent> events = chunk.events();
        if (events.isEmpty()) {
            // Nothing to attach to and nothing open: these lines are settled (blank, `!!off`, prose) —
            // but they may still have put a SUBJECT in scope, which a `!!block` in the subject slot does
            // without emitting anything. Carrying it is what lets the `..knows.. Bob` after such a block
            // find its subject (corpus cases 29 and 32).
            excluded = chunk.endsExcluded();
            subject = chunk.endSubject();
            buffered.clear();
            return;
        }
        for (DdotEvent event : events.subList(0, events.size() - 1)) emit(event);
        // Retain the last event's lines — its opening line through the end of the buffer, which for a
        // block is its body too — so re-parsing with the next line can still extend it.
        int keepFrom = events.getLast().startLineInSnippet() - bufferFirstLine;
        if (keepFrom > 0) {
            List<String> tail = new ArrayList<>(buffered.subList(keepFrom, buffered.size()));
            buffered.clear();
            buffered.addAll(tail);
            bufferFirstLine += keepFrom;
        }
    }

    /** Parse what is buffered, without consuming it. */
    private DdotParser.Chunk parseBuffer() {
        return DdotParser.parseChunk(String.join("\n", buffered), snippet, excluded, bufferFirstLine, subject);
    }

    private void emit(DdotEvent event) {
        subject = event.from();
        onTripleEvent(event);
    }

    @Override
    public void onTripleEvent(IDdotEvent ddotEvent) {
        sourceHandler.onTripleEvent(ddotEvent);
    }

    /**
     * Close the current chunk: a structural boundary in the host document (end of a markdown section,
     * say) ends any ddot construct that was still open, and drops the subject that was in scope.
     */
    public void resetSubject() {
        flush();
        subject = null;
    }

    @Override
    public void sourceEnd() {
        flush();
    }

    @Override
    public void sourceResourceEnd() {
        flush();
        // A new resource starts clean: neither a subject nor an !!off region carries into it.
        subject = null;
        excluded = false;
    }

    @Override
    public void sourceResourceStart(ISnippet snippet) {
        this.snippet = snippet;
    }

    @Override
    public void sourceStart() {
        // A fresh source starts clean: an `!!off` left open by whatever ran before must not silence it.
        this.enabled = true;
        this.excluded = false;
        this.subject = null;
    }

    /** Parse everything buffered so far and push the resulting events downstream. */
    private void flush() {
        if (buffered.isEmpty()) return;
        List<String> chunk = List.copyOf(buffered);
        buffered.clear();

        DdotParser.Chunk parsed =
                DdotParser.parseChunk(String.join("\n", chunk), snippet, excluded, bufferFirstLine, subject);
        excluded = parsed.endsExcluded();
        for (DdotEvent event : parsed.events()) emit(event);
        // The parser owns subject scoping — including the fact that `!!off` clears it — so take the
        // subject we seed the next chunk with from the parse, never from the last event we happened
        // to emit.
        subject = parsed.endSubject();
    }



}
