package it.ddot.core.cds;

import it.ddot.core.store.IFact;
import it.ddot.core.store.TripleStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static it.ddot.core.cds.Cds.*;
import static it.ddot.core.cds.CdsQuery.*;
import static com.google.common.truth.Truth.assertThat;

/**
 * What every {@link CdsReasoner} must answer, against {@code doc/cds-inf.adoc} and the CDS spec,
 * one rule at a time.
 *
 * <p>Every test states a few triples and asks questions whose answers need a rule: nothing asked
 * here was written down as asked. An engine runs these by extending this class; see
 * {@link CdsInferenceEngineTest}.
 */
abstract class CdsReasonerContract {

    protected abstract CdsReasoner create(TripleStore<?> store, RuleSet rules);

    private CdsReasoner engine() {
        return engine(RuleSet.ALL);
    }

    private CdsReasoner engine(RuleSet rules) {
        return create(new MemoryTripleStore(), rules);
    }

    // ---- the vocabulary ------------------------------------------------------------------------

    /** The hierarchy of the spec, including the inverse side that only r17 places. */
    @Test
    void theRelationHierarchyIsTheSpecs() {
        CdsReasoner cds = engine();
        assertThat(cds.isSubRelation(HAS_SUBTYPE, HAS_DETAIL)).isTrue();
        assertThat(cds.isSubRelation(HAS_SUBTYPE, LINKS_TO)).isTrue();
        assertThat(cds.isSubRelation(HAS_SUBTYPE, RELATED)).isTrue();
        assertThat(cds.isSubRelation(HAS_INSTANCE, LINKS_TO)).isTrue();
        assertThat(cds.isSubRelation(HAS_TYPE, HAS_TAG)).isTrue();
        assertThat(cds.isSubRelation(HAS_TYPE, HAS_ANNOTATION)).isTrue();
        assertThat(cds.isSubRelation(HAS_TYPE, IS_LINKED_FROM)).isTrue();
        assertThat(cds.isSubRelation(IS_PART_OF, HAS_CONTEXT)).isTrue();
        assertThat(cds.isSubRelation(SAME_AS, HAS_SIMILAR)).isTrue();
        assertThat(cds.isSubRelation(HAS_ALIAS, HAS_SIMILAR)).isTrue();

        assertThat(cds.isSubRelation(HAS_TYPE, LINKS_TO)).isFalse();
        assertThat(cds.isSubRelation(HAS_PART, HAS_SUBTYPE)).isFalse();
        assertThat(cds.isSubRelation(HAS_INVERSE, RELATED)).isFalse();
    }

    @Test
    void transitiveAndSymmetricAreTheSpecsAndInversesShareThem() {
        CdsReasoner cds = engine();
        assertThat(cds.isTransitive(HAS_PART)).isTrue();
        assertThat(cds.isTransitive(IS_PART_OF)).isTrue(); // r13
        assertThat(cds.isTransitive(HAS_PREVIOUS)).isTrue();
        assertThat(cds.isTransitive(IS_REPLACED_BY)).isTrue(); // ddot.it only
        assertThat(cds.isTransitive(HAS_DETAIL)).isFalse();
        assertThat(cds.isSymmetric(RELATED)).isTrue();
        assertThat(cds.isSymmetric(HAS_SIMILAR)).isTrue(); // r10: its own inverse
        assertThat(cds.isSymmetric(LINKS_TO)).isFalse();
    }

    // ---- canonical form ------------------------------------------------------------------------

    /** Stored on its links-to side; the inverse is never materialised. */
    @Test
    void aTripleIsStoredInTheCanonicalDirection() {
        CdsReasoner cds = engine();
        assertThat(cds.canonicalForm(new Triple("a", HAS_TYPE, "T"))).isEqualTo(new Triple("T", HAS_INSTANCE, "a"));
        assertThat(cds.canonicalForm(new Triple("b", IS_PART_OF, "a"))).isEqualTo(new Triple("a", HAS_PART, "b"));
        assertThat(cds.canonicalForm(new Triple("a", "^" + HAS_PART, "b"))).isEqualTo(new Triple("b", HAS_PART, "a"));
        // No side under links to: the name the mapping table uses.
        assertThat(cds.canonicalForm(new Triple("JavaScript", HAS_ALIAS, "JS"))).isEqualTo(new Triple("JS", IS_ALIAS_OF, "JavaScript"));
        assertThat(cds.canonicalForm(new Triple("b", IS_REPLACED_BY, "a"))).isEqualTo(new Triple("a", REPLACES, "b"));
    }

    // ---- r02, r05, r17 -------------------------------------------------------------------------

    @Test
    void aStatementHoldsForEverySuperRelationAndBackwards() {
        CdsReasoner cds = engine().add("animal", HAS_SUBTYPE, "dog");
        assertThat(cds.holds("animal", HAS_DETAIL, "dog")).isTrue();
        assertThat(cds.holds("animal", LINKS_TO, "dog")).isTrue();
        assertThat(cds.holds("animal", RELATED, "dog")).isTrue();
        assertThat(cds.holds("dog", RELATED, "animal")).isTrue();
        assertThat(cds.holds("dog", HAS_SUPERTYPE, "animal")).isTrue();
        assertThat(cds.holds("dog", HAS_CONTEXT, "animal")).isTrue();
        assertThat(cds.holds("dog", IS_LINKED_FROM, "animal")).isTrue();
        assertThat(cds.holds("dog", "^" + HAS_SUBTYPE, "animal")).isTrue();

        assertThat(cds.holds("dog", LINKS_TO, "animal")).isFalse();
        assertThat(cds.holds("animal", HAS_PART, "dog")).isFalse();
    }

