package com.calpano.ddot.it.core.event;

import com.calpano.ddot.it.core.DdotSourceKinds;
import com.calpano.ddot.it.core.DdotVocabulary;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * When a ddot.it parser reads text ands find a triple, it sends a {@code DdotEvent} to the collector.
 * <p>
 * The schema is documented at
 * <a href="https://ddot.it/spec/ddot-it-parse.html#events">ddot.it events</a>.
 *
 * <p>One event per recognized triple; a {@code NotATriple} line emits nothing at all — that is not
 * an error and has no representation. All values are carried <strong>verbatim</strong>: no unescaping, no quote
 * stripping, no HTML escaping. Commands are not resolved at this layer — {@code !!this} is just a value, and resolving
 * it against {@link #source} is the collector's job.
 * <p>
 * A slot filled by a {@code !!block} holds the block body here, real newlines included (they become {@code \n} in the
 * JSON string).
 */
public final class DdotEvent {

    /**
     * ⓢ The subject, never empty. When the line omitted its subject, the one inherited from the preceding line is
     * substituted here.
     */
    public final String from;

    /**
     * Ⓟ The relation, or {@code null} for an untyped link ({@code ....} / {@code .. ..}). Null is
     * <em>omitted</em> from the JSON rather than written out as {@code links to}; a consumer
     * defaults a missing {@code type} to the built-in {@link DdotVocabulary#LINKS_TO}.
     */
    public final @Nullable String type;

    /**
     * ⓞ The object, never empty — an empty object is not a Triple by the grammar, so no event is emitted for one.
     */
    public final String to;

    /**
     * The triple's metadata pairs in source order, possibly empty. An empty list is omitted from the JSON entirely —
     * never emitted as {@code []}. See {@link MetaPair}.
     */
    public final List<MetaPair> meta = new ArrayList<>();

    /**
     * The host format the reader read, as a short lowercase name. See {@link DdotSourceKinds}.
     */
    public final String kind;

    /**
     * The chunk's source URI, verbatim — e.g. {@code /README.md} or {@code file:///Users/me/example.adoc}. This is the
     * value {@code !!this} stands for, though the substitution happens in the collector, not here.
     */
    public final String source;

    /**
     * The <strong>1-based</strong> line number of the triple's first line, relative to its chunk. A triple whose
     * subject or object is a {@code !!block} is located at the block's <em>opening</em> line, not at its body.
     */
    public final int location;

    public DdotEvent(String from, @Nullable String type, String to, String kind, String source, int location) {
        this.from = from;
        this.type = type;
        this.to = to;
        this.kind = kind;
        this.source = source;
        this.location = location;
    }

}
