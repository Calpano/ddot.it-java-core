package it.ddot.core.lint;

import it.ddot.core.cds.Cds;
import it.ddot.core.cds.CdsReasoner;
import it.ddot.core.store.IFact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@value DdotAnalysis#RELATION_ON_BOTH_SIDES}: a user relation that ends up under both {@code links to} and
 * {@code is linked from}.
 *
 * <p>Below {@code has similar}, every inverse pair splits across the two sides of the root: one relation lies
 * under {@code links to}, its inverse under {@code is linked from}. That is what gives a relation its
 * direction (cds-inf.adoc, "Two Sides of Every Pair"). A relation placed on both sides -- directly, or through
 * an explicit inverse that contradicts its side, an alias, a {@code same as} -- has none. The monotonic logic
 * accepts it and canonical direction breaks the tie, but it almost certainly means that one of the statements
 * placing it is the wrong way round.
 *
 * <p>Reported on each statement that places the relation: its {@code has subtype}, {@code has supertype},
 * {@code has inverse}, alias and {@code same as} statements.
 */
final class RelationSides {

    /** The statements that place a relation in the hierarchy, or make it another relation's name. */
    private static final Set<String> PLACING = Set.of(Cds.HAS_SUBTYPE, Cds.HAS_SUPERTYPE, Cds.HAS_INVERSE,
            Cds.IS_ALIAS_OF, Cds.HAS_ALIAS, Cds.SAME_AS);

    private RelationSides() {}

    static List<Finding> check(CdsReasoner cds) {
        Set<String> vocabulary = Cds.names();
        // Every name the store mentions that is a relation and not one of the vocabulary's, once per concept.
        Map<String, String> relations = new LinkedHashMap<>();
        for (IFact f : cds.store().all()) {
            for (String name : List.of(f.subject(), f.relation(), f.object())) {
                if (vocabulary.contains(name) || relations.containsValue(name)) continue;
                if (!cds.isRelation(name)) continue;
                relations.putIfAbsent(cds.canonical(name), name);
            }
        }
        List<Finding> out = new ArrayList<>();
        for (String relation : relations.values()) {
            if (!cds.isSubRelation(relation, Cds.LINKS_TO) || !cds.isSubRelation(relation, Cds.IS_LINKED_FROM)) continue;
            List<Location> placing = placing(cds, relation);
            String message = "'" + relation + "' lies under both '" + Cds.LINKS_TO + "' and '" + Cds.IS_LINKED_FROM
                    + "', so it has no direction. One of the statements that place it is probably the wrong way round.";
            for (Location at : placing) {
                List<Location> others = placing.stream().filter(l -> !l.equals(at)).toList();
                out.add(Finding.onLine(DdotAnalysis.RELATION_ON_BOTH_SIDES, at, message, others));
            }
        }
        return out;
    }

    /** The read statements that place {@code relation} or name it, and the proofs of its two sides. */
    private static List<Location> placing(CdsReasoner cds, String relation) {
        Set<Location> out = new LinkedHashSet<>();
        for (IFact f : cds.store().find(relation, null, null)) add(f, out);
        for (IFact f : cds.store().find(null, null, relation)) add(f, out);
        for (String side : List.of(Cds.LINKS_TO, Cds.IS_LINKED_FROM)) {
            cds.why(side, Cds.HAS_SUBTYPE, relation).ifPresent(why -> why.forEach(f -> add(f, out)));
        }
        return List.copyOf(out);
    }

    private static void add(IFact f, Set<Location> out) {
        if (f.isRead() && PLACING.contains(f.relation())) out.add(DdotAnalysis.locationOf(f));
    }
}