    @Test
    void aUserRelationSitsWhereItWasPut() {
        CdsReasoner cds = engine()
                .add(LINKS_TO, HAS_SUBTYPE, "depends on")
                .add("depends on", HAS_INVERSE, "is needed by")
                .add("b", "is needed by", "a");
        assertThat(cds.isRelation("depends on")).isTrue();
        assertThat(cds.isType("depends on")).isFalse();
        assertThat(cds.holds("a", "depends on", "b")).isTrue();
        assertThat(cds.holds("a", LINKS_TO, "b")).isTrue();
        assertThat(cds.isSubRelation("is needed by", IS_LINKED_FROM)).isTrue(); // r17
        assertThat(cds.canonicalForm(new Triple("b", "is needed by", "a"))).isEqualTo(new Triple("a", "depends on", "b"));
    }

    /** A predicate nobody placed is still a relation, and every relation is related. */
    @Test
    void anUndeclaredPredicateLiesUnderRelated() {
        CdsReasoner cds = engine().add("a", "mentions", "b");
        assertThat(cds.isRelation("mentions")).isTrue();
        assertThat(cds.holds("a", RELATED, "b")).isTrue();
        assertThat(cds.holds("b", RELATED, "a")).isTrue();
        assertThat(cds.holds("a", LINKS_TO, "b")).isFalse();
    }

    /**
     * r17 places sub-relations as {@code has subtype} triples, not only for {@link CdsReasoner#isSubRelation}:
     * "P ⊑ Q" means {@code Q has subtype P}. The vocabulary alone entails {@code has tag has subtype has type}.
     */
    @Test
    void r17PlacesInversesAsTriples() {
        CdsReasoner cds = engine();
        assertThat(cds.holds(HAS_TAG, HAS_SUBTYPE, HAS_TYPE)).isTrue();
        assertThat(cds.holds(HAS_TYPE, HAS_SUPERTYPE, HAS_TAG)).isTrue();
        assertThat(cds.holds(IS_LINKED_FROM, HAS_SUBTYPE, HAS_TYPE)).isTrue();
        assertThat(cds.holds(RELATED, HAS_SUBTYPE, IS_LINKED_FROM)).isTrue();
        assertThat(cds.holds(IS_LINKED_FROM, HAS_SUBTYPE, HAS_INSTANCE)).isFalse();

        cds.add(LINKS_TO, HAS_SUBTYPE, "depends on").add("depends on", HAS_INVERSE, "is needed by");
        assertThat(cds.holds(IS_LINKED_FROM, HAS_SUBTYPE, "is needed by")).isTrue();
        assertThat(cds.match(IS_LINKED_FROM, HAS_SUBTYPE, null)).contains(new Triple(IS_LINKED_FROM, HAS_SUBTYPE, "is needed by"));
        assertThat(cds.match(null, HAS_SUBTYPE, "is needed by")).contains(new Triple(IS_LINKED_FROM, HAS_SUBTYPE, "is needed by"));
        Set<IFact> why = cds.why(IS_LINKED_FROM, HAS_SUBTYPE, "is needed by").orElseThrow();
        assertThat(why).contains(new Triple(LINKS_TO, HAS_SUBTYPE, "depends on"));
    }

    /** Without chains, the hierarchy's triples are its single steps: r17 is no chain, but r01 is. */
    @Test
    void withoutChainsR17PlacesOneStep() {
        CdsReasoner cds = engine(RuleSet.ALL_EXCEPT_TRANSITIVE);
        assertThat(cds.holds(HAS_TAG, HAS_SUBTYPE, HAS_TYPE)).isTrue();
        assertThat(cds.holds(HAS_ANNOTATION, HAS_SUBTYPE, HAS_TYPE)).isFalse();
        assertThat(cds.isSubRelation(HAS_TYPE, HAS_ANNOTATION)).isTrue();
    }

    /**
     * An alias of a relation stays one way, as every alias does: it is not the same as the relation, and
     * nothing places it in the hierarchy -- it lies under {@code related}, as a relation nobody placed.
     * Read literally, the convention that every relation has the inverse {@code ^p} would hand
     * {@code a has inverse ^a} on through the alias and make it the same as the relation (r03, r12).
     */
    @Test
    void anAliasOfARelationStaysOneWay() {
        CdsReasoner cds = engine()
                .add(IS_LINKED_FROM, HAS_SUBTYPE, "r")
                .add("a", IS_ALIAS_OF, "r")
                .add("x", "a", "y");
        assertThat(cds.holds("x", "r", "y")).isTrue();
        assertThat(cds.holds("a", SAME_AS, "r")).isFalse();
        assertThat(cds.holds(IS_LINKED_FROM, HAS_SUBTYPE, "a")).isFalse();
        assertThat(cds.holds(RELATED, HAS_SUBTYPE, "a")).isTrue();
        assertThat(cds.isSubRelation("a", IS_LINKED_FROM)).isTrue(); // r07: its triples are r's
    }

    // ---- identity: r06–r08, r12, r14–r16 -------------------------------------------------------

