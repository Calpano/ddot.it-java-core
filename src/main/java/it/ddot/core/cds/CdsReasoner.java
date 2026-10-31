package it.ddot.core.cds;

import it.ddot.core.store.IFact;
import it.ddot.core.store.TripleStore;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a CDS inference engine answers, over a {@link TripleStore}.
 *
 * <p>{@link CdsInferenceEngine} is the reference implementation and the test oracle. Other
 * implementations exist to be faster, and must give the same answers: the same statements hold,
 * the same relations lie under each other, the same names are canonical. Only {@link #why} may
 * differ, since one statement can have several derivations.
 */
public interface CdsReasoner {

    /** Makes an engine over a store. */
    @FunctionalInterface
    interface Factory {
        CdsReasoner create(TripleStore<?> store, RuleSet rules);
    }

    TripleStore<?> store();

    /** Which rules it applies. */
    RuleSet rules();

    /**
     * Whether a pattern for {@code relation} already follows its chains: the relation is
     * transitive, and chains are among the rules applied. Then {@code p+} asks nothing {@code p}
     * does not answer -- chains follow derived triples as well as stated ones.
     */
    default boolean chains(String relation) {
        return rules().transitive && isTransitive(relation);
    }

    /** Adds a statement to the store, for building one up in a line. */
    default CdsReasoner add(String subject, String relation, String object) {
        store().add(subject, relation, object);
        return this;
    }

    default boolean holds(String subject, String relation, String object) {
        return why(subject, relation, object).isPresent();
    }

    /**
     * The facts one derivation rests on: the store's own, with their provenance, and the axioms.
     * Empty when the statement does not hold.
     */
    Optional<Set<IFact>> why(String subject, String relation, String object);

    /**
     * Every entailed triple matching a pattern, in canonical names; a null position matches
     * anything. What {@link CdsQueryEngine} evaluates a triple pattern with.
     */
    List<Triple> match(@Nullable String subject, @Nullable String relation, @Nullable String object);

    /**
     * Roughly how many triples {@link #match} would list for a pattern, without listing them; -1
     * when the reasoner cannot say cheaply. Only for ordering a query's joins, so it may be off.
     */
    default long estimate(@Nullable String subject, @Nullable String relation, @Nullable String object) {
        return -1;
    }

    /** Whether {@code sub} lies under {@code sup} in the relation hierarchy, reflexively. */
    boolean isSubRelation(String sub, String sup);

    /** The name a concept or relation goes by; a relation's direction is kept, so this may start with {@code ^}. */
    String canonical(String name);

    /** The form a fact is stored in: canonical names, in the canonical direction of its relation. */
    Triple canonicalForm(IFact fact);

    boolean isRelation(String name);

    boolean isType(String name);

    boolean isSymmetric(String relation);

    boolean isTransitive(String relation);
}
