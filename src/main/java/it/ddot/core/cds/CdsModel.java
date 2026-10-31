package it.ddot.core.cds;

import it.ddot.core.store.IFact;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static it.ddot.core.cds.Cds.*;

/**
 * What a store's facts say, for {@link CdsEngine}: worked out as the facts arrive, answering any
 * number of questions at once, and grown with the store -- never built anew while it only grows.
 *
 * <p>The design is {@code doc/cds-inf.adoc}; how each part came about, and what it was measured
 * to cost, is {@code doc/cds-inf-perf.adoc}. It answers as {@link CdsInferenceEngine}, the oracle,
 * does -- the tests hold it to that.
 *
 * <h2>Growing</h2>
 * A model starts from nothing, and {@link #grow} takes batches: the first is the axioms and the
 * store's facts, each later one what the store gained since. A batch's facts wait while the meta
 * level settles -- identity, inverses, the hierarchy, which relations are transitive and symmetric
 * -- from the batch's meta statements and whatever earlier fact a change makes say more. What the
 * change moved is mended in place, and the waiting facts are normalised -- canonical names,
 * canonical direction -- and stored. The hierarchy is closed once per strongly connected
 * component, children first, into one ancestor bitset per node; a subtype cycle is one component,
 * and collapses to one concept. Nothing a rule would derive is stored: a question asks the bitsets
 * whether a stored relation lies under the one asked for.
 *
 * <h2>Oriented relations</h2>
 * A node is an {@code int}: its {@link CdsIdentity} class times two, plus 1 when it reads the class
 * backwards. {@code has type} and {@code has instance} are one class at the two parities; a
 * symmetric relation only ever has parity 0. The hierarchy is over these, so {@code has type} and
 * {@code has instance} each have their own place in it, as r17 requires.
 *
 * <h2>Storage</h2>
 * The stored facts are four arrays -- subject, relation, object, source -- and each node has an
 * array of the indexes of its facts, mostly sorted by the other end, so that the facts between two
 * nodes are a binary search in the shorter list. A record of a stored fact is made only
 * for one a question keeps. The literal indexes exist only for the rule sets that read them.
 *
 * <h2>Answering</h2>
 * {@link #holds} and {@link #match} derive without building proofs; {@link #why} builds the whole
 * proof. {@link #match} enumerates from the bound end -- stored facts, type ancestors and
 * descendants, the tags of wholes, chains -- instead of testing candidates. A chain along a
 * transitive relation is answered by a {@link Reach} index, built on first use; a proof still walks,
 * since an index says that a chain exists, not which.
 *
 * <h2>Threads</h2>
 * Questions only read, apart from what is safe to change concurrently: {@link CdsIdentity}
 * registers a name nobody used, and the caches filled on first use are concurrent maps. Growing
 * writes, and {@link CdsEngine} lets it only while no question reads.
 */
final class CdsModel {

    /** What a triple does at the meta level, by what its predicate lies under. */
    private enum Kind {SAME, ALIAS, HAS_ALIAS, INVERSE, SUBTYPE, SUPERTYPE, TYPE, INSTANCE}

    private record NameEdge(String sub, String sup, IFact why) {}

    /**
     * One step up the hierarchy. {@code subtype}: whether the step is a {@code has subtype} triple -- a
     * stated one, one r17 derives, or the root above a relation nobody placed. An alias is not: it lies
     * under what it aliases, since r07 hands its triples on, but nothing makes that a triple.
     */
    private record Up(int to, List<IFact> why, boolean subtype) {}

    /** A triple in canonical form, with the triple it was read from. */
    private record Stored(int subject, int relation, int object, IFact source) {}

    /** One step along a stored triple, in whichever direction satisfied the relation. */
    private record Hop(int from, int to, Stored stored, int relation, List<IFact> aliases) {}

    /** {@code alias is alias of resolved}, as said by {@code why}. */
    private record AliasEdge(String alias, String resolved, IFact why) {}

    private record Member(String name, int parity) {}

    private final RuleSet rules;
    private final CdsIdentity identity = new CdsIdentity();

    /** Every alias statement, by name: what is said about the alias holds for what it is an alias of. */
    private final Set<AliasEdge> aliasEdges = new LinkedHashSet<>();
    private final Map<String, Set<String>> aliasTargets = new HashMap<>();
    /** The same, backwards: for each name, the names that are an alias of it. */
    private final Map<String, List<String>> aliasSources = new HashMap<>();
    /** The same between nodes, both ways, with the statements that make them. */
    private final Map<Integer, List<Up>> aliasUpEdges = new HashMap<>();
    private final Map<Integer, List<Up>> aliasDownEdges = new HashMap<>();
    private final Set<String> preferredNames = new HashSet<>();
    private final Set<String> relationNames = new HashSet<>();
    private final Set<String> transitiveNames = new HashSet<>();
    private final List<NameEdge> subtypeEdges = new ArrayList<>();
    /** The pairs {@link #subtypeEdges} holds, one edge each. */
    private final Set<String> subtypePairs = new HashSet<>();

    private int[] indexOf = new int[0];
    private final List<Integer> nodes = new ArrayList<>();
    private final List<List<Up>> ups = new ArrayList<>();
    private BitSet[] above = new BitSet[0];

    private Set<Integer> relationRoots = Set.of();
    private Set<Integer> transitiveRoots = Set.of();
    private final Set<Integer> typeRoots = new HashSet<>();
    private final Map<Integer, List<Member>> members = new HashMap<>();
    private final Map<Integer, Integer> canonicalParity = new java.util.concurrent.ConcurrentHashMap<>();

    /** Every stated triple, in canonical form, by the order the store gave them. */
    private int[] edgeSubject = new int[1024];
    private int[] edgeRelation = new int[1024];
    private int[] edgeObject = new int[1024];
    private IFact[] edgeSource = new IFact[1024];
    private int edgeCount;
    /** For each predicate as written, the triples that have it: what a change of its meaning re-reads. */
    private final Map<String, IntList> bySpelling = new HashMap<>();
    /** For each predicate as written, how its triples were stored: see {@link #storedAs}. */
    private final Map<String, Long> spellingState = new HashMap<>();
    /** For each node, the indexes of the triples it is an end of. */
    private int[][] adjacency = new int[1024][];
    private int[] degree = new int[1024];
    /**
     * How much of each node's list is sorted by the other end. Growing adds to an unsorted tail,
     * searched linearly, and sorts the list again once the tail is long; joining two concepts
     * leaves the lists it moved triples between unsorted until then.
     */
    private int[] sorted = new int[1024];
    /** The nodes whose lists joining left unsorted, for the end of the batch to sort. */
    private final BitSet unsorted = new BitSet();
    private final Map<Triple, IFact> literal = new HashMap<>();
    private final Map<String, List<IFact>> literalBySubject = new HashMap<>();

    /** A growable list of ints. */
    private static final class IntList {
        int[] items = new int[4];
        int size;

        void add(int item) {
            if (size == items.length) items = Arrays.copyOf(items, size * 2);
            items[size++] = item;
        }
    }

    /** A model of the vocabulary's axioms and {@code all}, grown from nothing. */
    CdsModel(RuleSet rules, List<IFact> all) {
        this.rules = rules;
        for (Pair pair : PAIRS) preferredNames.add(pair.relation());
        grow(all);
        trim();
    }

    // ---- growing -------------------------------------------------------------------------------

    /**
     * Takes facts a growing store gained: every fact, of any kind, at any time -- a model is never
     * compiled anew, the first batch is the axioms and the store's facts, and each later one is
     * what the store gained since.
     *
     * <p>A batch is read in three steps. Its facts are registered first, and wait unstored, since how
     * a fact is stored depends on the meta level, which the batch may change. Then the meta level
     * settles: the batch's meta statements are applied, and any earlier fact a change makes a meta
     * statement, or a meta statement saying more than it did. Then whatever the meta level changed
     * is repaired in place -- triples of two concepts made one move to one node, triples of a
     * relation whose meaning or direction changed are stored again -- and the waiting facts are
     * stored. A batch of data, the usual case, skips the middle.
     *
     * <p>Not safe against concurrent questions: the arrays grow in place. The engine serialises.
     */
    void grow(List<? extends IFact> facts) {
        identity.writing(true);
        try {
            int first = edgeCount;
            Set<String> newPredicates = new LinkedHashSet<>();
            for (IFact fact : facts) {
                if (!rules.normalise) {
                    // Only the rule sets that answer from the names as written read these.
                    literal.putIfAbsent(Triple.of(fact), fact);
                    literalBySubject.computeIfAbsent(fact.subject(), k -> new ArrayList<>()).add(fact);
                }
                int e = register(fact);
                bySpelling.computeIfAbsent(fact.relation(), k -> new IntList()).add(e);
                if (knownPredicates.add(fact.relation())) newPredicates.add(fact.relation());
            }
            boolean changed = settle(first, newPredicates);
            if (changed) repair(first);
            for (int e = first; e < edgeCount; e++) {
                index(e);
                groups.remove(edgeSubject[e]);
                groups.remove(edgeObject[e]);
            }
            if (changed) computeTypeRoots();
            sortLists(first);
        } finally {
            identity.writing(false);
        }
    }

    /** A fact, kept to be stored once the meta level has settled. */
    private int register(IFact fact) {
        if (edgeCount == edgeSubject.length) {
            int size = Math.max(16, edgeCount * 2);
            edgeSubject = Arrays.copyOf(edgeSubject, size);
            edgeRelation = Arrays.copyOf(edgeRelation, size);
            edgeObject = Arrays.copyOf(edgeObject, size);
            edgeSource = Arrays.copyOf(edgeSource, size);
        }
        int e = edgeCount++;
        edgeSource[e] = fact;
        edgeSubject[e] = edgeRelation[e] = edgeObject[e] = -1;
        return e;
    }

    // ---- meta level ----------------------------------------------------------------------------

    /** The predicates known, and what each does at the meta level, its alias variants included. */
    private final Set<String> knownPredicates = new LinkedHashSet<>();
    private final Map<String, EnumSet<Kind>> metaKinds = new HashMap<>();
    /** What each name does at the meta level by itself, for the hierarchy as it stands. */
    private final Map<String, EnumSet<Kind>> kindCache = new HashMap<>();
    /** The names given an alias target in this round of {@link #settle}. */
    private final Set<String> newAliases = new LinkedHashSet<>();

    /**
     * Settles the meta level after a batch: what the relations are -- identity, inverses, the
     * hierarchy, which are transitive and symmetric.
     *
     * <p>Round by round, as a worklist. A round applies its meta statements, and when that changed
     * anything, rebuilds the hierarchy and collects the next round's statements: every triple of a
     * predicate whose meta kinds grew, the meta statements about a name that gained an alias
     * target -- the stored ones by name, the batch's all again --, and the typings of anything that
     * lies under {@code relation}. All the sets only grow, so it ends; cycles are harmless. The
     * hierarchy is closed once per strongly connected component, children first, into one ancestor
     * bitset per node; a subtype cycle is one component, and collapses to one concept.
     *
     * @return whether the meta level changed, and what is stored must be repaired
     */
    private boolean settle(int first, Set<String> newPredicates) {
        // A new predicate is a new relation, under related.
        boolean changed = !newPredicates.isEmpty();
        boolean everChanged = false;
        BitSet work = new BitSet();
        batchWork(first, work, true);
        while (true) {
            for (int e = work.nextSetBit(0); e >= 0; e = work.nextSetBit(e + 1)) changed |= applyMeta(edgeSource[e]);
            if (!changed) break;
            everChanged = true;
            Set<String> predicates = new LinkedHashSet<>(knownPredicates);
            for (String predicate : knownPredicates) predicates.addAll(aliasUp(predicate));
            kindCache.clear();
            changed = buildHierarchy(predicates);
            moveJoined();
            work = nextWork(first);
        }
        return everChanged;
    }

