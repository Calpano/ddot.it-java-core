package com.calpano.ddot.it.core.event;

import it.ddot.core.event.DdotEvent;
import org.jspecify.annotations.Nullable;

/**
 * A ddot triple as indexed: subject, optional predicate (null = untyped link), object.
 *
 * <p>This is the identity of a statement — the {@link DdotEvent} fields that say <em>what</em> was
 * said, without the {@code kind}/{@code source}/{@code location} that say where it was said or the
 * meta pairs attached to that one occurrence. Two events differing only in provenance share one
 * {@code DdotTriple}, which is what makes it usable as a map or set key.
 *
 * @param from ⓢ the subject, never empty; an omitted subject is already resolved to the one
 *             inherited from the preceding line
 * @param type Ⓟ the relation, or {@code null} for an untyped link ({@code ....} / {@code .. ..}),
 *             which a consumer reads as the built-in
 *             <a href="https://ddot.it/spec/ddot-it-vocabulary.html#links-to">{@code links to}</a>.
 *             Null is a distinct key from the literal string {@code "links to"}.
 * @param to   ⓞ the object, verbatim and never empty; a {@code !!block}-filled slot carries the
 *             block body, newlines included
 */
public record DdotTriple(String from, @Nullable String type, String to) {}