    /**
     * r06–r08, as the spec reads them: what is said about an alias holds for what it is an alias of,
     * in every position -- and not the other way round. An alias is a name that hands its statements
     * on, not a second name of the same concept: that is {@code same as}.
     */
    @Test
    void anAliasIsRewrittenInEveryPosition() {
        CdsReasoner cds = engine()
                .add("JS", IS_ALIAS_OF, "JavaScript")
                .add("script.js", HAS_TYPE, "JS")
                .add("likes", IS_ALIAS_OF, "links to")
                .add("JS", "likes", "web")
                .add("JavaScript", HAS_PART, "closures");
        assertThat(cds.canonical("JS")).isEqualTo("JS");
        assertThat(cds.holds("script.js", HAS_TYPE, "JavaScript")).isTrue();
        assertThat(cds.holds("JavaScript", LINKS_TO, "web")).isTrue();
        assertThat(cds.holds("web", IS_LINKED_FROM, "JavaScript")).isTrue();
        assertThat(cds.why("script.js", HAS_TYPE, "JavaScript").orElseThrow())
                .containsAtLeast(new Triple("script.js", HAS_TYPE, "JS"), new Triple("JS", IS_ALIAS_OF, "JavaScript"));
        // Not back: JavaScript's statements are not JS's, nor are links' those of likes.
        assertThat(cds.holds("JS", HAS_PART, "closures")).isFalse();
        assertThat(cds.holds("JS", SAME_AS, "JavaScript")).isFalse();
        assertThat(cds.isSubRelation("likes", LINKS_TO)).isTrue();
        assertThat(cds.isSubRelation(LINKS_TO, "likes")).isFalse();
    }

    /** Two names each an alias of the other hand each other everything -- and are still two names. */
    @Test
    void anAliasCycleSharesEverything() {
        CdsReasoner cds = engine()
                .add("B", IS_ALIAS_OF, "A")
                .add("A", IS_ALIAS_OF, "B")
                .add("A", HAS_PART, "x")
                .add("B", HAS_TAG, "t");
        assertThat(cds.holds("B", HAS_PART, "x")).isTrue();
        assertThat(cds.holds("A", HAS_TAG, "t")).isTrue();
        assertThat(cds.canonical("B")).isEqualTo("B");
    }

    /** {@code same as} is reflexive: anything is the same as itself, and so whatever lies above. */
    @Test
    void anythingIsTheSameAsItself() {
        CdsReasoner cds = engine().add("a", LINKS_TO, "b");
        assertThat(cds.holds("a", SAME_AS, "a")).isTrue();
        assertThat(cds.holds("a", HAS_SIMILAR, "a")).isTrue();
        assertThat(cds.holds("a", RELATED, "a")).isTrue();
        assertThat(cds.holds("never mentioned", SAME_AS, "never mentioned")).isTrue();
        assertThat(cds.holds("a", LINKS_TO, "a")).isFalse();
        assertThat(cds.holds("a", SAME_AS, "b")).isFalse();
        assertThat(objects(cds.match("a", SAME_AS, null))).containsExactly("a");
    }

    @Test
    void sameAsIsSymmetricTransitiveAndRewrites() {
        CdsReasoner cds = engine()
                .add("a", SAME_AS, "b")
                .add("b", SAME_AS, "c")
                .add("a", HAS_PART, "wheel");
        assertThat(cds.holds("c", SAME_AS, "a")).isTrue();
        assertThat(cds.holds("c", HAS_PART, "wheel")).isTrue();
        assertThat(cds.canonical("c")).isEqualTo("a");
    }

    @Test
    void anInverseOfAnInverseIsTheSameRelation() {
        CdsReasoner cds = engine()
                .add("p", HAS_INVERSE, "q")
                .add("q", HAS_INVERSE, "r")
                .add("x", "r", "y");
        assertThat(cds.canonical("r")).isEqualTo("p");
        assertThat(cds.holds("p", SAME_AS, "r")).isTrue();
        assertThat(cds.holds("x", "p", "y")).isTrue();
        assertThat(cds.holds("y", "q", "x")).isTrue();
        // One class, two directions: an inverse is not the same relation.
        assertThat(cds.holds("p", SAME_AS, "q")).isFalse();
        assertThat(cds.holds("p", HAS_INVERSE, "q")).isTrue();
        assertThat(cds.holds("p", HAS_INVERSE, "^r")).isTrue();
    }

    /** A statement about a relation is not one about its inverse. */
    @Test
    void aStatementAboutARelationStaysWithItsDirection() {
        CdsReasoner cds = engine().add(HAS_TYPE, "is shown as", "is a");
        assertThat(cds.holds(HAS_TYPE, "is shown as", "is a")).isTrue();
        assertThat(cds.holds(HAS_INSTANCE, "is shown as", "is a")).isFalse();
    }

    /** ddot.it: a subtype cycle is one concept. */
    @Test
    void aSubtypeCycleCollapsesToSameAs() {
        CdsReasoner cds = engine().add("A", HAS_SUBTYPE, "B").add("B", HAS_SUBTYPE, "A");
        assertThat(cds.canonical("B")).isEqualTo("A");
        assertThat(cds.holds("A", SAME_AS, "B")).isTrue();
    }

    // ---- r09–r11 -------------------------------------------------------------------------------

    @Test
    void aSymmetricRelationHoldsBothWays() {
        CdsReasoner cds = engine()
                .add("knows", HAS_TYPE, SYMMETRIC_RELATION)
                .add("alice", "knows", "bob")
                .add("meets", HAS_INVERSE, "meets")
                .add("alice", "meets", "carol");
        assertThat(cds.holds("bob", "knows", "alice")).isTrue();
        assertThat(cds.holds("carol", "meets", "alice")).isTrue(); // r10
        assertThat(cds.holds("meets", HAS_TYPE, SYMMETRIC_RELATION)).isTrue();
        assertThat(cds.holds("knows", HAS_INVERSE, "knows")).isTrue(); // r11
    }

    // ---- r01 -----------------------------------------------------------------------------------

