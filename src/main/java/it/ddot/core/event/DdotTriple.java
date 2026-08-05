package it.ddot.core.event;

import it.ddot.core.DdotVocabulary;

import java.util.Objects;

/**
 * A ddot triple as indexed: subject, predicate, object.
 *
 * <p>This is the identity of a statement — the {@link DdotEvent} fields that say <em>what</em> was
 * said, without the {@code kind}/{@code source}/{@code location} that say where it was said or the
 * meta pairs attached to that one occurrence. Two events differing only in provenance share one
 * {@code DdotTriple}, which is what makes it usable as a map or set key.
 *
 * @param from ⓢ the subject, never empty; an omitted subject is already resolved to the one
 *             inherited from the preceding line
 * @param type Ⓟ the relation, never {@code null}. An untyped link ({@code ....} / {@code .. ..}) carries
 *             {@link DdotVocabulary#LINKS_TO} here, spelled out: it is the same key as any other relation, so a
 *             consumer never has to default a missing one and two spellings of the same link cannot split a map
 *             entry in two.
 * @param to   ⓞ the object, verbatim and never empty; a {@code !!block}-filled slot carries the
 *             block body, newlines included
 */
public record DdotTriple(String from, String type, String to) {

    public DdotTriple {
        Objects.requireNonNull(type, "type must not be null; an untyped link is DdotVocabulary.LINKS_TO");
    }

}