    /** What the next round of {@link #settle} applies; see there. */
    private BitSet nextWork(int first) {
        BitSet work = new BitSet();
        // What else a statement says it says only of a new alias variant of a name.
        batchWork(first, work, !newAliases.isEmpty());
        for (String predicate : knownPredicates) {
            EnumSet<Kind> kinds = kindsOf(predicate);
            if (kinds.equals(metaKinds.getOrDefault(predicate, EnumSet.noneOf(Kind.class)))) continue;
            metaKinds.put(predicate, kinds);
            IntList list = bySpelling.get(predicate);
            for (int i = 0; i < list.size; i++) work.set(list.items[i]);
        }
        // A statement about a name says it of what the name is an alias of too (r06–r08): every
        // stored meta statement about a name whose variants grew, at any depth of aliases.
        Set<String> named = new LinkedHashSet<>();
        for (String alias : newAliases) named.addAll(aliasDown(alias));
        newAliases.clear();
        for (String name : named) {
            IntList list = bySpelling.get(name);
            if (list != null) for (int i = 0; i < list.size; i++) work.set(list.items[i]);
            storedMetaAt(name, first, work, false);
        }
        // A typing makes a relation when its type lies under relation, which the hierarchy may only
        // now say.
        int relation = oriented(RELATION);
        for (int i = 0; i < nodes.size(); i++) {
            int type = nodes.get(i);
            if (!isSub(type, relation)) continue;
            for (Member member : membersOf(type >> 1)) {
                for (String name : aliasDown(member.name())) storedMetaAt(name, first, work, true);
            }
        }
        return work;
    }

    /**
     * The batch's meta statements that can change the meta level as it stands. A typing changes it
     * only when its type lies under {@code relation} -- and on adir nine of ten meta statements are
     * typings of files and classes, which applying in every round of every batch was most of
     * building a model. By predicate, since a batch can be the whole store.
     *
     * @param all whether the statements other than typings too: after a round that found no new
     *     alias, applying them again could only say what they said
     */
    private void batchWork(int first, BitSet work, boolean all) {
        Map<String, Boolean> relationTypes = new HashMap<>();
        for (Map.Entry<String, IntList> entry : bySpelling.entrySet()) {
            IntList list = entry.getValue();
            if (list.items[list.size - 1] < first) continue;
            EnumSet<Kind> kinds = kindsOf(entry.getKey());
            if (kinds.isEmpty()) continue;
            boolean typings = TYPINGS.containsAll(kinds);
            if (!typings && !all) continue;
            for (int i = list.size - 1; i >= 0 && list.items[i] >= first; i--) {
                IFact fact = edgeSource[list.items[i]];
                if (!typings
                        || kinds.contains(Kind.TYPE) && relationTypes.computeIfAbsent(fact.object(), this::relationType)
                        || kinds.contains(Kind.INSTANCE) && relationTypes.computeIfAbsent(fact.subject(), this::relationType)) {
                    work.set(list.items[i]);
                }
            }
        }
    }

    private static final EnumSet<Kind> TYPINGS = EnumSet.of(Kind.TYPE, Kind.INSTANCE);

    /** Whether a name, or anything it is an alias of, lies under {@code relation}: a type of relations. */
    private boolean relationType(String name) {
        int relation = oriented(RELATION);
        for (String variant : aliasUp(name)) if (isSub(oriented(variant), relation)) return true;
        return false;
    }

    /** The stored meta statements at {@code name}'s concept, or only its typings. */
    private void storedMetaAt(String name, int first, BitSet work, boolean typings) {
        int root = root(name);
        for (int node = root * 2; node <= root * 2 + 1; node++) {
            for (int i = 0; i < degreeOf(node); i++) {
                int e = adjacency[node][i];
                if (e >= first) continue;
                EnumSet<Kind> kinds = kindsOf(edgeSource[e].relation());
                if (typings ? kinds.contains(Kind.TYPE) || kinds.contains(Kind.INSTANCE) : !kinds.isEmpty()) work.set(e);
            }
        }
    }

    /** One meta statement, applied for every alias variant of its names (r06–r08). */
    private boolean applyMeta(IFact triple) {
        boolean changed = false;
        Set<String> subjects = aliasUp(triple.subject());
        for (String predicate : aliasUp(triple.relation())) {
            EnumSet<Kind> applying = kindCache.computeIfAbsent(predicate, this::classify);
            if (applying.isEmpty()) continue;
            // An alias statement's object needs no variants: what the object is an alias of, the
            // alias reaches by walking on (r08 would only write out the walk, every chain as every
            // pair).
            Set<String> objects = applying.contains(Kind.ALIAS) || applying.contains(Kind.HAS_ALIAS)
                    ? Set.of(triple.object()) : aliasUp(triple.object());
            for (String subject : subjects) {
                for (String object : objects) {
                    for (Kind kind : applying) changed |= apply(kind, subject, object, triple);
                }
            }
        }
        return changed;
    }

    /** What a predicate does at the meta level: what it lies under, and what its alias variants do. */
    private EnumSet<Kind> kindsOf(String predicate) {
        Set<String> variants = aliasUp(predicate);
        if (variants.size() == 1) return kindCache.computeIfAbsent(predicate, this::classify);
        EnumSet<Kind> out = EnumSet.noneOf(Kind.class);
        for (String variant : variants) out.addAll(kindCache.computeIfAbsent(variant, this::classify));
        return out;
    }

    /** {@code name}, and every name it is an alias of, at any depth. */
    private Set<String> aliasUp(String name) {
        return aliasWalk(name, aliasTargets);
    }

    /** {@code name}, and every name that is an alias of it, at any depth. */
    private Set<String> aliasDown(String name) {
        return aliasWalk(name, aliasSources);
    }

    private Set<String> aliasWalk(String name, Map<String, ? extends java.util.Collection<String>> edges) {
        if (!edges.containsKey(name)) return Set.of(name);
        Map<String, Set<String>> cache = edges == aliasTargets ? aliasUpCache : aliasDownCache;
        Set<String> cached = cache.get(name);
        if (cached != null) return cached;
        Set<String> out = new LinkedHashSet<>(List.of(name));
        Deque<String> queue = new ArrayDeque<>(List.of(name));
        while (!queue.isEmpty()) {
            java.util.Collection<String> step = edges.get(queue.poll());
            if (step == null) continue;
            for (String next : step) {
                if (out.add(next)) queue.add(next);
            }
        }
        cache.put(name, out);
        return out;
    }

    /**
     * Each name's alias variants, remembered until an alias is added. Walking the aliases afresh
     * for every name of every meta statement was most of building a model on a graph with many aliases.
     */
    private final Map<String, Set<String>> aliasUpCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Set<String>> aliasDownCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One edge per pair of names. Applied to every alias variant of a meta statement, r06–r08 say
     * the same alias again and again, each time from another statement; one of them is proof enough.
     */
    private boolean alias(String alias, String resolved, IFact why) {
        if (!aliasTargets.computeIfAbsent(alias, k -> new LinkedHashSet<>()).add(resolved)) return false;
        aliasSources.computeIfAbsent(resolved, k -> new ArrayList<>(1)).add(alias);
        aliasUpCache.clear();
        aliasDownCache.clear();
        aliasEdges.add(new AliasEdge(alias, resolved, why));
        newAliases.add(alias);
        return true;
    }

    // ---- repairing what is stored --------------------------------------------------------------

    /**
     * Moves the triples of every concept joined into another to the node it now is, read in the
     * direction it now is: the stored ends of a triple are concepts, and identity just made two one.
     */
    private void moveJoined() {
        for (int absorbed : identity.drainJoined()) {
            CdsIdentity.Ref to = identity.find(absorbed);
            boolean symmetric = identity.isSymmetric(to.root());
            for (int parity = 0; parity < 2; parity++) {
                move(absorbed * 2 + parity, symmetric ? to.root() * 2 : to.root() * 2 + (parity ^ to.parity()));
            }
            Set<String> names = new HashSet<>();
            for (Member member : membersOf(to.root())) names.add(member.name());
            for (Member member : membersOf(absorbed)) names.add(member.name());
            List<Member> list = new ArrayList<>();
            for (String name : names) list.add(new Member(name, identity.find(identity.id(name)).parity()));
            list.sort(Comparator.comparing(Member::name));
            members.remove(absorbed);
            members.put(to.root(), list);
        }
        // A concept that became its own inverse is read one way only.
        for (int made : identity.drainSymmetric()) {
            int root = identity.find(made).root();
            move(root * 2 + 1, root * 2);
        }
    }

    /**
     * After the meta level changed: the caches of what it was, the alias edges between nodes, and
     * every stored triple whose predicate is now stored differently -- another concept, another
     * direction, or its own inverse.
     */
    private void repair(int first) {
        moveJoined();
        canonicalParity.clear();
        transitiveAbove.clear();
        transitiveUnder.clear();
        groups.clear();
        reaches.clear();
        cacheIds();
        aliasUpEdges.clear();
        aliasDownEdges.clear();
        for (AliasEdge edge : aliasEdges) aliasNodes(edge);
        for (Map.Entry<String, IntList> entry : bySpelling.entrySet()) {
            long state = storedAs(entry.getKey());
            Long was = spellingState.put(entry.getKey(), state);
            if (was == null || was == state) continue;
            IntList list = entry.getValue();
            for (int i = 0; i < list.size && list.items[i] < first; i++) restore(list.items[i]);
        }
    }

    /** How a predicate's triples are stored: the relation, which way round, and whether either way. */
    private long storedAs(String predicate) {
        int relation = oriented(predicate);
        int root = relation >> 1;
        return ((long) relation << 2) | ((long) canonicalParity(root) << 1) | (identity.isSymmetric(root) ? 1 : 0);
    }

    /** A stored triple normalised again, in place: its ends are already the right nodes, joining moved them. */
    private void restore(int e) {
        Stored now = normalise(edgeSource[e]);
        int s = edgeSubject[e];
        int o = edgeObject[e];
        boolean same = (now.subject() == s && now.object() == o) || (now.subject() == o && now.object() == s);
        if (!same) {
            detach(s, e);
            if (o != s) detach(o, e);
        }
        edgeSubject[e] = now.subject();
        edgeRelation[e] = now.relation();
        edgeObject[e] = now.object();
        if (!same) {
            attach(now.subject(), e);
            if (now.object() != now.subject()) attach(now.object(), e);
        }
    }

    private void detach(int node, int e) {
        int[] list = adjacency[node];
        for (int i = 0; i < degree[node]; i++) {
            if (list[i] != e) continue;
            System.arraycopy(list, i + 1, list, i, degree[node] - i - 1);
            degree[node]--;
            if (i < sorted[node]) sorted[node]--;
            return;
        }
    }

    /** Every triple at {@code from} rewritten to be at {@code to}, and listed there. */
    private void move(int from, int to) {
        if (from == to) return;
        for (int i = 0; i < degreeOf(from); i++) {
            int e = adjacency[from][i];
            boolean alreadyThere = edgeSubject[e] == to || edgeObject[e] == to;
            // The triple's other end, whose list it is sorted in by this end, which changes.
            int other = edgeSubject[e] == from ? edgeObject[e] : edgeSubject[e];
            if (other < sorted.length) {
                sorted[other] = 0;
                unsorted.set(other);
            }
            if (edgeSubject[e] == from) edgeSubject[e] = to;
            if (edgeObject[e] == from) edgeObject[e] = to;
            if (!alreadyThere) attach(to, e);
        }
        if (to < sorted.length) {
            sorted[to] = 0;
            unsorted.set(to);
        }
        if (from < adjacency.length) {
            adjacency[from] = null;
            degree[from] = 0;
            sorted[from] = 0;
        }
    }

    private EnumSet<Kind> classify(String predicate) {
        int relation = oriented(predicate);
        EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
        if (isSub(relation, oriented(SAME_AS))) kinds.add(Kind.SAME);
        if (isSub(relation, oriented(IS_ALIAS_OF))) kinds.add(Kind.ALIAS);
        if (isSub(relation, oriented(HAS_ALIAS))) kinds.add(Kind.HAS_ALIAS);
        if (isSub(relation, oriented(HAS_INVERSE))) kinds.add(Kind.INVERSE);
        if (isSub(relation, oriented(HAS_SUBTYPE))) kinds.add(Kind.SUBTYPE);
        if (isSub(relation, oriented(HAS_SUPERTYPE))) kinds.add(Kind.SUPERTYPE);
        if (isSub(relation, oriented(HAS_TYPE))) kinds.add(Kind.TYPE);
        if (isSub(relation, oriented(HAS_INSTANCE))) kinds.add(Kind.INSTANCE);
        return kinds;
    }