    @Test
    void aTransitiveRelationChainsAlsoThroughItsSubRelations() {
        CdsReasoner cds = engine()
                .add(HAS_PART, HAS_SUBTYPE, "has component")
                .add("car", "has component", "engine")
                .add("engine", HAS_PART, "piston");
        assertThat(cds.holds("car", HAS_PART, "piston")).isTrue();
        assertThat(cds.holds("piston", IS_PART_OF, "car")).isTrue();
        assertThat(cds.holds("car", "has component", "piston")).isFalse();
        assertThat(cds.holds("car", HAS_DETAIL, "piston")).isTrue(); // r01, then r05
        assertThat(cds.why("car", HAS_PART, "piston").orElseThrow())
                .containsAtLeast(new Triple("car", "has component", "engine"), new Triple("engine", HAS_PART, "piston"));
    }

    /**
     * Chains through shapes other than a tree: two paths to one part, and a cycle. An engine that
     * indexes chains has a fast answer for trees, and must fall back correctly for the rest.
     */
    @Test
    void chainsRunThroughDiamondsAndCycles() {
        CdsReasoner cds = engine()
                .add("car", HAS_PART, "engine")
                .add("car", HAS_PART, "chassis")
                .add("engine", HAS_PART, "bolt")
                .add("chassis", HAS_PART, "bolt")
                .add("bolt", HAS_PART, "thread")
                .add("monday", HAS_NEXT, "tuesday")
                .add("tuesday", HAS_NEXT, "wednesday")
                .add("wednesday", HAS_NEXT, "monday");
        assertThat(cds.holds("car", HAS_PART, "thread")).isTrue();
        assertThat(cds.holds("chassis", HAS_PART, "thread")).isTrue();
        assertThat(cds.holds("thread", IS_PART_OF, "car")).isTrue();
        assertThat(cds.holds("engine", HAS_PART, "chassis")).isFalse();
        assertThat(cds.holds("thread", HAS_PART, "car")).isFalse();
        assertThat(objects(cds.match("car", HAS_PART, null))).containsExactly("engine", "chassis", "bolt", "thread");
        assertThat(cds.holds("wednesday", HAS_NEXT, "tuesday")).isTrue();
        assertThat(cds.holds("monday", HAS_PREVIOUS, "tuesday")).isTrue();
        // r01 on a cycle: monday has next tuesday ... has next monday.
        assertThat(cds.holds("monday", HAS_NEXT, "monday")).isTrue();
        assertThat(cds.holds("car", HAS_PART, "car")).isFalse();
        assertThat(objects(cds.match("tuesday", HAS_NEXT, null))).containsExactly("wednesday", "monday", "tuesday");
    }

    /**
     * r01 over every triple, derived ones too: a transitive relation above {@code has type} chains
     * through the types type inheritance (r04) derives, and one above {@code has tag} through the
     * tags parts inherit (r18).
     */
    @Test
    void chainsRunThroughDerivedTriples() {
        CdsReasoner cds = engine()
                .add("is classified as", HAS_TYPE, TRANSITIVE_RELATION)
                .add("is classified as", HAS_SUBTYPE, HAS_TYPE)
                .add("rex", HAS_TYPE, "poodle")
                .add("poodle", HAS_SUPERTYPE, "dog")
                .add("dog", "is classified as", "mammal")
                .add("is labelled", HAS_TYPE, TRANSITIVE_RELATION)
                .add("is labelled", HAS_SUBTYPE, HAS_TAG)
                .add("car", HAS_TAG, "red")
                .add("car", HAS_PART, "door")
                .add("red", "is labelled", "colourful");
        assertThat(cds.holds("rex", "is classified as", "dog")).isTrue();
        assertThat(cds.holds("rex", "is classified as", "mammal")).isTrue();
        assertThat(cds.holds("mammal", "^is classified as", "rex")).isTrue();
        assertThat(cds.holds("door", "is labelled", "colourful")).isTrue();
        assertThat(objects(cds.match("rex", "is classified as", null))).containsAtLeast("poodle", "dog", "mammal");
    }

    @Test
    void withoutTransitivityNoChain() {
        CdsReasoner cds = engine(RuleSet.ALL_EXCEPT_TRANSITIVE)
                .add("car", HAS_PART, "engine")
                .add("engine", HAS_PART, "piston");
        assertThat(cds.holds("car", HAS_PART, "engine")).isTrue();
        assertThat(cds.holds("car", HAS_PART, "piston")).isFalse();
    }

    @Test
    void replacesIsTransitive() {
        CdsReasoner cds = engine().add("v3", REPLACES, "v2").add("v2", REPLACES, "v1");
        assertThat(cds.holds("v3", REPLACES, "v1")).isTrue();
        assertThat(cds.holds("v1", IS_REPLACED_BY, "v3")).isTrue();
    }

    // ---- r04 and typing creates types ----------------------------------------------------------

    /** r04 chains even without r01: it applies again to its own conclusion. */
    @Test
    void aThingHasEveryTypeAboveItsType() {
        CdsReasoner cds = engine(RuleSet.ALL_EXCEPT_TRANSITIVE)
                .add("animal", HAS_SUBTYPE, "dog")
                .add("dog", HAS_SUBTYPE, "poodle")
                .add("rex", HAS_TYPE, "poodle");
        assertThat(cds.holds("rex", HAS_TYPE, "animal")).isTrue();
        assertThat(cds.holds("animal", HAS_INSTANCE, "rex")).isTrue();
        assertThat(cds.holds("rex", HAS_TAG, "animal")).isTrue();
        assertThat(cds.why("rex", HAS_TYPE, "animal").orElseThrow()).containsAtLeast(
                new Triple("rex", HAS_TYPE, "poodle"),
                new Triple("dog", HAS_SUBTYPE, "poodle"),
                new Triple("animal", HAS_SUBTYPE, "dog"));
    }

