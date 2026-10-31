package it.ddot.core.cds;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The CDS vocabulary in its ddot.it names, and the axioms that come with it.
 *
 * <p>Normative source: <a href="https://ddot.it/spec/ddot-it-cds.html">CDS &amp; Standards
 * Alignment</a>. The names are the ddot.it ones, never the CDS ids or labels: those read in
 * different directions ({@code hasAfter} is labelled "comes before"), and a name that means one
 * thing is worth more than two that each need a footnote.
 */
public final class Cds {

    public static final String RELATED = "related";
    public static final String HAS_SIMILAR = "has similar";
    public static final String LINKS_TO = "links to";
    public static final String IS_LINKED_FROM = "is linked from";
    public static final String HAS_NEXT = "has next";
    public static final String HAS_PREVIOUS = "has previous";
    public static final String HAS_DETAIL = "has detail";
    public static final String HAS_CONTEXT = "has context";
    public static final String ANNOTATES = "annotates";
    public static final String HAS_ANNOTATION = "has annotation";
    public static final String IS_TAG_OF = "is tag of";
    public static final String HAS_TAG = "has tag";
    public static final String HAS_INSTANCE = "has instance";
    public static final String HAS_TYPE = "has type";
    public static final String HAS_PART = "has part";
    public static final String IS_PART_OF = "is part of";
    public static final String HAS_SUBTYPE = "has subtype";
    public static final String HAS_SUPERTYPE = "has supertype";
    public static final String SAME_AS = "same as";
    public static final String IS_ALIAS_OF = "is alias of";
    public static final String HAS_ALIAS = "has alias";
    public static final String REPLACES = "replaces";
    public static final String IS_REPLACED_BY = "is replaced by";
    public static final String HAS_INVERSE = "has inverse";

    /** Items, not relations: they classify relations, and {@link #TYPE} classifies types. */
    public static final String RELATION = "relation";
    public static final String SYMMETRIC_RELATION = "symmetric relation";
    public static final String TRANSITIVE_RELATION = "transitive relation";
    public static final String TYPE = "type";

    /**
     * A relation, its inverse, and the relation above it.
     *
     * <p>{@code relation} is the side the spec's mapping table names, which is also the side the
     * axioms place in the hierarchy; the inverse gets its place from r17. A symmetric relation is its
     * own inverse.
     */
    record Pair(String relation, String inverse, @Nullable String superRelation) {}

    static final List<Pair> PAIRS = List.of(
            new Pair(RELATED, RELATED, null),
            new Pair(HAS_INVERSE, HAS_INVERSE, null),
            new Pair(HAS_SIMILAR, HAS_SIMILAR, RELATED),
            new Pair(LINKS_TO, IS_LINKED_FROM, RELATED),
            new Pair(HAS_NEXT, HAS_PREVIOUS, LINKS_TO),
            new Pair(HAS_DETAIL, HAS_CONTEXT, LINKS_TO),
            new Pair(ANNOTATES, HAS_ANNOTATION, LINKS_TO),
            new Pair(IS_TAG_OF, HAS_TAG, ANNOTATES),
            new Pair(HAS_INSTANCE, HAS_TYPE, IS_TAG_OF),
            new Pair(HAS_PART, IS_PART_OF, HAS_DETAIL),
            new Pair(HAS_SUBTYPE, HAS_SUPERTYPE, HAS_DETAIL),
            new Pair(SAME_AS, SAME_AS, HAS_SIMILAR),
            new Pair(IS_ALIAS_OF, HAS_ALIAS, HAS_SIMILAR),
            new Pair(REPLACES, IS_REPLACED_BY, HAS_SIMILAR));

    /** {@code replaces} is transitive in ddot.it only; CDS leaves it out. */
    static final List<String> TRANSITIVE = List.of(HAS_NEXT, SAME_AS, HAS_SUBTYPE, HAS_PART, REPLACES);

    static final List<String> SYMMETRIC = List.of(HAS_INVERSE, SAME_AS, RELATED);

    private Cds() {}

    /** Every name the vocabulary defines: its relations and its item types. */
    public static Set<String> names() {
        Set<String> out = new LinkedHashSet<>();
        for (Pair pair : PAIRS) {
            out.add(pair.relation());
            out.add(pair.inverse());
        }
        out.addAll(List.of(RELATION, SYMMETRIC_RELATION, TRANSITIVE_RELATION, TYPE));
        return Set.copyOf(out);
    }

    /** Everything the vocabulary states before a user has said a word. */
    public static List<Triple> axioms() {
        List<Triple> out = new ArrayList<>();
        for (Pair pair : PAIRS) {
            out.add(new Triple(pair.relation(), HAS_TYPE, RELATION));
            if (!pair.inverse().equals(pair.relation())) out.add(new Triple(pair.inverse(), HAS_TYPE, RELATION));
            out.add(new Triple(pair.relation(), HAS_INVERSE, pair.inverse()));
            if (pair.superRelation() != null) out.add(new Triple(pair.superRelation(), HAS_SUBTYPE, pair.relation()));
        }
        for (String relation : TRANSITIVE) out.add(new Triple(relation, HAS_TYPE, TRANSITIVE_RELATION));
        for (String relation : SYMMETRIC) out.add(new Triple(relation, HAS_TYPE, SYMMETRIC_RELATION));
        out.add(new Triple(RELATION, HAS_SUBTYPE, SYMMETRIC_RELATION));
        out.add(new Triple(RELATION, HAS_SUBTYPE, TRANSITIVE_RELATION));
        return List.copyOf(out);
    }
}