    /** One meta statement, about {@code subject} and {@code object} -- the triple's own names, or alias variants of them. */
    private boolean apply(Kind kind, String subject, String object, IFact triple) {
        List<IFact> why = List.of(triple);
        return switch (kind) {
            case SAME -> identity.union(subject, object, 0, why);
            // An alias is not a second name of the same concept: it hands what is said about it on.
            case ALIAS -> alias(subject, object, triple);
            case HAS_ALIAS -> alias(object, subject, triple);
            case INVERSE -> identity.union(subject, object, 1, why);
            case SUBTYPE -> subtype(object, subject, triple);
            case SUPERTYPE -> subtype(subject, object, triple);
            case TYPE -> typed(subject, object, triple);
            case INSTANCE -> typed(object, subject, triple);
        };
    }

    private boolean subtype(String sub, String sup, IFact triple) {
        if (!subtypePairs.add(sub + "\u0000" + sup)) return false;
        subtypeEdges.add(new NameEdge(sub, sup, triple));
        preferredNames.add(sub);
        return true;
    }

    /** {@code thing has type type}, where the type says something about relations. */
    private boolean typed(String thing, String type, IFact triple) {
        int typeRelation = oriented(type);
        boolean changed = false;
        if (isSub(typeRelation, oriented(RELATION))) changed |= relationNames.add(thing);
        if (isSub(typeRelation, oriented(TRANSITIVE_RELATION))) changed |= transitiveNames.add(thing);
        if (isSub(typeRelation, oriented(SYMMETRIC_RELATION))) changed |= identity.union(thing, thing, 1, List.of(triple));
        return changed;
    }

    /**
     * Compiles the hierarchy from this round's subtype edges.
     *
     * @return whether a cycle was found and collapsed, which changes identity and needs another round
     */
    private boolean buildHierarchy(Set<String> predicates) {
        relationRoots = relations(predicates);
        Set<Integer> transitive = new HashSet<>();
        for (String name : transitiveNames) transitive.add(root(name));
        transitiveRoots = transitive;

        indexOf = new int[identity.size() * 2 + 2];
        Arrays.fill(indexOf, -1);
        nodes.clear();
        ups.clear();
        subtypeDowns = null;
        for (NameEdge edge : subtypeEdges) {
            int sub = oriented(edge.sub());
            int sup = oriented(edge.sup());
            addUp(sub, sup, List.of(edge.why()), true);
            // r17: the inverse of a sub-relation lies under the inverse of its super-relation
            if (relationRoots.contains(sub >> 1) || relationRoots.contains(sup >> 1)) {
                addUp(inverse(sub), inverse(sup), List.of(edge.why()), true);
            }
        }
        // r07: a triple with an alias as its relation is one with the relation it is an alias of --
        // which is what lying under it means.
        for (AliasEdge edge : aliasEdges) {
            int alias = oriented(edge.alias());
            int resolved = oriented(edge.resolved());
            if (!relationRoots.contains(alias >> 1) || (alias >> 1) == (resolved >> 1)) continue;
            addUp(alias, resolved, List.of(edge.why()), false);
            addUp(inverse(alias), inverse(resolved), List.of(edge.why()), false);
        }
        // A relation nobody placed lies under the root.
        int related = oriented(RELATED);
        int hasInverse = oriented(HAS_INVERSE);
        for (int root : relationRoots) {
            for (int relation : identity.isSymmetric(root) ? List.of(root * 2) : List.of(root * 2, root * 2 + 1)) {
                if (relation == related || relation == hasInverse) continue;
                int at = at(relation);
                // Placed means a has-subtype step: an alias lies under what it aliases, but is placed by no triple.
                if (at < 0 || ups.get(at).stream().noneMatch(Up::subtype)) addUp(relation, related, List.of(), true);
            }
        }

        // One closure per strongly connected component, children before parents: a component's
        // ancestors are its own members and the ancestors of whatever its members point up to.
        List<int[]> components = components();
        int[] componentOf = new int[nodes.size()];
        for (int c = 0; c < components.size(); c++) for (int member : components.get(c)) componentOf[member] = c;
        BitSet[] closures = new BitSet[components.size()];
        for (int c = 0; c < components.size(); c++) {
            BitSet closure = new BitSet();
            for (int member : components.get(c)) {
                closure.set(member);
                for (Up up : ups.get(member)) {
                    int target = componentOf[at(up.to())];
                    if (target != c) closure.or(closures[target]);
                }
            }
            closures[c] = closure;
        }
        above = new BitSet[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) above[i] = closures[componentOf[i]];

        // A has-subtype cycle collapses to same as: every member of a component is its first.
        boolean changed = false;
        for (int[] component : components) {
            for (int k = 1; k < component.length; k++) {
                int a = nodes.get(component[0]);
                int b = nodes.get(component[k]);
                List<IFact> why = new ArrayList<>(hierarchyWhy(a, b));
                why.addAll(hierarchyWhy(b, a));
                changed |= identity.union(identity.name(a >> 1), identity.name(b >> 1), (a ^ b) & 1, why);
            }
        }
        return changed;
    }

    /**
     * What is a relation: whatever is typed so, whatever is used as a predicate, and everything a
     * {@code has subtype} edge ties to a relation.
     */
    private Set<Integer> relations(Set<String> predicates) {
        Set<Integer> out = new HashSet<>();
        for (String name : relationNames) out.add(root(name));
        for (String name : predicates) out.add(root(name));
        Map<Integer, List<Integer>> neighbours = new HashMap<>();
        for (NameEdge edge : subtypeEdges) {
            int a = root(edge.sub());
            int b = root(edge.sup());
            neighbours.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
            neighbours.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
        }
        // An alias of a relation makes what it is an alias of one: r06 says so of every statement
        // that typed it, and r07 of every triple it was the relation of.
        for (AliasEdge edge : aliasEdges) {
            neighbours.computeIfAbsent(root(edge.alias()), k -> new ArrayList<>()).add(root(edge.resolved()));
        }
        Deque<Integer> queue = new ArrayDeque<>(out);
        while (!queue.isEmpty()) {
            for (int next : neighbours.getOrDefault(queue.poll(), List.of())) {
                if (out.add(next)) queue.add(next);
            }
        }
        return out;
    }