    @Test
    void typingAndSubtypingCreateTypes() {
        CdsReasoner cds = engine()
                .add("aaa", HAS_TYPE, "bbb")
                .add("animal", HAS_SUBTYPE, "dog");
        assertThat(cds.holds("bbb", HAS_TYPE, TYPE)).isTrue();
        assertThat(cds.holds("animal", HAS_TYPE, TYPE)).isTrue();
        assertThat(cds.holds("dog", HAS_TYPE, TYPE)).isTrue();
        assertThat(cds.holds(TYPE, HAS_INSTANCE, "bbb")).isTrue();
        assertThat(cds.holds(TYPE, HAS_TYPE, TYPE)).isTrue();
        assertThat(cds.holds("aaa", HAS_TYPE, TYPE)).isFalse();
        assertThat(cds.holds("bbb", HAS_TAG, TYPE)).isTrue();
    }

    /** A subtype edge between relations makes relations, not types. */
    @Test
    void relationsGetATypeOfTheirOwn() {
        CdsReasoner cds = engine().add(LINKS_TO, HAS_SUBTYPE, "depends on");
        assertThat(cds.holds("depends on", HAS_TYPE, RELATION)).isTrue();
        assertThat(cds.holds("depends on", HAS_TYPE, TYPE)).isFalse();
        assertThat(cds.holds(LINKS_TO, HAS_TYPE, TYPE)).isFalse();
        assertThat(cds.holds(RELATION, HAS_TYPE, TYPE)).isTrue();
        assertThat(cds.holds(HAS_NEXT, HAS_TYPE, RELATION)).isTrue();
    }

    // ---- r18 -----------------------------------------------------------------------------------

    @Test
    void tagsPropagateToPartsAtAnyDepth() {
        CdsReasoner cds = engine(RuleSet.ALL_EXCEPT_TRANSITIVE)
                .add("car", HAS_TAG, "red")
                .add("car", HAS_TYPE, "vehicle")
                .add("car", HAS_PART, "door")
                .add("door", HAS_PART, "handle");
        assertThat(cds.holds("handle", HAS_TAG, "red")).isTrue();
        assertThat(cds.holds("red", IS_TAG_OF, "handle")).isTrue();
        // A type of the whole is a tag of each part, not a type of it.
        assertThat(cds.holds("handle", HAS_TAG, "vehicle")).isTrue();
        assertThat(cds.holds("handle", HAS_TYPE, "vehicle")).isFalse();
    }

    // ---- meta fixpoint -------------------------------------------------------------------------

    /** A relation found to be a meta relation makes its triples meta triples. */
    @Test
    void aUserMetaRelationIsFoundInTheFixpoint() {
        CdsReasoner cds = engine()
                .add(HAS_SUBTYPE, HAS_SUBTYPE, "has kind")
                .add("animal", "has kind", "dog")
                .add("rex", HAS_TYPE, "dog")
                .add(IS_ALIAS_OF, HAS_SUBTYPE, "stands for")
                .add("hound", "stands for", "dog");
        assertThat(cds.isSubRelation("has kind", HAS_SUBTYPE)).isTrue();
        assertThat(cds.holds("rex", HAS_TYPE, "animal")).isTrue();
        // stands for is an alias relation: what is said about the hound is said about the dog.
        CdsReasoner more = cds.add("fido", HAS_TYPE, "hound");
        assertThat(more.holds("fido", HAS_TYPE, "dog")).isTrue();
        assertThat(more.holds("fido", HAS_TYPE, "animal")).isTrue();
        assertThat(more.holds("rex", HAS_TYPE, "hound")).isFalse();
    }

    // ---- rule sets and change ------------------------------------------------------------------

    @Test
    void noneAnswersOnlyWhatWasWritten() {
        CdsReasoner cds = engine(RuleSet.NONE)
                .add("animal", HAS_SUBTYPE, "dog");
        assertThat(cds.holds("animal", HAS_SUBTYPE, "dog")).isTrue();
        assertThat(cds.holds("dog", HAS_SUPERTYPE, "animal")).isFalse();
        assertThat(cds.holds("animal", RELATED, "dog")).isFalse();
    }

    @Test
    void onlyTransitiveChainsTheNamesAsWritten() {
        CdsReasoner cds = engine(RuleSet.ONLY_TRANSITIVE)
                .add("a", HAS_NEXT, "b")
                .add("b", HAS_NEXT, "c");
        assertThat(cds.holds("a", HAS_NEXT, "c")).isTrue();
        assertThat(cds.holds("c", HAS_PREVIOUS, "a")).isFalse();
    }

    /** A conclusion goes when its last support goes, and the engine notices without being told. */
    @Test
    void removingASupportRemovesWhatFollowedFromIt() {
        MemoryTripleStore store = new MemoryTripleStore();
        CdsReasoner cds = create(store, RuleSet.ALL);
        Triple alias = new Triple("JS", IS_ALIAS_OF, "JavaScript");
        store.add("script.js", HAS_TYPE, "JS");
        store.add(alias);
        assertThat(cds.holds("script.js", HAS_TYPE, "JavaScript")).isTrue();
        store.remove(alias);
        assertThat(cds.holds("script.js", HAS_TYPE, "JavaScript")).isFalse();
    }

    // ---- stores --------------------------------------------------------------------------------

