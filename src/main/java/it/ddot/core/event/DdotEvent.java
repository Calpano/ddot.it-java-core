package it.ddot.core.event;

import it.ddot.core.DdotVocabulary;
import it.ddot.core.source.ISnippet;
import it.ddot.core.source.ISourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * When a ddot.it parser reads text ands find a triple, it sends a {@code DdotEvent} to the collector.
 * <p>
 * The schema is documented at
 * <a href="https://ddot.it/spec/ddot-it-parse.html#events">ddot.it events</a>.
 *
 * <p>One event per recognized triple; a {@code NotATriple} line emits nothing at all — that is not
 * an error and has no representation. All values are carried <strong>verbatim</strong>: no unescaping, no quote
 * stripping, no HTML escaping. Commands are not resolved at this layer — {@code !!this} is just a value, and resolving
 * it against {@link #sourceUri()} is the collector's job.
 * <p>
 * A slot filled by a {@code !!block} holds the block body here, real newlines included (they become {@code \n} in the
 * JSON string).
 */
public final class DdotEvent implements IDdotEvent, IMeta {

    public final String from;
    public final String type;
    public final String to;
    public final ISnippet snippet;
    /**
     * The <strong>1-based</strong> line number of the triple's first line, relative to the start of {@link #snippet}
     * — <em>not</em> a line in the file; see {@link IDdotEvent#startLineInResource()} for that. A triple whose
     * subject or object is a {@code !!block} is located at the block's <em>opening</em> line, not at its body.
     */
    public final int startLineInSnippet;
    /** This triple's metadata, in written order; a relation may repeat (see {@link IMeta}). */
    private final List<IMetaPair> metaPairs = new ArrayList<>();

    /**
     * @param type never {@code null}: an untyped {@code ....} link carries {@link DdotVocabulary#LINKS_TO}
     *             explicitly, so that no consumer has to default a missing relation.
     */
    public DdotEvent(ISnippet snippet, String from, String type, String to, int startLineInSnippet) {
        this.snippet = snippet;
        this.from = from;
        this.type = Objects.requireNonNull(type, "type must not be null; an untyped link is DdotVocabulary.LINKS_TO");
        this.to = to;
        this.startLineInSnippet = startLineInSnippet;
    }

    public static IDdotEvent of(String from, String type, String to, ISourceLocation loc) {
        ISnippet snippet = ISnippet.of("unknownSourceKind", loc);
        return new DdotEvent(snippet, from, type, to, 1);
    }

    public static IDdotEvent of(String from, String type, String to, IMeta meta, ISourceLocation loc) {
        ISnippet snippet = ISnippet.of("unknownSourceKind", loc);
        DdotEvent de = new DdotEvent(snippet, from, type, to, 1);
        de.addMeta(meta);
        return de;
    }

    /** Appends one pair. A relation already present is NOT replaced: both entries are kept, in written order. */
    public void addMeta(String type, String to) {
        metaPairs.add(IMetaPair.of(type, to));
    }

    /** Appends every pair of {@code meta}, keeping its order. */
    public void addMeta(IMeta meta) {
        metaPairs.addAll(meta.pairs());
    }

    @Override
    public String from() {
        return from;
    }

    @Override
    public List<IMetaPair> pairs() {
        return Collections.unmodifiableList(metaPairs);
    }

    @Override
    public IMeta meta() {
        return this;
    }

    @Override
    public ISnippet snippet() {
        return snippet;
    }

    @Override
    public int startLineInSnippet() {
        return startLineInSnippet;
    }

    @Override
    public String to() {
        return to;
    }

    @Override
    public String type() {
        return type;
    }

}