    /**
     * The strongly connected components of the hierarchy, each a list of node indexes, in the order
     * Tarjan's algorithm finds them: a component comes after every component it points up to.
     * Iterative, since a type chain can be thousands deep.
     */
    private List<int[]> components() {
        int n = nodes.size();
        int[] number = new int[n];
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(number, -1);
        int[] stack = new int[n];
        int stackSize = 0;
        int[] callNode = new int[n];
        int[] callEdge = new int[n];
        int counter = 0;
        List<int[]> out = new ArrayList<>();
        for (int start = 0; start < n; start++) {
            if (number[start] >= 0) continue;
            int depth = 0;
            callNode[0] = start;
            callEdge[0] = 0;
            number[start] = low[start] = counter++;
            stack[stackSize++] = start;
            onStack[start] = true;
            while (depth >= 0) {
                int v = callNode[depth];
                List<Up> edges = ups.get(v);
                if (callEdge[depth] < edges.size()) {
                    int w = at(edges.get(callEdge[depth]++).to());
                    if (number[w] < 0) {
                        number[w] = low[w] = counter++;
                        stack[stackSize++] = w;
                        onStack[w] = true;
                        depth++;
                        callNode[depth] = w;
                        callEdge[depth] = 0;
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], number[w]);
                    }
                    continue;
                }
                if (low[v] == number[v]) {
                    int size = 0;
                    while (stack[stackSize - 1 - size] != v) size++;
                    size++;
                    int[] component = new int[size];
                    for (int k = 0; k < size; k++) {
                        int member = stack[--stackSize];
                        onStack[member] = false;
                        component[size - 1 - k] = member;
                    }
                    out.add(component);
                }
                depth--;
                if (depth >= 0) low[callNode[depth]] = Math.min(low[callNode[depth]], low[v]);
            }
        }
        return out;
    }

    private int at(int relation) {
        return relation < indexOf.length ? indexOf[relation] : -1;
    }

    private int node(int relation) {
        int at = at(relation);
        if (at >= 0) return at;
        if (relation >= indexOf.length) {
            int old = indexOf.length;
            indexOf = Arrays.copyOf(indexOf, Math.max(relation + 1, old * 2));
            Arrays.fill(indexOf, old, indexOf.length, -1);
        }
        indexOf[relation] = nodes.size();
        nodes.add(relation);
        ups.add(new ArrayList<>());
        return nodes.size() - 1;
    }

    private void addUp(int sub, int sup, List<IFact> why, boolean subtype) {
        if (sub == sup) return;
        int from = node(sub);
        node(sup);
        ups.get(from).add(new Up(sup, why, subtype));
    }

    // ---- the hierarchy as has-subtype triples ------------------------------------------------------

    /**
     * For each hierarchy node, the nodes one {@code has subtype} step below it: {@link #ups} backwards,
     * the subtype steps only. Built on first use after the hierarchy was compiled; questions run in
     * parallel, so whichever builds it first wins, and every build is the same.
     */
    private volatile @Nullable List<List<Up>> subtypeDowns;

    private List<List<Up>> subtypeDowns() {
        List<List<Up>> downs = subtypeDowns;
        if (downs != null) return downs;
        downs = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) downs.add(new ArrayList<>());
        for (int i = 0; i < nodes.size(); i++) {
            for (Up up : ups.get(i)) {
                if (up.subtype()) downs.get(at(up.to())).add(new Up(nodes.get(i), up.why(), true));
            }
        }
        subtypeDowns = downs;
        return downs;
    }

    /**
     * Whether {@code sup has subtype sub} holds through the compiled hierarchy, and why: the steps a
     * walk up from {@code sub} takes. One step only without chains (r01 off); stated steps, r17's and
     * the root's alike. The stored triples answer the rest, so this is what they cannot: r17 places the
     * inverse of every sub-relation, and stores nothing.
     */
    private @Nullable Set<IFact> subtypeStep(int sub, int sup, boolean chains) {
        if (sub == sup) return null;
        int start = at(sub);
        int goal = at(sup);
        if (start < 0 || goal < 0 || !above[start].get(goal)) return null;
        Map<Integer, Integer> previous = new HashMap<>();
        Map<Integer, List<IFact>> via = new HashMap<>();
        previous.put(start, start);
        Deque<Integer> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            int at = queue.poll();
            for (Up up : ups.get(at)) {
                if (!up.subtype()) continue;
                int to = at(up.to());
                if (previous.containsKey(to)) continue;
                previous.put(to, at);
                via.put(to, up.why());
                if (to == goal) {
                    if (!proving.get()) return found();
                    Set<IFact> out = new LinkedHashSet<>();
                    for (int step = goal; step != start; step = previous.get(step)) out.addAll(via.get(step));
                    return out;
                }
                if (chains) queue.add(to);
            }
        }
        return null;
    }

    /** Every node {@code sub} with {@code x has subtype sub} through the hierarchy: {@link #subtypeStep}, enumerated. */
    private Set<Integer> subtypesThrough(int x, boolean chains) {
        return walkSubtypes(x, chains, true);
    }

    /** Every node {@code sup} with {@code sup has subtype x} through the hierarchy. */
    private Set<Integer> supertypesThrough(int x, boolean chains) {
        return walkSubtypes(x, chains, false);
    }

    private Set<Integer> walkSubtypes(int x, boolean chains, boolean down) {
        int start = at(x);
        if (start < 0) return Set.of();
        List<List<Up>> steps = down ? subtypeDowns() : ups;
        Set<Integer> out = new LinkedHashSet<>();
        Deque<Integer> queue = new ArrayDeque<>(List.of(start));
        Set<Integer> seen = new HashSet<>(List.of(start));
        while (!queue.isEmpty()) {
            for (Up up : steps.get(queue.poll())) {
                if (!up.subtype()) continue;
                int to = at(up.to());
                if (!seen.add(to)) continue;
                if (up.to() != x) out.add(up.to());
                if (chains) queue.add(to);
            }
        }
        return out;
    }

    // ---- hierarchy -----------------------------------------------------------------------------

    int oriented(String name) {
        CdsIdentity.Ref ref = identity.ref(name);
        return ref.root() * 2 + ref.parity();
    }

    int root(String name) {
        return identity.ref(name).root();
    }

    private int inverse(int relation) {
        return identity.isSymmetric(relation >> 1) ? relation : relation ^ 1;
    }

    /** Whether {@code sub} lies under {@code sup}, both oriented. Every relation lies under itself. */
    boolean isSub(int sub, int sup) {
        if (sub == sup) return true;
        int from = at(sub);
        int to = at(sup);
        return from >= 0 && to >= 0 && above[from].get(to);
    }

    /** The {@code has subtype} triples on one path from {@code sub} up to {@code sup}. */
    private List<IFact> hierarchyWhy(int sub, int sup) {
        if (sub == sup) return List.of();
        int start = at(sub);
        int goal = at(sup);
        if (start < 0 || goal < 0) return List.of();
        Map<Integer, Integer> previous = new HashMap<>();
        Map<Integer, List<IFact>> via = new HashMap<>();
        previous.put(start, start);
        Deque<Integer> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            int at = queue.poll();
            for (Up up : ups.get(at)) {
                int to = at(up.to());
                if (previous.containsKey(to)) continue;
                previous.put(to, at);
                via.put(to, up.why());
                if (to == goal) {
                    List<IFact> out = new ArrayList<>();
                    for (int step = goal; step != start; step = previous.get(step)) out.addAll(0, via.get(step));
                    return out;
                }
                queue.add(to);
            }
        }
        return List.of();
    }

    // ---- the vocabulary's own ids, again whenever the meta level changes -----------------------

    private int hasType, hasInstance, hasTag, isTagOf, hasPart, isPartOf, hasSubtype, sameAs, hasInverse, related;
    private int typeItem, relationItem, symmetricItem, transitiveItem;
    private List<Integer> transitiveRelations = List.of();
    private List<Integer> relationIds = List.of();

    private void cacheIds() {
        hasType = oriented(HAS_TYPE);
        hasInstance = oriented(HAS_INSTANCE);
        hasTag = oriented(HAS_TAG);
        isTagOf = oriented(IS_TAG_OF);
        hasPart = oriented(HAS_PART);
        isPartOf = oriented(IS_PART_OF);
        hasSubtype = oriented(HAS_SUBTYPE);
        sameAs = oriented(SAME_AS);
        hasInverse = oriented(HAS_INVERSE);
        related = oriented(RELATED);
        typeItem = oriented(TYPE);
        relationItem = oriented(RELATION);
        symmetricItem = oriented(SYMMETRIC_RELATION);
        transitiveItem = oriented(TRANSITIVE_RELATION);
        List<Integer> transitive = new ArrayList<>();
        for (int root : transitiveRoots) {
            transitive.add(root * 2);
            if (!identity.isSymmetric(root)) transitive.add(root * 2 + 1);
        }
        transitiveRelations = List.copyOf(transitive);
        List<Integer> relations = new ArrayList<>();
        for (int root : relationRoots) {
            relations.add(root * 2);
            if (!identity.isSymmetric(root)) relations.add(root * 2 + 1);
        }
        relationIds = List.copyOf(relations);
    }

    // ---- names ---------------------------------------------------------------------------------

    private List<Member> membersOf(int root) {
        List<Member> list = members.get(root);
        return list != null ? list : List.of(new Member(identity.name(root), 0));
    }

    /**
     * The name a class goes by, read in the given direction.
     *
     * <p>The smallest name of the class wins -- the class being the names that are the same as each
     * other; an alias is a name of its own. A direction nobody named is the other one with a
     * {@code ^}.
     */
    String name(int relation) {
        int root = relation >> 1;
        boolean symmetric = identity.isSymmetric(root);
        for (Member member : membersOf(root)) {
            if (!symmetric && member.parity() != (relation & 1)) continue;
            return member.name();
        }
        return "^" + name(relation ^ 1);
    }

    /**
     * Which way round a relation is stored: its {@code links to} side.
     *
     * <p>Where neither side is under {@code links to} -- the branch under {@code has similar} -- the
     * side the vocabulary or a {@code has subtype} statement names wins, then the side somebody
     * named at all, then the smaller name.
     */
    private int canonicalParity(int root) {
        return canonicalParity.computeIfAbsent(root, r -> {
            if (identity.isSymmetric(r)) return 0;
            int linksTo = oriented(LINKS_TO);
            if (isSub(r * 2, linksTo)) return 0;
            if (isSub(r * 2 + 1, linksTo)) return 1;
            List<Member> list = membersOf(r);
            for (Member member : list) {
                if (preferredNames.contains(member.name())) return member.parity();
            }
            boolean forwards = list.stream().anyMatch(m -> m.parity() == 0);
            boolean backwards = list.stream().anyMatch(m -> m.parity() == 1);
            if (forwards != backwards) return forwards ? 0 : 1;
            return name(r * 2).compareTo(name(r * 2 + 1)) <= 0 ? 0 : 1;
        });
    }

    // ---- storing -------------------------------------------------------------------------------

    /** Stores a registered fact, normalised, and lists it at both ends. */
    private void index(int e) {
        Stored stored = normalise(edgeSource[e]);
        invalidateReaches(stored.relation());
        edgeSubject[e] = stored.subject();
        edgeRelation[e] = stored.relation();
        edgeObject[e] = stored.object();
        attach(stored.subject(), e);
        if (stored.object() != stored.subject()) attach(stored.object(), e);
        spellingState.computeIfAbsent(edgeSource[e].relation(), this::storedAs);
        // Typing creates types.
        if (isSub(stored.relation(), hasInstance)) typeRoot(stored.subject() >> 1);
        if (isSub(inverse(stored.relation()), hasInstance)) typeRoot(stored.object() >> 1);
    }

    /** A type, and with it what it is an alias of (r08: what is typed with it is typed with that too). */
    private void typeRoot(int root) {
        for (int up : aliasUp(root * 2).keySet()) {
            if (relationRoots.contains(up >> 1)) continue;
            typeRoots.add(up >> 1);
            typeRoots.add(typeItem >> 1);
        }
    }

    /** What is a type, worked out afresh after the meta level changed: typing and subtyping create types. */
    private void computeTypeRoots() {
        typeRoots.clear();
        for (Map.Entry<String, IntList> entry : bySpelling.entrySet()) {
            int relation = oriented(entry.getKey());
            boolean forwards = isSub(relation, hasInstance);
            boolean backwards = isSub(inverse(relation), hasInstance);
            if (!forwards && !backwards) continue;
            IntList list = entry.getValue();
            for (int i = 0; i < list.size; i++) {
                int e = list.items[i];
                // Stored the other way round, a has-instance triple is a has-type one: either way,
                // the end the stored relation types with is the type.
                if (isSub(edgeRelation[e], hasInstance)) typeRoot(edgeSubject[e] >> 1);
                if (isSub(inverse(edgeRelation[e]), hasInstance)) typeRoot(edgeObject[e] >> 1);
            }
        }
        for (NameEdge edge : subtypeEdges) {
            typeRoot(root(edge.sub()));
            typeRoot(root(edge.sup()));
        }
    }

    private void aliasNodes(AliasEdge edge) {
        int alias = oriented(edge.alias());
        int resolved = oriented(edge.resolved());
        if (alias == resolved) return;
        aliasUpEdges.computeIfAbsent(alias, k -> new ArrayList<>()).add(new Up(resolved, List.of(edge.why()), false));
        aliasDownEdges.computeIfAbsent(resolved, k -> new ArrayList<>()).add(new Up(alias, List.of(edge.why()), false));
    }

    /** {@code node} and every node it is an alias of, each with the alias statements on the way. */
    private Map<Integer, List<IFact>> aliasUp(int node) {
        return aliasWalk(node, aliasUpEdges);
    }

    /** {@code node} and every node that is an alias of it: whose statements are statements about it too. */
    private Map<Integer, List<IFact>> aliasDown(int node) {
        return aliasWalk(node, aliasDownEdges);
    }

    private Map<Integer, List<IFact>> aliasWalk(int node, Map<Integer, List<Up>> edges) {
        if (!edges.containsKey(node)) return Map.of(node, List.of());
        Map<Integer, List<IFact>> out = new LinkedHashMap<>();
        out.put(node, List.of());
        Deque<Integer> queue = new ArrayDeque<>(List.of(node));
        while (!queue.isEmpty()) {
            int at = queue.poll();
            for (Up up : edges.getOrDefault(at, List.of())) {
                if (out.containsKey(up.to())) continue;
                List<IFact> why = new ArrayList<>(out.get(at));
                why.addAll(up.why());
                out.put(up.to(), why);
                queue.add(up.to());
            }
        }
        return out;
    }

    /**
     * Subject and object are oriented too: a statement about {@code has type} is not one about
     * {@code has instance}, although the two are one class.
     */
    private Stored normalise(IFact triple) {
        int subject = oriented(triple.subject());
        int object = oriented(triple.object());
        int relation = oriented(triple.relation());
        int root = relation >> 1;
        if (identity.isSymmetric(root)) {
            boolean ordered = name(subject).compareTo(name(object)) <= 0;
            return ordered ? new Stored(subject, relation, object, triple) : new Stored(object, relation, subject, triple);
        }
        if ((relation & 1) == canonicalParity(root)) return new Stored(subject, relation, object, triple);
        return new Stored(object, relation ^ 1, subject, triple);
    }

    Triple canonicalForm(IFact triple) {
        Stored stored = normalise(triple);
        return new Triple(name(stored.subject()), name(stored.relation()), name(stored.object()));
    }

    /** A stored triple as a record, made when one is needed: for a hop, or a proof. */
    private Stored stored(int e) {
        return new Stored(edgeSubject[e], edgeRelation[e], edgeObject[e], edgeSource[e]);
    }

    /** The end of triple {@code e} that is not {@code node}; {@code node} itself for a loop. */
    private int otherEnd(int node, int e) {
        return edgeSubject[e] == node ? edgeObject[e] : edgeSubject[e];
    }

    /**
     * Sorts again the lists a batch left with a long unsorted tail: every list, after the first
     * batch; after a later one, those of its triples' ends, and those joining moved triples to or
     * from, once the tail is more than a quarter of what is sorted. Lists grow by doubling, so
     * sorting is paid for a few times per list, however the store grows.
     */
    private void sortLists(int first) {
        if (first == 0) {
            for (int node = 0; node < adjacency.length; node++) sortList(node);
            unsorted.clear();
            return;
        }
        for (int e = first; e < edgeCount; e++) {
            unsorted.set(edgeSubject[e]);
            unsorted.set(edgeObject[e]);
        }
        for (int node = unsorted.nextSetBit(0); node >= 0; node = unsorted.nextSetBit(node + 1)) {
            int tail = degreeOf(node) - sorted[node];
            if (tail > 8 && tail * 4 > sorted[node]) sortList(node);
        }
        unsorted.clear();
    }

    /** One node's list sorted by the other end, each triple keyed by (other end, index). */
    private void sortList(int node) {
        int d = degree[node];
        if (d > 1) {
            int[] list = adjacency[node];
            long[] keys = new long[d];
            for (int i = 0; i < d; i++) keys[i] = ((long) otherEnd(node, list[i]) << 32) | list[i];
            Arrays.sort(keys);
            for (int i = 0; i < d; i++) list[i] = (int) keys[i];
        }
        sorted[node] = d;
    }

    /**
     * Every array cut to what it holds, after the first batch. Lists grow by doubling, so up to
     * half of each is empty; growing doubles them again.
     */
    private void trim() {
        edgeSubject = Arrays.copyOf(edgeSubject, edgeCount);
        edgeRelation = Arrays.copyOf(edgeRelation, edgeCount);
        edgeObject = Arrays.copyOf(edgeObject, edgeCount);
        edgeSource = Arrays.copyOf(edgeSource, edgeCount);
        for (int node = 0; node < adjacency.length; node++) {
            if (adjacency[node] != null && adjacency[node].length > degree[node]) {
                adjacency[node] = Arrays.copyOf(adjacency[node], degree[node]);
            }
        }
        for (IntList list : bySpelling.values()) list.items = Arrays.copyOf(list.items, Math.max(1, list.size));
    }

    private void attach(int node, int e) {
        if (node >= adjacency.length) {
            int size = Math.max(node + 1, adjacency.length * 2);
            adjacency = Arrays.copyOf(adjacency, size);
            degree = Arrays.copyOf(degree, size);
            sorted = Arrays.copyOf(sorted, size);
        }
        int[] list = adjacency[node];
        if (list == null) {
            list = new int[2];
            adjacency[node] = list;
        } else if (degree[node] == list.length) {
            list = Arrays.copyOf(list, Math.max(2, list.length * 2)); // trimmed lists can be empty
            adjacency[node] = list;
        }
        list[degree[node]++] = e;
    }

    private int degreeOf(int node) {
        return node < degree.length ? degree[node] : 0;
    }

    /**
     * The stored triples between two nodes, in either direction: the triples of whichever of the two
     * has fewer, kept if their other end is the other node.
     */
    private List<Stored> between(int x, int y) {
        int near = degreeOf(x) <= degreeOf(y) ? x : y;
        int far = near == x ? y : x;
        List<Stored> out = new ArrayList<>(2);
        int d = degreeOf(near);
        if (d == 0) return out;
        int[] list = adjacency[near];
        // The sorted prefix: the first triple whose other end is not below far, then all equal.
        int low = 0;
        int high = sorted[near];
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (otherEnd(near, list[middle]) < far) low = middle + 1;
            else high = middle;
        }
        for (int i = low; i < sorted[near] && otherEnd(near, list[i]) == far; i++) out.add(stored(list[i]));
        // The unsorted tail, from growing and joining.
        for (int i = sorted[near]; i < d; i++) {
            if (otherEnd(near, list[i]) == far) out.add(stored(list[i]));
        }
        return out;
    }

    /**
     * A node's stored triples by the relation they leave it by -- the stated one where the node is
     * the subject, its inverse where it is the object; a loop in both. Only for a node with
     * {@value #GROUPED} triples or more, made on first use: a step of one relation from a hub read
     * every triple at the hub to find the few of that relation, and a failed test of {@code
     * related} against a hub took several such reads.
     */
    private record Groups(int[] relations, int[][] edges) {}

    private static final int GROUPED = 64;

    private final Map<Integer, Groups> groups = new java.util.concurrent.ConcurrentHashMap<>();

    private @Nullable Groups groupsOf(int node) {
        if (degreeOf(node) < GROUPED) return null;
        return groups.computeIfAbsent(node, this::group);
    }

    private Groups group(int node) {
        Map<Integer, IntList> by = new LinkedHashMap<>();
        for (int i = 0; i < degree[node]; i++) {
            int e = adjacency[node][i];
            if (edgeSubject[e] == node) by.computeIfAbsent(edgeRelation[e], k -> new IntList()).add(e);
            if (edgeObject[e] == node) by.computeIfAbsent(inverse(edgeRelation[e]), k -> new IntList()).add(e);
        }
        int[] relations = new int[by.size()];
        int[][] edges = new int[by.size()][];
        int k = 0;
        for (Map.Entry<Integer, IntList> entry : by.entrySet()) {
            relations[k] = entry.getKey();
            edges[k++] = Arrays.copyOf(entry.getValue().items, entry.getValue().size);
        }
        return new Groups(relations, edges);
    }

    // ---- queries -------------------------------------------------------------------------------

    boolean isRelation(String name) {
        return relationRoots.contains(root(name));
    }

    boolean isType(String name) {
        return typeRoots.contains(root(name));
    }

    boolean isSymmetric(String name) {
        return isRelation(name) && identity.isSymmetric(root(name));
    }

    boolean isTransitive(String name) {
        return transitiveRoots.contains(root(name));
    }

    String canonical(String name) {
        return name(oriented(name));
    }

    /**
     * Whether proofs are being built on this thread. {@link #holds} and {@link #match} only need to
     * know that a derivation exists, and building its proof -- walking the identity and hierarchy
     * graphs for the statements it went through -- was most of what a {@code holds} cost.
     */
    private final ThreadLocal<Boolean> proving = ThreadLocal.withInitial(() -> true);

    /** Whether {@code x p y} holds, without working out why. */
    boolean holds(String x, String p, String y) {
        if (!rules.normalise) return literal(x, p, y) != null;
        proving.set(false);
        try {
            return derive(oriented(x), oriented(p), oriented(y), true) != null;
        } finally {
            proving.set(true);
        }
    }

    /** A found derivation, when nobody asked what it rests on. */
    private static Set<IFact> found() {
        return new LinkedHashSet<>();
    }

    /** The triples one derivation of {@code x p y} rests on, or null when it does not hold. */
    @Nullable Set<IFact> why(String x, String p, String y) {
        if (!rules.normalise) return literal(x, p, y);
        int subject = oriented(x);
        int object = oriented(y);
        int relation = oriented(p);
        Set<IFact> proof = derive(subject, relation, object, true);
        if (proof == null) return null;
        Set<IFact> out = new LinkedHashSet<>(proof);
        out.addAll(identity.explain(x, identity.name(subject >> 1)));
        out.addAll(identity.explain(y, identity.name(object >> 1)));
        out.addAll(identity.explain(p, identity.name(relation >> 1)));
        return out;
    }

    /** @param chains whether to follow chains of transitive relations (r01), or only single steps */
    private @Nullable Set<IFact> derive(int x, int p, int y, boolean chains) {
        Set<IFact> proof = direct(x, p, y);
        if (proof != null) return proof;
        // Anything is the same as itself; two names of one concept are the same as each other.
        if (x == y && isSub(sameAs, p)) return new LinkedHashSet<>();
        // A relation read backwards is its inverse (r02 at the meta level, r11 for a symmetric one).
        if (relationRoots.contains(x >> 1) && y == inverse(x) && isSub(hasInverse, p)) {
            if (!proving.get()) return found();
            return new LinkedHashSet<>(identity.explain(identity.name(x >> 1), identity.name(y >> 1)));
        }
        if (isSub(hasTag, p) && (proof = tagged(x, y)) != null) return proof;
        if (isSub(isTagOf, p) && (proof = tagged(y, x)) != null) return proof;
        if (isSub(hasType, p) && (proof = typed(x, y)) != null) return proof;
        if (isSub(hasInstance, p) && (proof = typed(y, x)) != null) return proof;
        // The hierarchy itself: has-subtype triples r17 derives, which no stored triple answers.
        boolean subtypeChains = rules.transitive && chains;
        if (isSub(hasSubtype, p) && (proof = subtypeStep(y, x, subtypeChains)) != null) return proof;
        if (isSub(inverse(hasSubtype), p) && (proof = subtypeStep(x, y, subtypeChains)) != null) return proof;
        if (rules.transitive && chains) {
            BitSet candidates = chainsWorthSearching(x, y, p);
            BitSet derivable = under(p).derived();
            for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
                int transitive = transitiveRelations.get(i);
                if (derivable.get(i)) {
                    if ((proof = derivedPath(x, y, transitive)) != null) return proof;
                } else if (proving.get()) {
                    if ((proof = path(x, y, transitive)) != null) return proof;
                } else if (reachFor(i).reachable(x, y)) {
                    return found();
                }
            }
        }
        return null;
    }

    /**
     * Whether a step of {@code relation} can be a triple another rule derived: one of type
     * inheritance, of tags reaching parts, or of a relation's inverse -- the rules whose
     * conclusions are no stored triple. Where none lies under it, its chains run over stored
     * triples only, and the index answers them.
     */
    private boolean derivedStepsUnder(int relation) {
        return isSub(hasType, relation) || isSub(hasInstance, relation) || isSub(hasTag, relation)
                || isSub(isTagOf, relation) || isSub(hasInverse, relation);
    }

    /**
     * r01 over every triple, derived ones included: a walk whose every step is anything that holds
     * for the relation without a chain. Only for the relations where a step can be derived.
     */
    private @Nullable Set<IFact> derivedPath(int from, int to, int relation) {
        Map<Integer, Integer> previous = new HashMap<>();
        Deque<Integer> queue = new ArrayDeque<>(List.of(from));
        Set<Integer> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            int at = queue.poll();
            for (int next : objects(at, relation, false)) {
                if (next == to) {
                    previous.put(-1, at);
                    return derivedChain(previous, from, to, relation);
                }
                if (next == from || !seen.add(next)) continue;
                previous.put(next, at);
                queue.add(next);
            }
        }
        return null;
    }

    /** The steps of a walk {@link #derivedPath} found, each with what it rests on. */
    private Set<IFact> derivedChain(Map<Integer, Integer> previous, int from, int to, int relation) {
        if (!proving.get()) return found();
        Set<IFact> out = new LinkedHashSet<>();
        int at = previous.get(-1);
        int next = to;
        while (true) {
            Set<IFact> step = derive(at, relation, next, false);
            if (step != null) out.addAll(step);
            if (at == from) return out;
            next = at;
            at = previous.get(at);
        }
    }

    /** Everything a walk of derivable steps of {@code relation} reaches from {@code from}. */
    private Set<Integer> derivedReach(int from, int relation) {
        Set<Integer> out = new LinkedHashSet<>();
        Deque<Integer> queue = new ArrayDeque<>(List.of(from));
        Set<Integer> seen = new HashSet<>(List.of(from));
        while (!queue.isEmpty()) {
            for (int next : objects(queue.poll(), relation, false)) {
                out.add(next);
                if (seen.add(next)) queue.add(next);
            }
        }
        return out;
    }

    /**
     * The transitive relations under a relation, as indexes into {@link #transitiveRelations}, and
     * those of them whose steps can be derived. Worked out once per relation asked about: every
     * {@code holds} needs it, and under {@code related} lies every transitive relation there is.
     * Read-only once made; callers copy before changing.
     */
    private record Under(BitSet all, BitSet derived) {}

    private final Map<Integer, Under> transitiveUnder = new java.util.concurrent.ConcurrentHashMap<>();

    private Under under(int p) {
        return transitiveUnder.computeIfAbsent(p, r -> {
            BitSet all = new BitSet(transitiveRelations.size());
            BitSet derived = new BitSet(transitiveRelations.size());
            for (int i = 0; i < transitiveRelations.size(); i++) {
                int transitive = transitiveRelations.get(i);
                if (!isSub(transitive, r)) continue;
                all.set(i);
                if (derivedStepsUnder(transitive)) derived.set(i);
            }
            return new Under(all, derived);
        });
    }

    /** The transitive relations under {@code p}, as indexes into {@link #transitiveRelations}; usually none. Shared: do not change. */
    private BitSet transitiveUnder(int p) {
        return under(p).all();
    }

    /**
     * The transitive relations under {@code p} a chain from {@code x} to {@code y} could use: those
     * the end with fewer triples has a step for. Scanning both ends would cost: one end is often a
     * hub, a type with thousands of instances.
     */
    private BitSet chainsWorthSearching(int x, int y, int p) {
        Under under = under(p);
        if (under.all().isEmpty()) return new BitSet();
        BitSet out = degreeOf(x) <= degreeOf(y) ? steps(x, true) : steps(y, false);
        out.and(under.all());
        // A chain that can step along derived triples need not start with a stored one.
        out.or(under.derived());
        return out;
    }

    /** For each relation, the transitive relations above it, as indexes into {@link #transitiveRelations}. */
    private final Map<Integer, BitSet> transitiveAbove = new java.util.concurrent.ConcurrentHashMap<>();

    private BitSet transitiveAbove(int relation) {
        return transitiveAbove.computeIfAbsent(relation, r -> {
            BitSet out = new BitSet();
            for (int i = 0; i < transitiveRelations.size(); i++) if (isSub(r, transitiveRelations.get(i))) out.set(i);
            return out;
        });
    }

    /**
     * The transitive relations {@code node} has a step for, leaving it or arriving at it. A chain
     * from {@code x} to {@code y} needs a step leaving {@code x} and one arriving at {@code y}, so
     * only those relations are worth a search -- of the eighty under {@code related} on a tangled
     * hierarchy, usually none.
     */
    private BitSet steps(int node, boolean leaving) {
        BitSet out = new BitSet();
        // Statements about an alias of the node are steps of the node too (r06, r08).
        for (int at : aliasDown(node).keySet()) {
            Groups groups = groupsOf(at);
            if (groups != null) {
                for (int relation : groups.relations()) out.or(transitiveAbove(leaving ? relation : inverse(relation)));
                continue;
            }
            for (int i = 0; i < degreeOf(at); i++) {
                int e = adjacency[at][i];
                int relation = edgeRelation[e];
                if (edgeSubject[e] == at) out.or(transitiveAbove(leaving ? relation : inverse(relation)));
                if (edgeObject[e] == at) out.or(transitiveAbove(leaving ? inverse(relation) : relation));
            }
        }
        return out;
    }

    /** A stored triple between the two, in either direction, whose predicate lies under {@code p}. */
    private @Nullable Set<IFact> direct(int x, int p, int y) {
        // r06, r08: a triple stated about an alias of either end is about that end too.
        Map<Integer, List<IFact>> subjects = aliasDown(x);
        Map<Integer, List<IFact>> objects = aliasDown(y);
        if (subjects.size() == 1 && objects.size() == 1) return directly(x, p, y);
        for (Map.Entry<Integer, List<IFact>> subject : subjects.entrySet()) {
            for (Map.Entry<Integer, List<IFact>> object : objects.entrySet()) {
                Set<IFact> found = directly(subject.getKey(), p, object.getKey());
                if (found != null) {
                    if (proving.get()) {
                        found.addAll(subject.getValue());
                        found.addAll(object.getValue());
                    }
                    return found;
                }
            }
        }
        return null;
    }

    private @Nullable Set<IFact> directly(int x, int p, int y) {
        for (Stored stored : between(x, y)) {
            if (stored.subject() == x && stored.object() == y && isSub(stored.relation(), p)) return support(stored, stored.relation(), p);
            if (stored.subject() == y && stored.object() == x && isSub(inverse(stored.relation()), p)) {
                return support(stored, inverse(stored.relation()), p);
            }
        }
        return null;
    }

    /** A stored triple, the names it was normalised through, and the hierarchy path it was lifted along. */
    private Set<IFact> support(Stored stored, int usedAs, int p) {
        if (!proving.get()) return found();
        IFact source = stored.source();
        Set<IFact> out = new LinkedHashSet<>();
        out.add(source);
        out.addAll(identity.explain(source.subject(), identity.name(root(source.subject()))));
        out.addAll(identity.explain(source.object(), identity.name(root(source.object()))));
        out.addAll(identity.explain(source.relation(), identity.name(usedAs >> 1)));
        out.addAll(hierarchyWhy(usedAs, p));
        return out;
    }

    /**
     * The steps of {@code relation} from {@code node}: along every stored triple about it or about
     * an alias of it (r06, r08), to the other end and to everything the other end is an alias of.
     */
    private List<Hop> hops(int node, int relation) {
        List<Hop> out = new ArrayList<>();
        for (Map.Entry<Integer, List<IFact>> source : aliasDown(node).entrySet()) {
            int at = source.getKey();
            Groups groups = groupsOf(at);
            if (groups != null) {
                for (int k = 0; k < groups.relations().length; k++) {
                    int leaving = groups.relations()[k];
                    if (!isSub(leaving, relation)) continue;
                    for (int e : groups.edges()[k]) landing(out, node, otherEnd(at, e), e, leaving, source.getValue());
                }
                continue;
            }
            for (int i = 0; i < degreeOf(at); i++) {
                int e = adjacency[at][i];
                int stated = edgeRelation[e];
                if (edgeSubject[e] == at && isSub(stated, relation)) {
                    landing(out, node, edgeObject[e], e, stated, source.getValue());
                }
                if (edgeObject[e] == at && isSub(inverse(stated), relation)) {
                    landing(out, node, edgeSubject[e], e, inverse(stated), source.getValue());
                }
            }
        }
        return out;
    }

    private void landing(List<Hop> out, int from, int to, int e, int relation, List<IFact> aliases) {
        Map<Integer, List<IFact>> targets = aliasUp(to);
        for (Map.Entry<Integer, List<IFact>> target : targets.entrySet()) {
            List<IFact> why = aliases;
            if (!target.getValue().isEmpty()) {
                why = new ArrayList<>(aliases);
                why.addAll(target.getValue());
            }
            out.add(new Hop(from, target.getKey(), stored(e), relation, why));
        }
    }

    /** r01: a chain of steps from {@code from} to {@code to}, each lying under the transitive {@code relation}. */
    private @Nullable Set<IFact> path(int from, int to, int relation) {
        if (from == to) {
            // A cycle: x p x holds when a chain leaves x and comes back.
            for (Hop hop : hops(from, relation)) {
                Set<IFact> back = hop.to() == from ? found() : path(hop.to(), from, relation);
                if (back == null) continue;
                Set<IFact> out = new LinkedHashSet<>(support(hop.stored(), hop.relation(), relation));
                out.addAll(hop.aliases());
                out.addAll(back);
                return out;
            }
            return null;
        }
        Map<Integer, Hop> via = new HashMap<>();
        Deque<Integer> queue = new ArrayDeque<>(List.of(from));
        Set<Integer> seen = new HashSet<>(List.of(from));
        while (!queue.isEmpty()) {
            for (Hop hop : hops(queue.poll(), relation)) {
                if (!seen.add(hop.to())) continue;
                via.put(hop.to(), hop);
                if (hop.to() == to) return chain(via, from, to, relation);
                queue.add(hop.to());
            }
        }
        return null;
    }

    private Set<IFact> chain(Map<Integer, Hop> via, int from, int to, int relation) {
        if (!proving.get()) return found();
        List<Hop> steps = new ArrayList<>();
        for (int at = to; at != from; at = via.get(at).from()) steps.addFirst(via.get(at));
        Set<IFact> out = new LinkedHashSet<>();
        for (Hop step : steps) {
            out.addAll(support(step.stored(), step.relation(), relation));
            out.addAll(step.aliases());
        }
        return out;
    }

    /**
     * r04 and typing-creates-types: {@code x has type u}, stated for {@code u} or for a subtype of it.
     */
    private @Nullable Set<IFact> typed(int x, int u) {
        for (Hop hop : hops(x, hasType)) {
            List<IFact> up = typeUp(hop.to(), u);
            if (up != null) {
                Set<IFact> out = support(hop.stored(), hop.relation(), hasType);
                if (proving.get()) out.addAll(hop.aliases());
                out.addAll(up);
                return out;
            }
        }
        for (Map.Entry<Integer, List<IFact>> entry : implicitTypes(x).entrySet()) {
            List<IFact> up = typeUp(entry.getKey(), u);
            if (up != null) {
                Set<IFact> out = new LinkedHashSet<>(entry.getValue());
                out.addAll(up);
                return out;
            }
        }
        return null;
    }

    private @Nullable List<IFact> typeUp(int type, int u) {
        if (type == u) return List.of();
        if (!isSub(type, u)) return null;
        return proving.get() ? hierarchyWhy(type, u) : List.of();
    }

    /** The types {@code x} has because of what it is rather than what anybody typed it with. */
    private Map<Integer, List<IFact>> implicitTypes(int x) {
        Map<Integer, List<IFact>> out = new TreeMap<>();
        int root = x >> 1;
        if (typeRoots.contains(root) && (x & 1) == 0) out.put(typeItem, typeReason(x));
        if (relationRoots.contains(root)) {
            out.put(relationItem, List.of());
            if (transitiveRoots.contains(root)) out.put(transitiveItem, List.of());
            if (identity.isSymmetric(root)) out.put(symmetricItem, List.of());
        }
        return out;
    }

    /** One triple that makes {@code x} a type: something typed with it, or a subtype edge at it. */
    private List<IFact> typeReason(int x) {
        if (!proving.get()) return List.of();
        int instance = hasInstance;
        int subtype = hasSubtype;
        for (int i = 0; i < degreeOf(x); i++) {
            int e = adjacency[x][i];
            int relation = edgeRelation[e];
            boolean typedWithX = edgeSubject[e] == x ? isSub(relation, instance) : isSub(inverse(relation), instance);
            if (typedWithX || isSub(relation, subtype) || isSub(inverse(relation), subtype)) {
                return List.of(edgeSource[e]);
            }
        }
        return List.of();
    }

    /**
     * r18: {@code x has tag u}, stated for {@code x} or for anything {@code x} is part of, at any
     * depth. A type is a tag too, since {@code has type} lies under {@code has tag}.
     */
    private @Nullable Set<IFact> tagged(int x, int u) {
        int tag = hasTag;
        int partOf = isPartOf;
        Map<Integer, Hop> via = new HashMap<>();
        Deque<Integer> queue = new ArrayDeque<>(List.of(x));
        Set<Integer> seen = new HashSet<>(List.of(x));
        while (!queue.isEmpty()) {
            int whole = queue.poll();
            Set<IFact> found = direct(whole, tag, u);
            if (found == null) found = typed(whole, u);
            if (found != null) {
                Set<IFact> out = whole == x ? new LinkedHashSet<>() : chain(via, x, whole, partOf);
                out.addAll(found);
                return out;
            }
            for (Hop hop : hops(whole, partOf)) {
                if (seen.add(hop.to())) {
                    via.put(hop.to(), hop);
                    queue.add(hop.to());
                }
            }
        }
        return null;
    }

    /** No normalisation: only what was written, and for {@code ONLY_TRANSITIVE} chains of it. */
    private @Nullable Set<IFact> literal(String x, String p, String y) {
        IFact written = literal.get(new Triple(x, p, y));
        if (written != null) return Set.of(written);
        IFact declared = literal.get(new Triple(p, HAS_TYPE, TRANSITIVE_RELATION));
        if (!rules.transitive || declared == null) return null;
        Map<String, IFact> via = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>(List.of(x));
        while (!queue.isEmpty()) {
            String at = queue.poll();
            for (IFact step : literalBySubject.getOrDefault(at, List.of())) {
                if (!step.relation().equals(p) || step.object().equals(x) || via.containsKey(step.object())) continue;
                via.put(step.object(), step);
                if (step.object().equals(y)) {
                    Set<IFact> out = new LinkedHashSet<>();
                    for (String node = y; !node.equals(x); node = via.get(node).subject()) out.add(via.get(node));
                    out.add(declared);
                    return out;
                }
                queue.add(step.object());
            }
        }
        return null;
    }

    boolean isSubRelation(String sub, String sup) {
        return isSub(oriented(sub), oriented(sup));
    }

    // ---- reachability along transitive relations ----------------------------------------------

    /**
     * For each transitive relation a question needed a chain for, by its index in
     * {@link #transitiveRelations}: built on first use, dropped when a step of it is stored.
     */
    private final Map<Integer, Reach> reaches = new java.util.concurrent.ConcurrentHashMap<>();

    private Reach reachFor(int index) {
        return reaches.computeIfAbsent(index, Reach::new);
    }

    /** Drops the indexes a stored triple adds a step to. */
    private void invalidateReaches(int relation) {
        if (reaches.isEmpty()) return;
        BitSet touched = (BitSet) transitiveAbove(relation).clone();
        touched.or(transitiveAbove(inverse(relation)));
        for (int i = touched.nextSetBit(0); i >= 0; i = touched.nextSetBit(i + 1)) reaches.remove(i);
    }

    /**
     * Which nodes a chain of one transitive relation's stated steps connects.
     *
     * <p>The steps' graph, condensed to its strongly connected components -- a cycle is one
     * component, whose members all reach each other -- and numbered by a depth-first walk of a
     * spanning forest: a component entered at {@code pre} and left at {@code post} has its forest
     * descendants strictly inside. Where every component has at most one parent, the forest is the
     * graph, and that containment is the whole answer: a part tree, a type tree. Otherwise
     * containment still proves reachability, and {@code low} -- the smallest {@code post} of anything
     * below -- disproves most of the rest: a node can only be below if its {@code post} lies within
     * {@code [low, post]} (the GRAIL labelling). What neither settles is searched, pruned by the
     * same test.
     */
    private final class Reach {
        private final Map<Integer, Integer> local = new HashMap<>();
        private int[] componentOf = new int[0];
        private int[][] members = new int[0][];
        private int[][] children = new int[0][];
        private int[] pre = new int[0];
        private int[] post = new int[0];
        private int[] low = new int[0];
        private int[] atSlot = new int[0];
        private boolean tree = true;
        /** The nodes with a step to themselves. */
        private final BitSet looping = new BitSet();

        Reach(int index) {
            // The relation's steps, as local edges.
            int[] from = new int[16];
            int[] to = new int[16];
            int steps = 0;
            List<Integer> nodesOf = new ArrayList<>();
            for (int e = 0; e < edgeCount; e++) {
                boolean forwards = transitiveAbove(edgeRelation[e]).get(index);
                boolean backwards = transitiveAbove(inverse(edgeRelation[e])).get(index);
                if (!forwards && !backwards) continue;
                // A statement about an alias holds for what it is an alias of, at either end.
                for (int s : aliasUp(edgeSubject[e]).keySet()) {
                    for (int o : aliasUp(edgeObject[e]).keySet()) {
                        int ls = localOf(s, nodesOf);
                        int lo = localOf(o, nodesOf);
                        if (s == o) {
                            looping.set(ls);
                            continue;
                        }
                        if (steps + 2 > from.length) {
                            from = Arrays.copyOf(from, from.length * 2);
                            to = Arrays.copyOf(to, to.length * 2);
                        }
                        if (forwards) {
                            from[steps] = ls;
                            to[steps++] = lo;
                        }
                        if (backwards) {
                            from[steps] = lo;
                            to[steps++] = ls;
                        }
                    }
                }
            }
            int n = nodesOf.size();
            // Compressed rows of the local graph.
            int[] offset = new int[n + 1];
            for (int k = 0; k < steps; k++) offset[from[k] + 1]++;
            for (int v = 0; v < n; v++) offset[v + 1] += offset[v];
            int[] target = new int[steps];
            int[] fill = Arrays.copyOf(offset, n);
            for (int k = 0; k < steps; k++) target[fill[from[k]]++] = to[k];

            // Tarjan, iteratively: components come out children first.
            componentOf = new int[n];
            Arrays.fill(componentOf, -1);
            int[] number = new int[n];
            int[] lowLink = new int[n];
            boolean[] onStack = new boolean[n];
            Arrays.fill(number, -1);
            int[] stack = new int[n];
            int stackSize = 0;
            int[] callNode = new int[n];
            int[] callEdge = new int[n];
            int counter = 0;
            List<int[]> components = new ArrayList<>();
            for (int start = 0; start < n; start++) {
                if (number[start] >= 0) continue;
                int depth = 0;
                callNode[0] = start;
                callEdge[0] = offset[start];
                number[start] = lowLink[start] = counter++;
                stack[stackSize++] = start;
                onStack[start] = true;
                while (depth >= 0) {
                    int v = callNode[depth];
                    if (callEdge[depth] < offset[v + 1]) {
                        int w = target[callEdge[depth]++];
                        if (number[w] < 0) {
                            number[w] = lowLink[w] = counter++;
                            stack[stackSize++] = w;
                            onStack[w] = true;
                            depth++;
                            callNode[depth] = w;
                            callEdge[depth] = offset[w];
                        } else if (onStack[w]) {
                            lowLink[v] = Math.min(lowLink[v], number[w]);
                        }
                        continue;
                    }
                    if (lowLink[v] == number[v]) {
                        int size = 0;
                        while (stack[stackSize - 1 - size] != v) size++;
                        size++;
                        int[] component = new int[size];
                        for (int k = 0; k < size; k++) {
                            int member = stack[--stackSize];
                            onStack[member] = false;
                            component[k] = member;
                            componentOf[member] = components.size();
                        }
                        components.add(component);
                    }
                    depth--;
                    if (depth >= 0) lowLink[callNode[depth]] = Math.min(lowLink[callNode[depth]], lowLink[v]);
                }
            }
            int c = components.size();
            members = new int[c][];
            for (int k = 0; k < c; k++) {
                int[] component = components.get(k);
                members[k] = new int[component.length];
                for (int m = 0; m < component.length; m++) members[k][m] = nodesOf.get(component[m]);
            }
            // The condensation: each component's distinct children, and how many parents each has.
            children = new int[c][];
            int[] parents = new int[c];
            for (int k = 0; k < c; k++) {
                Set<Integer> distinct = new LinkedHashSet<>();
                for (int member : components.get(k)) {
                    for (int j = offset[member]; j < offset[member + 1]; j++) {
                        int child = componentOf[target[j]];
                        if (child != k) distinct.add(child);
                    }
                }
                children[k] = new int[distinct.size()];
                int m = 0;
                for (int child : distinct) {
                    children[k][m++] = child;
                    parents[child]++;
                }
            }
            for (int k = 0; k < c; k++) if (parents[k] > 1) tree = false;
            // A depth-first walk of a spanning forest, from the components nothing points to.
            pre = new int[c];
            post = new int[c];
            atSlot = new int[2 * c];
            Arrays.fill(pre, -1);
            int slot = 0;
            int[] walkNode = new int[c];
            int[] walkChild = new int[c];
            for (int root = 0; root < c; root++) {
                if (parents[root] != 0 || pre[root] >= 0) continue;
                int depth = 0;
                walkNode[0] = root;
                walkChild[0] = 0;
                atSlot[slot] = root;
                pre[root] = slot++;
                while (depth >= 0) {
                    int v = walkNode[depth];
                    if (walkChild[depth] < children[v].length) {
                        int child = children[v][walkChild[depth]++];
                        if (pre[child] >= 0) continue;
                        depth++;
                        walkNode[depth] = child;
                        walkChild[depth] = 0;
                        atSlot[slot] = child;
                        pre[child] = slot++;
                        continue;
                    }
                    atSlot[slot] = -1;
                    post[v] = slot++;
                    depth--;
                }
            }
            // Children came out of Tarjan first, so each low is ready when its parents need it.
            low = new int[c];
            for (int k = 0; k < c; k++) {
                low[k] = post[k];
                for (int child : children[k]) low[k] = Math.min(low[k], low[child]);
            }
        }

        private int localOf(int node, List<Integer> nodesOf) {
            Integer known = local.get(node);
            if (known != null) return known;
            local.put(node, nodesOf.size());
            nodesOf.add(node);
            return nodesOf.size() - 1;
        }

        /** Whether a chain of steps leads from {@code x} to {@code y} -- back to itself, for a cycle. */
        boolean reachable(int x, int y) {
            Integer lx = local.get(x);
            Integer ly = local.get(y);
            if (lx == null || ly == null) return false;
            int cx = componentOf[lx];
            int cy = componentOf[ly];
            if (cx == cy) return members[cx].length > 1 || looping.get(lx);
            if (pre[cx] < pre[cy] && post[cy] < post[cx]) return true;
            if (tree) return false;
            int goal = post[cy];
            if (goal < low[cx] || goal > post[cx]) return false;
            BitSet seen = new BitSet();
            Deque<Integer> queue = new ArrayDeque<>(List.of(cx));
            seen.set(cx);
            while (!queue.isEmpty()) {
                for (int child : children[queue.poll()]) {
                    if (child == cy) return true;
                    if (seen.get(child) || goal < low[child] || goal > post[child]) continue;
                    if (pre[child] < pre[cy] && post[cy] < post[child]) return true;
                    seen.set(child);
                    queue.add(child);
                }
            }
            return false;
        }

        /** How many {@link #descendants} has: exact on a forest; elsewhere its component, and a guess per child. */
        int size(int x) {
            Integer lx = local.get(x);
            if (lx == null) return 0;
            int cx = componentOf[lx];
            if (tree) {
                int out = members[cx].length;
                for (int slot = pre[cx] + 1; slot < post[cx]; slot++) if (atSlot[slot] >= 0) out += members[atSlot[slot]].length;
                return out;
            }
            return members[cx].length + 4 * children[cx].length;
        }

        /** Every node a chain of steps leads to from {@code x}: {@code x} itself only on a cycle. */
        Set<Integer> descendants(int x) {
            Set<Integer> out = new LinkedHashSet<>();
            Integer lx = local.get(x);
            if (lx == null) return out;
            int cx = componentOf[lx];
            if (members[cx].length > 1) for (int member : members[cx]) out.add(member);
            if (looping.get(lx)) out.add(x);
            if (tree) {
                for (int slot = pre[cx] + 1; slot < post[cx]; slot++) {
                    int c = atSlot[slot];
                    if (c >= 0) for (int member : members[c]) out.add(member);
                }
                return out;
            }
            BitSet seen = new BitSet();
            Deque<Integer> queue = new ArrayDeque<>(List.of(cx));
            seen.set(cx);
            while (!queue.isEmpty()) {
                for (int child : children[queue.poll()]) {
                    if (seen.get(child)) continue;
                    seen.set(child);
                    queue.add(child);
                    for (int member : members[child]) out.add(member);
                }
            }
            return out;
        }
    }

    // ---- patterns ------------------------------------------------------------------------------

    /**
     * Roughly how many triples {@link #match} would list, without listing them, or -1 when that
     * cannot be said cheaply. For the query layer's join order: a pattern with one end bound is
     * counted from the stored triples at that end, plus what type inheritance, tags and chains of a
     * transitive relation add, each by a bound that costs no walk. Both ends bound is a test, as
     * cheap as {@link #holds}: 1. Neither end bound is a scan: -1.
     */
    long estimate(@Nullable String s, @Nullable String p, @Nullable String o) {
        if (!rules.normalise) return -1;
        if (s != null && o != null) return 1;
        if (s == null && o == null) return -1;
        int relation = p == null ? related : oriented(p);
        return s != null ? estimateObjects(oriented(s), relation) : estimateObjects(oriented(o), inverse(relation));
    }

    /** About as many as {@link #objects}{@code (x, p)} holds. */
    private long estimateObjects(int x, int p) {
        long out = 0;
        long types = 0;
        long tags = 0;
        boolean typeWanted = isSub(hasType, p) || isSub(hasTag, p);
        for (int at : aliasDown(x).keySet()) {
            Groups groups = groupsOf(at);
            if (groups != null) {
                for (int k = 0; k < groups.relations().length; k++) {
                    int forwards = groups.relations()[k];
                    int[] edges = groups.edges()[k];
                    if (isSub(forwards, p)) out += edges.length;
                    if (isSub(forwards, hasTag)) tags += edges.length;
                    if (typeWanted && isSub(forwards, hasType)) {
                        for (int e : edges) {
                            int a = at(otherEnd(at, e));
                            types += 1 + (a >= 0 ? above[a].cardinality() : 0);
                        }
                    }
                }
                continue;
            }
            for (int i = 0; i < degreeOf(at); i++) {
                int e = adjacency[at][i];
                int stated = edgeRelation[e];
                int forwards = edgeSubject[e] == at ? stated : inverse(stated);
                if (isSub(forwards, p)) out++;
                if (typeWanted && isSub(forwards, hasType)) {
                    int a = at(otherEnd(at, e));
                    types += 1 + (a >= 0 ? above[a].cardinality() : 0);
                }
                if (isSub(forwards, hasTag)) tags++;
            }
        }
        if (isSub(sameAs, p)) out++;
        if (relationRoots.contains(x >> 1) && isSub(hasInverse, p)) out++;
        int node = at(x);
        if (node >= 0 && (isSub(hasSubtype, p) || isSub(inverse(hasSubtype), p))) out += ups.get(node).size();
        if (isSub(hasType, p)) out += types;
        if (isSub(hasTag, p)) out += tags + types;
        boolean instances = isSub(hasInstance, p);
        boolean tagged = isSub(isTagOf, p);
        if (instances || tagged) {
            // Everything typed with x or below it: the stored triples at every type below, a bound.
            long below = 0;
            int a = at(x);
            if (a >= 0) {
                for (int i = 0; i < nodes.size(); i++) if (i != a && above[i].get(a)) below += degreeOf(nodes.get(i));
            }
            if (x == typeItem) below += typeRoots.size();
            if (x == relationItem || x == symmetricItem) below += relationIds.size();
            if (x == transitiveItem) below += transitiveRelations.size();
            // The parts below each holder are not counted: part trees are shallow next to type extents.
            if (instances) out += below;
            if (tagged) out += below;
        }
        if (rules.transitive) {
            BitSet under = transitiveUnder(p);
            if (!under.isEmpty()) {
                BitSet stated = steps(x, true);
                long direct = out;
                for (int i = under.nextSetBit(0); i >= 0; i = under.nextSetBit(i + 1)) {
                    // A chain over derived steps would have to be walked to be counted: a guess.
                    if (derivedStepsUnder(transitiveRelations.get(i))) out += direct;
                    else if (stated.get(i)) out += reachFor(i).size(x);
                }
            }
        }
        return out;
    }

    /**
     * Every entailed triple matching a pattern, in canonical names; a null position matches anything.
     *
     * <p>Enumerated from the bound end: each way {@link #derive} can conclude {@code x p y} has a
     * counterpart here that lists the {@code y}s -- stored edges, the inverse of a relation, type
     * ancestors and descendants, tags of wholes and the parts below a tagged thing, and chains.
     * The oracle instead tries every node connected to {@code x}.
     */
    List<Triple> match(@Nullable String s, @Nullable String p, @Nullable String o) {
        if (!rules.normalise) return literalMatch(s, p, o);
        proving.set(false);
        try {
            return matchWithoutProofs(s, p, o);
        } finally {
            proving.set(true);
        }
    }

    private List<Triple> matchWithoutProofs(@Nullable String s, @Nullable String p, @Nullable String o) {
        Set<Triple> out = new LinkedHashSet<>();
        if (p != null) {
            int relation = oriented(p);
            if (s != null) {
                int x = oriented(s);
                if (o == null) {
                    for (int y : objects(x, relation)) out.add(triple(x, relation, y));
                } else {
                    // A test, which the query layer asks once per solution: what holds answers,
                    // not every object of x listed to find one -- x may be a hub, and listing
                    // what is related to it took a hundred times as long.
                    int y = oriented(o);
                    if (derive(x, relation, y, true) != null) out.add(triple(x, relation, y));
                }
            } else if (o != null) {
                int y = oriented(o);
                for (int x : objects(y, inverse(relation))) out.add(triple(x, relation, y));
            } else {
                for (int x : allNodes()) {
                    for (int y : objects(x, relation)) out.add(triple(x, relation, y));
                }
            }
            return List.copyOf(out);
        }
        if (s != null && o != null) {
            int x = oriented(s);
            int y = oriented(o);
            for (int relation : relationsBetween(x, y)) out.add(triple(x, relation, y));
        } else if (s != null) {
            int x = oriented(s);
            for (int y : neighbours(x)) {
                for (int relation : relationsBetween(x, y)) out.add(triple(x, relation, y));
            }
        } else if (o != null) {
            int y = oriented(o);
            for (int x : neighbours(y)) {
                for (int relation : relationsBetween(x, y)) out.add(triple(x, relation, y));
            }
        } else {
            for (int x : allNodes()) {
                for (int y : neighbours(x)) {
                    for (int relation : relationsBetween(x, y)) out.add(triple(x, relation, y));
                }
            }
        }
        return List.copyOf(out);
    }

    private Triple triple(int x, int relation, int y) {
        return new Triple(name(x), name(relation), name(y));
    }

    /** Every {@code y} with {@code x p y} entailed. */
    private Set<Integer> objects(int x, int p) {
        return objects(x, p, true);
    }

    /** @param chains whether to follow chains of transitive relations, or only single steps */
    private Set<Integer> objects(int x, int p, boolean chains) {
        Set<Integer> out = new LinkedHashSet<>();
        for (Hop hop : hops(x, p)) out.add(hop.to());
        if (isSub(sameAs, p)) out.add(x);
        if (relationRoots.contains(x >> 1) && isSub(hasInverse, p)) out.add(inverse(x));
        if (isSub(hasType, p)) out.addAll(typesOf(x));
        if (isSub(hasInstance, p)) out.addAll(instancesOf(x));
        if (isSub(hasTag, p)) out.addAll(tagsOf(x));
        if (isSub(isTagOf, p)) out.addAll(taggedWith(x));
        if (isSub(hasSubtype, p)) out.addAll(subtypesThrough(x, rules.transitive && chains));
        if (isSub(inverse(hasSubtype), p)) out.addAll(supertypesThrough(x, rules.transitive && chains));
        if (rules.transitive && chains) {
            BitSet under = transitiveUnder(p);
            if (!under.isEmpty()) {
                BitSet stated = steps(x, true);
                for (int i = under.nextSetBit(0); i >= 0; i = under.nextSetBit(i + 1)) {
                    int transitive = transitiveRelations.get(i);
                    if (derivedStepsUnder(transitive)) out.addAll(derivedReach(x, transitive));
                    else if (stated.get(i)) out.addAll(reachFor(i).descendants(x));
                }
            }
        }
        return out;
    }

    /**
     * Everything at the other end of some entailed triple from {@code x}: whatever is related to it
     * either way round, since every relation but {@code has inverse} lies under {@code related},
     * and its inverse.
     */
    private Set<Integer> neighbours(int x) {
        Set<Integer> out = objects(x, related);
        out.addAll(objects(x, hasInverse));
        return out;
    }

    /** Every relation that holds from {@code x} to {@code y}, each way of concluding one worked out once. */
    private List<Integer> relationsBetween(int x, int y) {
        List<Integer> stated = new ArrayList<>();
        // r06, r08: what is stated between aliases of the two is stated between them.
        for (int subject : aliasDown(x).keySet()) {
            for (int object : aliasDown(y).keySet()) {
                for (Stored stored : between(subject, object)) {
                    if (stored.subject() == subject && stored.object() == object) stated.add(stored.relation());
                    if (stored.subject() == object && stored.object() == subject) stated.add(inverse(stored.relation()));
                }
            }
        }
        if (x == y) stated.add(sameAs);
        boolean inverseOf = relationRoots.contains(x >> 1) && y == inverse(x);
        boolean typed = typed(x, y) != null;
        boolean instance = typed(y, x) != null;
        boolean tagged = tagged(x, y) != null;
        boolean tagOf = tagged(y, x) != null;
        boolean subtypeOf = subtypeStep(y, x, rules.transitive) != null;
        boolean supertypeOf = subtypeStep(x, y, rules.transitive) != null;
        List<Integer> chained = new ArrayList<>();
        if (rules.transitive) {
            BitSet candidates = chainsWorthSearching(x, y, related);
            candidates.or(chainsWorthSearching(x, y, hasInverse));
            for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
                int transitive = transitiveRelations.get(i);
                boolean reached = derivedStepsUnder(transitive) ? derivedPath(x, y, transitive) != null : reachFor(i).reachable(x, y);
                if (reached) chained.add(transitive);
            }
        }
        List<Integer> out = new ArrayList<>();
        for (int relation : relationIds) {
            boolean holds = inverseOf && isSub(hasInverse, relation)
                    || typed && isSub(hasType, relation)
                    || instance && isSub(hasInstance, relation)
                    || tagged && isSub(hasTag, relation)
                    || tagOf && isSub(isTagOf, relation)
                    || subtypeOf && isSub(hasSubtype, relation)
                    || supertypeOf && isSub(inverse(hasSubtype), relation);
            for (int i = 0; !holds && i < stated.size(); i++) holds = isSub(stated.get(i), relation);
            for (int i = 0; !holds && i < chained.size(); i++) holds = isSub(chained.get(i), relation);
            if (holds) out.add(relation);
        }
        return out;
    }

    /** r04 forwards: every type {@code x} has, and everything above each. */
    private Set<Integer> typesOf(int x) {
        Set<Integer> direct = new LinkedHashSet<>();
        for (Hop hop : hops(x, hasType)) direct.add(hop.to());
        direct.addAll(implicitTypes(x).keySet());
        Set<Integer> out = new LinkedHashSet<>();
        for (int type : direct) {
            out.add(type);
            int at = at(type);
            if (at < 0) continue;
            for (int j = above[at].nextSetBit(0); j >= 0; j = above[at].nextSetBit(j + 1)) out.add(nodes.get(j));
        }
        return out;
    }

    /** r04 backwards: everything typed with {@code u} or with anything below it. */
    private Set<Integer> instancesOf(int u) {
        Set<Integer> below = new LinkedHashSet<>(List.of(u));
        int at = at(u);
        if (at >= 0) {
            for (int i = 0; i < nodes.size(); i++) if (above[i].get(at)) below.add(nodes.get(i));
        }
        Set<Integer> out = new LinkedHashSet<>();
        for (int type : below) {
            for (Hop hop : hops(type, hasInstance)) out.add(hop.to());
            if (type == typeItem) for (int root : typeRoots) out.add(root * 2);
            if (type == relationItem) out.addAll(relationIds);
            if (type == transitiveItem) out.addAll(transitiveRelations);
            if (type == symmetricItem) {
                for (int relation : relationIds) if (identity.isSymmetric(relation >> 1)) out.add(relation);
            }
        }
        return out;
    }

    /** r18 forwards: the tags of {@code x} and of everything it is part of, types included. */
    private Set<Integer> tagsOf(int x) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int whole : closure(x, isPartOf)) {
            for (Hop hop : hops(whole, hasTag)) out.add(hop.to());
            out.addAll(typesOf(whole));
        }
        return out;
    }

    /** r18 backwards: whatever is tagged or typed with {@code tag}, and every part below each. */
    private Set<Integer> taggedWith(int tag) {
        Set<Integer> holders = new LinkedHashSet<>();
        for (Hop hop : hops(tag, isTagOf)) holders.add(hop.to());
        holders.addAll(instancesOf(tag));
        Set<Integer> out = new LinkedHashSet<>();
        for (int holder : holders) out.addAll(closure(holder, hasPart));
        return out;
    }

    /** {@code from} and everything reached by steps of {@code relation}, at any depth. */
    private Set<Integer> closure(int from, int relation) {
        Set<Integer> seen = new LinkedHashSet<>(List.of(from));
        Deque<Integer> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            for (Hop hop : hops(queue.poll(), relation)) {
                if (seen.add(hop.to())) queue.add(hop.to());
            }
        }
        return seen;
    }

    /** The items the engine types things with, which nothing needs to be connected to. */
    private Set<Integer> virtualItems() {
        return Set.of(typeItem, relationItem, symmetricItem, transitiveItem);
    }

    private Set<Integer> allNodes() {
        Set<Integer> out = new LinkedHashSet<>();
        for (int node = 0; node < degree.length; node++) if (degree[node] > 0) out.add(node);
        out.addAll(virtualItems());
        out.addAll(relationIds);
        for (int root : typeRoots) out.add(root * 2);
        return out;
    }

    private List<Triple> literalMatch(@Nullable String s, @Nullable String p, @Nullable String o) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> predicates = new LinkedHashSet<>();
        for (Triple triple : literal.keySet()) {
            names.add(triple.subject());
            names.add(triple.object());
            predicates.add(triple.relation());
        }
        Set<Triple> out = new LinkedHashSet<>();
        for (String x : s != null ? Set.of(s) : names) {
            for (String y : o != null ? Set.of(o) : reachable(x)) {
                for (String relation : p != null ? Set.of(p) : predicates) {
                    if (literal(x, relation, y) != null) out.add(new Triple(x, relation, y));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Every name a chain of stated triples leads to from {@code x}, whatever their relations: the
     * only places a literal answer can end. Trying every name instead made a {@code p+} query
     * under {@code NONE} take minutes.
     */
    private Set<String> reachable(String x) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(x));
        while (!queue.isEmpty()) {
            for (IFact step : literalBySubject.getOrDefault(queue.poll(), List.of())) {
                if (out.add(step.object())) queue.add(step.object());
            }
        }
        return out;
    }
}