    @Test
    void theMemoryStoreFindsByPattern() {
        MemoryTripleStore store = new MemoryTripleStore();
        store.add("a", HAS_PART, "b");
        store.add("a", HAS_TAG, "red");
        store.add("c", HAS_PART, "b");
        assertThat(store.find("a", null, null)).hasSize(2);
        assertThat(store.find(null, HAS_PART, "b")).containsExactly(new Triple("a", HAS_PART, "b"), new Triple("c", HAS_PART, "b"));
        assertThat(store.find(null, null, null)).hasSize(3);
        assertThat(store.add("a", HAS_PART, "b")).isFalse();
    }

    // ---- patterns ------------------------------------------------------------------------------

    private CdsReasoner dogs() {
        return engine()
                .add("animal", HAS_SUBTYPE, "dog")
                .add("rex", HAS_TYPE, "dog")
                .add("rex", HAS_PART, "tail")
                .add("rex", HAS_TAG, "good");
    }

    @Test
    void aPatternMatchesWhatIsEntailed() {
        CdsReasoner cds = dogs();
        assertThat(objects(cds.match("rex", HAS_TYPE, null))).containsExactly("dog", "animal");
        assertThat(subjects(cds.match(null, HAS_TYPE, "animal"))).containsExactly("rex");
        // The vocabulary's own items are types too.
        assertThat(subjects(cds.match(null, HAS_TYPE, TYPE)))
                .containsExactly("animal", "dog", TYPE, RELATION, SYMMETRIC_RELATION, TRANSITIVE_RELATION);
        assertThat(objects(cds.match("rex", HAS_TAG, null))).containsExactly("dog", "animal", "good");
    }

    /**
     * A relation read backwards appears in no stored triple, but is typed all the same. Found on the
     * adir graph, where {@code relation} is a kind of {@code thing}: the oracle's {@code match} left
     * out every {@code ^r}, although its {@code holds} said yes to each.
     */
    @Test
    void aRelationReadBackwardsIsMatchedToo() {
        CdsReasoner cds = engine().add("a", "mentions", "b").add("thing", HAS_SUBTYPE, RELATION);
        assertThat(cds.holds("^mentions", HAS_TYPE, "thing")).isTrue();
        assertThat(subjects(cds.match(null, HAS_TYPE, "thing"))).containsAtLeast("mentions", "^mentions");
        // Only a type's own name is a type: its name read backwards means nothing.
        assertThat(cds.holds("^thing", HAS_TYPE, TYPE)).isFalse();
    }

    /**
     * A relation nobody stated is stored the way it was named, not read backwards from its
     * {@code ^} spelling. Found by the growing-store test: the oracle stored {@code a ^r b} for a
     * relation {@code r} it had not compiled as {@code a ^r b}, and {@code b r a} once it had.
     */
    @Test
    void aRelationNobodyStatedKeepsItsOwnName() {
        CdsReasoner cds = engine().add("a", "knows", "b");
        assertThat(cds.canonicalForm(new Triple("x", "^unheard of", "y"))).isEqualTo(new Triple("y", "unheard of", "x"));
    }

    /**
     * Facts that arrive after the first question, as they do while adir reads a tree. An engine that
     * appends instead of compiling again must still see what a new fact changes: {@code ^has
     * supertype} is {@code has subtype} in another spelling, and a relation never seen before is a
     * relation, under {@code related}.
     */
    @Test
    void factsArrivingLaterCountAsMuchAsTheFirst() {
        MemoryTripleStore store = new MemoryTripleStore();
        CdsReasoner cds = create(store, RuleSet.ALL);
        store.add("rex", HAS_TYPE, "dog");
        assertThat(cds.holds("rex", HAS_TYPE, "dog")).isTrue();

        store.add("animal", "^" + HAS_SUPERTYPE, "dog");
        assertThat(cds.holds("rex", HAS_TYPE, "animal")).isTrue();

        store.add("mammal", "^" + HAS_TYPE, "rex");
        assertThat(cds.holds("rex", HAS_TYPE, "mammal")).isTrue();
        assertThat(cds.isType("mammal")).isTrue();

        store.add("rex", "chases", "cat");
        assertThat(cds.isRelation("chases")).isTrue();
        assertThat(cds.holds("cat", RELATED, "rex")).isTrue();
        assertThat(cds.holds("rex", LINKS_TO, "cat")).isFalse();

        store.add(LINKS_TO, HAS_SUBTYPE, "chases");
        assertThat(cds.holds("rex", LINKS_TO, "cat")).isTrue();

        store.add("pooch", IS_ALIAS_OF, "dog");
        assertThat(cds.holds("rex", HAS_TYPE, "pooch")).isFalse();
        store.add("puppy", HAS_TYPE, "pooch");
        assertThat(cds.holds("puppy", HAS_TYPE, "dog")).isTrue();
        assertThat(cds.holds("puppy", HAS_TYPE, "animal")).isTrue();

        store.add("dog", HAS_SUBTYPE, "terrier");
        store.add("rover", HAS_TYPE, "terrier");
        assertThat(cds.holds("rover", HAS_TYPE, "animal")).isTrue();
        assertThat(cds.holds("rover", HAS_TYPE, "pooch")).isFalse();

        store.add("barks at", HAS_TYPE, RELATION);
        assertThat(cds.isRelation("barks at")).isTrue();
        assertThat(cds.holds("barks at", HAS_TYPE, RELATION)).isTrue();

        // An alias between two names both already known: a file, and the type it declares.
        store.add("Foo.java", "has size", "3");
        store.add("com.Foo", "declared in", "pkg");
        store.add("Foo.java", IS_ALIAS_OF, "com.Foo");
        assertThat(cds.holds("com.Foo", "has size", "3")).isTrue();
        assertThat(cds.holds("3", RELATED, "com.Foo")).isTrue();
        assertThat(cds.holds("Foo.java", "declared in", "pkg")).isFalse();
        // And two names made the same, both already known.
        store.add("Bar.java", "has size", "5");
        store.add("com.Bar", "declared in", "pkg");
        store.add("Bar.java", SAME_AS, "com.Bar");
        assertThat(cds.holds("com.Bar", "has size", "5")).isTrue();
        assertThat(cds.holds("Bar.java", "declared in", "pkg")).isTrue();

        // An alias of a name an earlier identity statement named: r08 makes that statement about
        // what it aliases too, so a same as b, b alias of c makes a same as c.
        store.add("Baz.java", SAME_AS, "Baz");
        assertThat(cds.holds("Baz.java", SAME_AS, "Baz")).isTrue();
        store.add("Baz", IS_ALIAS_OF, "com.Baz");
        assertThat(cds.holds("Baz.java", SAME_AS, "com.Baz")).isTrue();

        // A second parent, for a type with things below it.
        store.add("pet", HAS_SUBTYPE, "dog");
        assertThat(cds.holds("rover", HAS_TYPE, "pet")).isTrue();
        assertThat(cds.holds("terrier", HAS_SUPERTYPE, "pet")).isTrue();
        assertThat(cds.holds("rex", HAS_TYPE, "pet")).isTrue();
    }

    /**
     * A later fact can change what an earlier one means: make its predicate a meta relation, its
     * type a kind of relation, its relation its own inverse, its two ends one concept. A model that
     * grows instead of being built anew must re-read the earlier fact each time. Each step here
     * failed to when the re-reading was taken out.
     */
    @Test
    void aLaterFactChangesWhatAnEarlierOneMeans() {
        MemoryTripleStore store = new MemoryTripleStore();
        CdsReasoner cds = create(store, RuleSet.ALL);

        // A data predicate made a meta relation: its triples are subtype statements from now on.
        store.add("animal", "has kind", "dog");
        store.add("rex", HAS_TYPE, "dog");
        assertThat(cds.holds("rex", HAS_TYPE, "animal")).isFalse();
        store.add(HAS_SUBTYPE, HAS_SUBTYPE, "has kind");
        assertThat(cds.holds("rex", HAS_TYPE, "animal")).isTrue();

        // A data predicate made a typing: what it types with is a type.
        store.add("tom", "is a", "cat");
        assertThat(cds.isType("cat")).isFalse();
        store.add(HAS_TYPE, HAS_SUBTYPE, "is a");
        assertThat(cds.isType("cat")).isTrue();

        // A type made a kind of relation: what was typed with it is a relation of that kind.
        store.add("precedes", HAS_TYPE, "ordering");
        store.add("a", "precedes", "b");
        store.add("b", "precedes", "c");
        assertThat(cds.holds("a", "precedes", "c")).isFalse();
        store.add(TRANSITIVE_RELATION, HAS_SUBTYPE, "ordering");
        assertThat(cds.isTransitive("precedes")).isTrue();
        assertThat(cds.holds("a", "precedes", "c")).isTrue();

        // A relation made its own inverse: what was said about its other direction is said about it.
        store.add("knows", HAS_INVERSE, "is known by");
        store.add("is known by", HAS_DETAIL, "social");
        assertThat(cds.holds("knows", HAS_DETAIL, "social")).isFalse();
        store.add("knows", HAS_TYPE, SYMMETRIC_RELATION);
        assertThat(cds.holds("knows", HAS_DETAIL, "social")).isTrue();

        // Two known names made one: the one concept goes by the smaller name.
        store.add("zeta", "has size", "3");
        store.add("alpha", "has size", "4");
        assertThat(cds.canonical("zeta")).isEqualTo("zeta");
        store.add("zeta", SAME_AS, "alpha");
        assertThat(cds.canonical("zeta")).isEqualTo("alpha");
        assertThat(cds.match("zeta", "has size", null)).contains(new Triple("alpha", "has size", "3"));
    }

    @Test
    void aVariableRelationMatchesEveryRelationThatHolds() {
        List<String> relations = cds_relations(dogs().match("rex", null, "dog"));
        assertThat(relations).containsAtLeast(HAS_TYPE, HAS_TAG, HAS_ANNOTATION, IS_LINKED_FROM, RELATED);
        assertThat(relations).doesNotContain(LINKS_TO);
        assertThat(relations).doesNotContain(HAS_INSTANCE);
    }

    @Test
    void everyMatchHoldsAndNamesAreCanonical() {
        CdsReasoner cds = dogs().add("pup", IS_ALIAS_OF, "dog");
        for (Triple triple : cds.match("rex", null, null)) {
            assertThat(cds.holds(triple.subject(), triple.relation(), triple.object())).isTrue();
        }
        assertThat(objects(cds.match("rex", HAS_TYPE, null))).doesNotContain("pup");
    }

    // ---- queries -------------------------------------------------------------------------------

    @Test
    void aQueryJoinsPatterns() {
        CdsReasoner cds = dogs().add("felix", HAS_TYPE, "cat").add("felix", HAS_PART, "whiskers");
        List<Map<String, String>> found = new CdsQueryEngine(cds).select(where(
                t("?x", HAS_TYPE, "animal"),
                t("?x", HAS_PART, "?part")));
        assertThat(found).containsExactly(Map.of("x", "rex", "part", "tail"));
    }

    @Test
    void aConstructQueryBuildsTriples() {
        // Everything typed: rex, and every relation and type the vocabulary types -- so only what has parts.
        Set<Triple> built = new CdsQueryEngine(dogs()).construct(where(t("?x", HAS_TYPE, "?t"), t("?x", HAS_PART, "?part"))
                .construct(tpl("?x", "is a", "?t")));
        assertThat(built).containsExactly(new Triple("rex", "is a", "dog"), new Triple("rex", "is a", "animal"));
    }

    @Test
    void aPropertyChainFollowsItsSteps() {
        CdsReasoner cds = engine()
                .add("alice", "works for", "acme")
                .add("acme", "located in", "berlin")
                .add("bob", "works for", "initech");
        CdsQueryEngine query = new CdsQueryEngine(cds);
        assertThat(query.select(where(t("?who", seq(rel("works for"), rel("located in")), "berlin"))))
                .containsExactly(Map.of("who", "alice"));
        assertThat(query.select(where(t("alice", seq(rel("works for"), rel("located in")), "?city"))))
                .containsExactly(Map.of("city", "berlin"));
        assertThat(query.select(where(t("?city", seq(inv(rel("located in")), inv(rel("works for"))), "?who"))))
                .containsExactly(Map.of("city", "berlin", "who", "alice"));
    }

    @Test
    void oneOrMoreStepsFollowARelationThatIsNotTransitive() {
        CdsReasoner cds = engine().add("a", "follows", "b").add("b", "follows", "c").add("c", "follows", "d");
        CdsQueryEngine query = new CdsQueryEngine(cds);
        assertThat(cds.holds("a", "follows", "c")).isFalse();
        assertThat(query.select(where(t("a", plus(rel("follows")), "?x"))))
                .containsExactly(Map.of("x", "b"), Map.of("x", "c"), Map.of("x", "d"));
        assertThat(query.select(where(t("?x", plus(rel("follows")), "b")))).containsExactly(Map.of("x", "a"));
        assertThat(query.select(where(t("c", star(rel("follows")), "?x"))))
                .containsExactly(Map.of("x", "c"), Map.of("x", "d"));
        assertThat(query.select(where(t("?x", plus(rel("follows")), "?y")))).hasSize(6);
    }

    /**
     * {@code p+} over a relation whose chains the reasoner already follows is {@code p}; the query
     * layer then asks once instead of once per step. The answers must not change, also where a
     * chain steps along a derived triple.
     */
    @Test
    void oneOrMoreStepsOfATransitiveRelationAreTheRelation() {
        CdsReasoner cds = engine()
                .add("car", HAS_PART, "engine")
                .add("engine", HAS_PART, "bolt")
                .add("is classified as", HAS_TYPE, TRANSITIVE_RELATION)
                .add("is classified as", HAS_SUBTYPE, HAS_TYPE)
                .add("rex", HAS_TYPE, "poodle")
                .add("poodle", HAS_SUPERTYPE, "dog")
                .add("dog", "is classified as", "mammal");
        CdsQueryEngine query = new CdsQueryEngine(cds);
        assertThat(query.select(where(t("car", plus(rel(HAS_PART)), "?x")))).containsExactly(Map.of("x", "engine"), Map.of("x", "bolt"));
        assertThat(query.select(where(t("?x", plus(rel(HAS_PART)), "bolt")))).containsExactly(Map.of("x", "car"), Map.of("x", "engine"));
        assertThat(query.select(where(t("bolt", plus(inv(rel(HAS_PART))), "?x")))).containsExactly(Map.of("x", "engine"), Map.of("x", "car"));
        assertThat(query.select(where(t("car", star(rel(HAS_PART)), "?x"))))
                .containsExactly(Map.of("x", "car"), Map.of("x", "engine"), Map.of("x", "bolt"));
        assertThat(query.select(where(t("rex", plus(rel("is classified as")), "mammal")))).hasSize(1);
    }

    @Test
    void aLimitKeepsTheFirstSolutions() {
        CdsReasoner cds = engine().add("a", "follows", "b").add("b", "follows", "c").add("c", "follows", "d");
        CdsQueryEngine query = new CdsQueryEngine(cds);
        assertThat(query.select(where(t("?x", plus(rel("follows")), "?y")).limit(4))).hasSize(4);
        assertThat(query.select(where(t("a", "follows", "?y"), t("?y", plus(rel("follows")), "?z")).limit(1))).hasSize(1);
        assertThat(query.select(where(t("a", "follows", "?y")).limit(10))).hasSize(1);
    }

    @Test
    void alternativesAndVariableRelationsJoin() {
        CdsReasoner cds = dogs();
        CdsQueryEngine query = new CdsQueryEngine(cds);
        assertThat(query.select(where(t("rex", alt(rel(HAS_PART), rel(HAS_TAG)), "?x"))))
                .containsExactly(Map.of("x", "tail"), Map.of("x", "dog"), Map.of("x", "animal"), Map.of("x", "good"));
        List<Map<String, String>> how = query.select(where(t("rex", "?how", "tail"), t("?how", HAS_TYPE, TRANSITIVE_RELATION)));
        assertThat(how).containsExactly(Map.of("how", HAS_PART));
    }

    private static List<String> subjects(List<Triple> triples) {
        return triples.stream().map(Triple::subject).distinct().toList();
    }

    private static List<String> objects(List<Triple> triples) {
        return triples.stream().map(Triple::object).distinct().toList();
    }

    private static List<String> cds_relations(List<Triple> triples) {
        return triples.stream().map(Triple::relation).distinct().toList();
    }
}
