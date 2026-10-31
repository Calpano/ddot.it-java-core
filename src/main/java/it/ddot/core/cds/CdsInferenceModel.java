package it.ddot.core.cds;

import it.ddot.core.store.IFact;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * One compiled state of {@link CdsInferenceEngine}: everything worked out from one set of triples.
 *
 * <p>Built in two phases, as {@code doc/cds-inf.adoc} describes. The meta fixpoint settles what the
 * relations are -- identity, inverses, the hierarchy, which are transitive and symmetric -- and
 * compiles the hierarchy into one ancestor bitset per node. The data pass then normalises every
 * triple once and indexes it. Nothing r05 would derive is stored: a query asks the bitsets whether a
 * stored predicate lies under the one asked for.
 *
 * <h2>Oriented relations</h2>
 * A relation is an {@code int}: its {@link Identity} class times two, plus 1 when it reads the class
 * backwards. {@code has type} and {@code has instance} are one class at the two parities; a
 * symmetric relation only ever has parity 0. The hierarchy is over these, so {@code has type} and
 * {@code has instance} each have their own place in it, as r17 requires.
 */
final class CdsInferenceModel {

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

    /**
     * One step along a stored triple, in whichever direction satisfied the relation -- possibly
     * stated about an alias of {@code from}, or landing on what {@code to} is an alias of, in which
     * case {@code aliases} are the alias statements it went through.
     */
    private record Hop(int from, int to, Stored stored, int relation, List<IFact> aliases) {}

    /** {@code alias is alias of resolved}, as said by {@code why}. */
    private record AliasEdge(String alias, String resolved, IFact why) {}

    private record Member(String name, int parity) {}

    private final RuleSet rules;
    private final List<IFact> all;
    private final Identity identity = new Identity();

    /** Every alias statement found, by name: what is said about the alias holds for what it is an alias of. */
    private final Set<AliasEdge> aliasEdges = new LinkedHashSet<>();
    /** The same, by the alias's name. */
    private final Map<String, Set<String>> aliasTargets = new HashMap<>();
    private final Set<String> preferredNames = new HashSet<>();
    private final Set<String> relationNames = new HashSet<>();
    private final Set<String> transitiveNames = new HashSet<>();
    private List<NameEdge> subtypeEdges = List.of();

    private final Map<Integer, Integer> index = new HashMap<>();
    private final List<Integer> nodes = new ArrayList<>();
    private final List<List<Up>> ups = new ArrayList<>();
    private BitSet[] above = new BitSet[0];

    private Set<Integer> relationRoots = Set.of();
    private Set<Integer> transitiveRoots = Set.of();
    private final Set<Integer> typeRoots = new HashSet<>();
    private final Map<Integer, List<Member>> members = new HashMap<>();
    private final Map<Integer, Integer> canonicalParity = new HashMap<>();

    private final Map<Long, List<Stored>> byPair = new HashMap<>();
    private final Map<Integer, List<Stored>> byNode = new HashMap<>();
    private final Map<Triple, IFact> literal = new HashMap<>();
    private final Map<String, List<IFact>> literalBySubject = new HashMap<>();

    CdsInferenceModel(RuleSet rules, List<IFact> all) {
        this.rules = rules;
        this.all = all;
        for (IFact fact : all) literal.putIfAbsent(Triple.of(fact), fact);
        for (IFact triple : all) literalBySubject.computeIfAbsent(triple.subject(), k -> new ArrayList<>()).add(triple);
        runMetaFixpoint();
        collectMembers();
        indexData();
    }

    // ---- meta fixpoint -------------------------------------------------------------------------

    /**
     * Settles the meta level, reading only the triples whose predicate is a meta relation.
     *
     * <p>Semi-naive over predicates: a round classifies every distinct predicate by what it lies
     * under, applies the triples of the meta ones, and rebuilds the hierarchy. A predicate that turns
     * out to lie under {@code has subtype} is a meta predicate from the next round on. All the sets
     * only grow, so the loop ends; cycles are harmless.
     */
    private void runMetaFixpoint() {
        Map<String, List<IFact>> byPredicate = new LinkedHashMap<>();
        for (IFact triple : all) byPredicate.computeIfAbsent(triple.relation(), k -> new ArrayList<>()).add(triple);
        for (Pair pair : PAIRS) preferredNames.add(pair.relation());
        boolean changed = true;
        while (changed) {
            changed = false;
            // The predicates, with every name they are an alias of: r07 makes a triple with an
            // alias predicate a triple with the predicate it is an alias of.
            aliasUpCache.clear();
            Set<String> predicates = new LinkedHashSet<>(byPredicate.keySet());
            for (String predicate : byPredicate.keySet()) predicates.addAll(aliasUp(predicate));
            Map<String, EnumSet<Kind>> kinds = classify(predicates);
            List<NameEdge> edges = new ArrayList<>();
            Set<String> edgePairs = new HashSet<>();
            for (Map.Entry<String, List<IFact>> entry : byPredicate.entrySet()) {
                Set<String> variants = aliasUp(entry.getKey());
                if (variants.stream().noneMatch(kinds::containsKey)) continue;
                for (IFact triple : entry.getValue()) {
                    // r06–r08 on a meta statement: every alias variant of its names says it too.
                    for (String subject : aliasUp(triple.subject())) {
                        for (String predicate : variants) {
                            EnumSet<Kind> applying = kinds.get(predicate);
                            if (applying == null) continue;
                            // An alias statement's object needs no variants: what the object is an
                            // alias of, the alias reaches by walking on (r08 would only write out the
                            // walk, every chain as every pair).
                            Set<String> objects = applying.contains(Kind.ALIAS) || applying.contains(Kind.HAS_ALIAS)
                                    ? Set.of(triple.object()) : aliasUp(triple.object());
                            for (String object : objects) {
                                for (Kind kind : applying) {
                                    if ((kind == Kind.SUBTYPE || kind == Kind.SUPERTYPE)
                                            && !edgePairs.add(kind == Kind.SUBTYPE ? object + "\u0000" + subject : subject + "\u0000" + object)) {
                                        continue;
                                    }
                                    changed |= apply(kind, subject, object, triple, edges);
                                }
                            }
                        }
                    }
                }
            }
            subtypeEdges = edges;
            changed |= buildHierarchy(predicates);
        }
    }

    /** {@code name}, and every name it is an alias of, at any depth. */
    private Set<String> aliasUp(String name) {
        Set<String> targets = aliasTargets.get(name);
        if (targets == null) return Set.of(name);
        Set<String> cached = aliasUpCache.get(name);
        if (cached != null) return cached;
        Set<String> out = new LinkedHashSet<>(List.of(name));
        Deque<String> queue = new ArrayDeque<>(List.of(name));
        while (!queue.isEmpty()) {
            for (String next : aliasTargets.getOrDefault(queue.poll(), Set.of())) {
                if (out.add(next)) queue.add(next);
            }
        }
        aliasUpCache.put(name, out);
        return out;
    }

    /**
     * Each name's alias variants, remembered for a round of the meta fixpoint and for questions
     * after it. An alias found during a round makes it stale until the next round; the rounds go
     * on until nothing changes, so stale is never final. Walking the aliases afresh for every name
     * of every meta statement was most of a compile on a graph with many aliases.
     */
    private final Map<String, Set<String>> aliasUpCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One edge per pair of names. Applied to every alias variant of a meta statement, r06–r08 say
     * the same alias again and again, each time from another statement; one of them is proof enough.
     */
    private boolean alias(String alias, String resolved, IFact why) {
        if (!aliasTargets.computeIfAbsent(alias, k -> new LinkedHashSet<>()).add(resolved)) return false;
        aliasUpCache.clear();
        aliasEdges.add(new AliasEdge(alias, resolved, why));
        return true;
    }

    private Map<String, EnumSet<Kind>> classify(Set<String> predicates) {
        Map<String, EnumSet<Kind>> out = new LinkedHashMap<>();
        for (String predicate : predicates) {
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
            if (!kinds.isEmpty()) out.put(predicate, kinds);
        }
        return out;
    }

    /** One meta statement, about {@code subject} and {@code object} -- the triple's own names, or alias variants of them. */
    private boolean apply(Kind kind, String subject, String object, IFact triple, List<NameEdge> edges) {
        List<IFact> why = List.of(triple);
        return switch (kind) {
            case SAME -> identity.union(subject, object, 0, why);
            // An alias is not a second name of the same concept: it hands what is said about it on.
            case ALIAS -> alias(subject, object, triple);
            case HAS_ALIAS -> alias(object, subject, triple);
            case INVERSE -> identity.union(subject, object, 1, why);
            case SUBTYPE -> {
                edges.add(new NameEdge(object, subject, triple));
                yield preferredNames.add(object);
            }
            case SUPERTYPE -> {
                edges.add(new NameEdge(subject, object, triple));
                yield preferredNames.add(subject);
            }
            case TYPE -> typed(subject, object, triple);
            case INSTANCE -> typed(object, subject, triple);
        };
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

        index.clear();
        nodes.clear();
        ups.clear();
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
                Integer at = index.get(relation);
                // Placed means a has-subtype step: an alias lies under what it aliases, but is placed by no triple.
                if (at == null || ups.get(at).stream().noneMatch(Up::subtype)) addUp(relation, related, List.of(), true);
            }
        }

        above = new BitSet[nodes.size()];
        for (int start = 0; start < nodes.size(); start++) {
            BitSet reached = new BitSet();
            reached.set(start);
            Deque<Integer> queue = new ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                for (Up up : ups.get(queue.poll())) {
                    int to = index.get(up.to());
                    if (!reached.get(to)) {
                        reached.set(to);
                        queue.add(to);
                    }
                }
            }
            above[start] = reached;
        }

        // A has-subtype cycle collapses to same as.
        boolean changed = false;
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = above[i].nextSetBit(0); j >= 0; j = above[i].nextSetBit(j + 1)) {
                if (j == i || !above[j].get(i)) continue;
                int a = nodes.get(i);
                int b = nodes.get(j);
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

    private int node(int relation) {
        Integer at = index.get(relation);
        if (at != null) return at;
        index.put(relation, nodes.size());
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

    /**
     * Whether {@code sup has subtype sub} holds through the compiled hierarchy, and why: the steps a
     * walk up from {@code sub} takes along {@code has subtype} steps -- stated ones, r17's, and the
     * root above a relation nobody placed. One step only without chains (r01 off). The stored
     * triples answer the rest; this is what they cannot, since r17 stores nothing.
     */
    private @Nullable Set<IFact> subtypeStep(int sub, int sup, boolean chains) {
        if (sub == sup) return null;
        Integer start = index.get(sub);
        Integer goal = index.get(sup);
        if (start == null || goal == null || !above[start].get(goal)) return null;
        Map<Integer, Integer> previous = new HashMap<>();
        Map<Integer, List<IFact>> via = new HashMap<>();
        previous.put(start, start);
        Deque<Integer> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            int at = queue.poll();
            for (Up up : ups.get(at)) {
                if (!up.subtype()) continue;
                int to = index.get(up.to());
                if (previous.containsKey(to)) continue;
                previous.put(to, at);
                via.put(to, up.why());
                if (to == goal) {
                    Set<IFact> out = new LinkedHashSet<>();
                    for (int step = goal; step != start; step = previous.get(step)) out.addAll(via.get(step));
                    return out;
                }
                if (chains) queue.add(to);
            }
        }
        return null;
    }

    // ---- hierarchy -----------------------------------------------------------------------------

    int oriented(String name) {
        Identity.Ref ref = identity.ref(name);
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
        Integer from = index.get(sub);
        Integer to = index.get(sup);
        return from != null && to != null && above[from].get(to);
    }

    /** The {@code has subtype} triples on one path from {@code sub} up to {@code sup}. */
    private List<IFact> hierarchyWhy(int sub, int sup) {
        if (sub == sup) return List.of();
        Integer start = index.get(sub);
        Integer goal = index.get(sup);
        if (start == null || goal == null) return List.of();
        Map<Integer, Integer> previous = new HashMap<>();
        Map<Integer, List<IFact>> via = new HashMap<>();
        previous.put(start, start);
        Deque<Integer> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            int at = queue.poll();
            for (Up up : ups.get(at)) {
                int to = index.get(up.to());
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

    // ---- names ---------------------------------------------------------------------------------

    private void collectMembers() {
        for (int id = 0; id < identity.size(); id++) {
            Identity.Ref ref = identity.find(id);
            members.computeIfAbsent(ref.root(), k -> new ArrayList<>()).add(new Member(identity.name(id), ref.parity()));
        }
        for (List<Member> list : members.values()) list.sort(Comparator.comparing(Member::name));
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
        List<Member> list = members.getOrDefault(root, List.of(new Member(identity.name(root), 0)));
        for (Member member : list) {
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
            // A name compiled into no class -- one first seen after compiling -- is its own member.
            List<Member> list = members.getOrDefault(r, List.of(new Member(identity.name(r), 0)));
            for (Member member : list) {
                if (preferredNames.contains(member.name())) return member.parity();
            }
            boolean forwards = list.stream().anyMatch(m -> m.parity() == 0);
            boolean backwards = list.stream().anyMatch(m -> m.parity() == 1);
            if (forwards != backwards) return forwards ? 0 : 1;
            return name(r * 2).compareTo(name(r * 2 + 1)) <= 0 ? 0 : 1;
        });
    }

    // ---- data pass -----------------------------------------------------------------------------

    private void indexData() {
        for (IFact triple : all) {
            Stored stored = normalise(triple);
            byPair.computeIfAbsent(key(stored.subject(), stored.object()), k -> new ArrayList<>()).add(stored);
            byNode.computeIfAbsent(stored.subject(), k -> new ArrayList<>()).add(stored);
            if (stored.object() != stored.subject()) {
                byNode.computeIfAbsent(stored.object(), k -> new ArrayList<>()).add(stored);
            }
        }
        // Typing and subtyping create types; for relations, the axioms and relations() say so.
        int instance = oriented(HAS_INSTANCE);
        for (List<Stored> list : byPair.values()) {
            for (Stored stored : list) {
                if (isSub(stored.relation(), instance)) typeRoots.add(stored.subject() >> 1);
                if (isSub(inverse(stored.relation()), instance)) typeRoots.add(stored.object() >> 1);
            }
        }
        for (NameEdge edge : subtypeEdges) {
            typeRoots.add(root(edge.sub()));
            typeRoots.add(root(edge.sup()));
        }
        for (AliasEdge edge : aliasEdges) {
            int alias = oriented(edge.alias());
            int resolved = oriented(edge.resolved());
            if (alias == resolved) continue;
            aliasUpEdges.computeIfAbsent(alias, k -> new ArrayList<>()).add(new Up(resolved, List.of(edge.why()), false));
            aliasDownEdges.computeIfAbsent(resolved, k -> new ArrayList<>()).add(new Up(alias, List.of(edge.why()), false));
        }
        // r08: a thing typed with an alias is typed with what it is an alias of.
        for (int type : List.copyOf(typeRoots)) {
            for (int up : aliasUp(type * 2).keySet()) typeRoots.add(up >> 1);
        }
        typeRoots.removeAll(relationRoots);
        if (!typeRoots.isEmpty()) typeRoots.add(root(TYPE));
    }

    /** Alias edges between nodes, both ways, with the statements that make them. */
    private final Map<Integer, List<Up>> aliasUpEdges = new HashMap<>();
    private final Map<Integer, List<Up>> aliasDownEdges = new HashMap<>();

    /** {@code node} and every node it is an alias of, each with the alias statements on the way. */
    private Map<Integer, List<IFact>> aliasUp(int node) {
        return aliasWalk(node, aliasUpEdges);
    }

    /** {@code node} and every node that is an alias of it: whose statements are statements about it too. */
    private Map<Integer, List<IFact>> aliasDown(int node) {
        return aliasWalk(node, aliasDownEdges);
    }

    private Map<Integer, List<IFact>> aliasWalk(int node, Map<Integer, List<Up>> edges) {
        Map<Integer, List<IFact>> out = new LinkedHashMap<>();
        out.put(node, List.of());
        if (edges.isEmpty()) return out;
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

    private static long key(int subject, int object) {
        return ((long) subject << 32) | (object & 0xffffffffL);
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
        if (x == y && isSub(oriented(SAME_AS), p)) return new LinkedHashSet<>();
        // A relation read backwards is its inverse (r02 at the meta level, r11 for a symmetric one).
        if (relationRoots.contains(x >> 1) && y == inverse(x) && isSub(oriented(HAS_INVERSE), p)) {
            return new LinkedHashSet<>(identity.explain(identity.name(x >> 1), identity.name(y >> 1)));
        }
        if (isSub(oriented(HAS_TAG), p) && (proof = tagged(x, y)) != null) return proof;
        if (isSub(oriented(IS_TAG_OF), p) && (proof = tagged(y, x)) != null) return proof;
        if (isSub(oriented(HAS_TYPE), p) && (proof = typed(x, y)) != null) return proof;
        if (isSub(oriented(HAS_INSTANCE), p) && (proof = typed(y, x)) != null) return proof;
        // The hierarchy itself: has-subtype triples r17 derives, which no stored triple answers.
        int hasSubtype = oriented(HAS_SUBTYPE);
        if (isSub(hasSubtype, p) && (proof = subtypeStep(y, x, rules.transitive && chains)) != null) return proof;
        if (isSub(inverse(hasSubtype), p) && (proof = subtypeStep(x, y, rules.transitive && chains)) != null) return proof;
        if (rules.transitive && chains) {
            for (int transitive : transitiveRelations()) {
                if (!isSub(transitive, p)) continue;
                proof = derivedStepsUnder(transitive) ? derivedPath(x, y, transitive) : path(x, y, transitive);
                if (proof != null) return proof;
            }
        }
        return null;
    }

    /**
     * Whether a step of {@code relation} can be a triple another rule derived: one of type
     * inheritance, of tags reaching parts, or of a relation's inverse -- the rules whose
     * conclusions are no stored triple. Where none lies under it, its chains run over stored
     * triples only, and a plain walk finds them.
     */
    private boolean derivedStepsUnder(int relation) {
        return isSub(oriented(HAS_TYPE), relation) || isSub(oriented(HAS_INSTANCE), relation)
                || isSub(oriented(HAS_TAG), relation) || isSub(oriented(IS_TAG_OF), relation)
                || isSub(oriented(HAS_INVERSE), relation);
    }

    /**
     * r01 over every triple, derived ones included: a walk whose every step is anything
     * {@link #derive} concludes for the relation without a chain. Obviously right, and slow -- every
     * step tries every candidate.
     */
    private @Nullable Set<IFact> derivedPath(int from, int to, int relation) {
        Map<Integer, Integer> previous = new HashMap<>();
        Map<Integer, Set<IFact>> stepWhy = new HashMap<>();
        Deque<Integer> queue = new ArrayDeque<>(List.of(from));
        previous.put(from, from);
        while (!queue.isEmpty()) {
            int at = queue.poll();
            for (int next : candidates(at)) {
                if (next != to && previous.containsKey(next)) continue;
                Set<IFact> step = derive(at, relation, next, false);
                if (step == null) continue;
                if (next == to) {
                    // Back to the start counts: a cycle makes x p x.
                    Set<IFact> out = new LinkedHashSet<>(step);
                    for (int node = at; node != from; node = previous.get(node)) out.addAll(stepWhy.get(node));
                    return out;
                }
                previous.put(next, at);
                stepWhy.put(next, step);
                queue.add(next);
            }
        }
        return null;
    }

    /** A stored triple between the two, in either direction, whose predicate lies under {@code p}. */
    private @Nullable Set<IFact> direct(int x, int p, int y) {
        // r06, r08: a triple stated about an alias of either end is about that end too.
        Map<Integer, List<IFact>> subjects = aliasDown(x);
        Map<Integer, List<IFact>> objects = aliasDown(y);
        for (Map.Entry<Integer, List<IFact>> subject : subjects.entrySet()) {
            for (Map.Entry<Integer, List<IFact>> object : objects.entrySet()) {
                Set<IFact> found = directly(subject.getKey(), p, object.getKey());
                if (found != null) {
                    found.addAll(subject.getValue());
                    found.addAll(object.getValue());
                    return found;
                }
            }
        }
        return null;
    }

    private @Nullable Set<IFact> directly(int x, int p, int y) {
        for (Stored stored : byPair.getOrDefault(key(x, y), List.of())) {
            if (isSub(stored.relation(), p)) return support(stored, stored.relation(), p);
        }
        for (Stored stored : byPair.getOrDefault(key(y, x), List.of())) {
            if (isSub(inverse(stored.relation()), p)) return support(stored, inverse(stored.relation()), p);
        }
        return null;
    }

    /** A stored triple, the names it was normalised through, and the hierarchy path it was lifted along. */
    private Set<IFact> support(Stored stored, int usedAs, int p) {
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
            for (Stored stored : byNode.getOrDefault(at, List.of())) {
                if (stored.subject() == at && isSub(stored.relation(), relation)) {
                    landing(out, node, stored.object(), stored, stored.relation(), source.getValue());
                }
                if (stored.object() == at && isSub(inverse(stored.relation()), relation)) {
                    landing(out, node, stored.subject(), stored, inverse(stored.relation()), source.getValue());
                }
            }
        }
        return out;
    }

    private void landing(List<Hop> out, int from, int to, Stored stored, int relation, List<IFact> aliases) {
        for (Map.Entry<Integer, List<IFact>> target : aliasUp(to).entrySet()) {
            List<IFact> why = new ArrayList<>(aliases);
            why.addAll(target.getValue());
            out.add(new Hop(from, target.getKey(), stored, relation, why));
        }
    }

    /** r01: a chain of steps from {@code from} to {@code to}, each lying under the transitive {@code relation}. */
    private @Nullable Set<IFact> path(int from, int to, int relation) {
        if (from == to) {
            // A cycle: x p x holds when a chain leaves x and comes back.
            for (Hop hop : hops(from, relation)) {
                Set<IFact> back = hop.to() == from ? new LinkedHashSet<>() : path(hop.to(), from, relation);
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
        List<Hop> steps = new ArrayList<>();
        for (int at = to; at != from; at = via.get(at).from()) steps.addFirst(via.get(at));
        Set<IFact> out = new LinkedHashSet<>();
        for (Hop step : steps) {
            out.addAll(support(step.stored(), step.relation(), relation));
            out.addAll(step.aliases());
        }
        return out;
    }

    private List<Integer> transitiveRelations() {
        List<Integer> out = new ArrayList<>();
        for (int root : transitiveRoots) {
            out.add(root * 2);
            if (!identity.isSymmetric(root)) out.add(root * 2 + 1);
        }
        return out;
    }

    /**
     * r04 and typing-creates-types: {@code x has type u}, stated for {@code u} or for a subtype of it.
     */
    private @Nullable Set<IFact> typed(int x, int u) {
        for (Hop hop : hops(x, oriented(HAS_TYPE))) {
            List<IFact> up = typeUp(hop.to(), u);
            if (up != null) {
                Set<IFact> out = support(hop.stored(), hop.relation(), oriented(HAS_TYPE));
                out.addAll(hop.aliases());
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
        return isSub(type, u) ? hierarchyWhy(type, u) : null;
    }

    /** The types {@code x} has because of what it is rather than what anybody typed it with. */
    private Map<Integer, List<IFact>> implicitTypes(int x) {
        Map<Integer, List<IFact>> out = new TreeMap<>();
        int root = x >> 1;
        if (typeRoots.contains(root) && (x & 1) == 0) out.put(oriented(TYPE), typeReason(x));
        if (relationRoots.contains(root)) {
            out.put(oriented(RELATION), List.of());
            if (transitiveRoots.contains(root)) out.put(oriented(TRANSITIVE_RELATION), List.of());
            if (identity.isSymmetric(root)) out.put(oriented(SYMMETRIC_RELATION), List.of());
        }
        return out;
    }

    /** One triple that makes {@code x} a type: something typed with it, or a subtype edge at it. */
    private List<IFact> typeReason(int x) {
        int instance = oriented(HAS_INSTANCE);
        int subtype = oriented(HAS_SUBTYPE);
        for (Stored stored : byNode.getOrDefault(x, List.of())) {
            boolean typedWithX = stored.subject() == x ? isSub(stored.relation(), instance) : isSub(inverse(stored.relation()), instance);
            if (typedWithX || isSub(stored.relation(), subtype) || isSub(inverse(stored.relation()), subtype)) {
                return List.of(stored.source());
            }
        }
        return List.of();
    }

    /**
     * r18: {@code x has tag u}, stated for {@code x} or for anything {@code x} is part of, at any
     * depth. A type is a tag too, since {@code has type} lies under {@code has tag}.
     */
    private @Nullable Set<IFact> tagged(int x, int u) {
        int tag = oriented(HAS_TAG);
        int partOf = oriented(IS_PART_OF);
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

    // ---- patterns ------------------------------------------------------------------------------

    /**
     * Every entailed triple matching a pattern, in canonical names; a null position matches anything.
     *
     * <p>Built for being obviously right, not fast: it tries every candidate against {@link #why}'s
     * own derivation. An object can only be entailed for a subject if a chain of stored triples joins
     * the two in some direction, or if it is one of the items the engine types things with, so the
     * candidates are the subject's connected component plus those items. Two names of one concept
     * are one node here, so {@code JS same as JavaScript}, which {@link #why} answers, has no triple
     * of its own.
     */
    List<Triple> match(@Nullable String s, @Nullable String p, @Nullable String o) {
        if (!rules.normalise) return literalMatch(s, p, o);
        List<Integer> relations = p != null ? List.of(oriented(p)) : relationIds();
        Set<Integer> subjects = s != null ? Set.of(oriented(s)) : o != null ? candidates(oriented(o)) : allNodes();
        Set<Triple> out = new LinkedHashSet<>();
        for (int x : subjects) {
            Set<Integer> objects = o != null ? Set.of(oriented(o)) : candidates(x);
            for (int y : objects) {
                for (int relation : relations) {
                    if (derive(x, relation, y, true) != null) out.add(new Triple(name(x), name(relation), name(y)));
                }
            }
        }
        return List.copyOf(out);
    }

    private List<Integer> relationIds() {
        List<Integer> out = new ArrayList<>();
        for (int root : relationRoots) {
            out.add(root * 2);
            if (!identity.isSymmetric(root)) out.add(root * 2 + 1);
        }
        return out;
    }

    /** The items the engine types things with, which nothing needs to be connected to. */
    private Set<Integer> virtualItems() {
        return Set.of(oriented(TYPE), oriented(RELATION), oriented(SYMMETRIC_RELATION), oriented(TRANSITIVE_RELATION));
    }

    private Set<Integer> allNodes() {
        Set<Integer> out = new LinkedHashSet<>(byNode.keySet());
        out.addAll(virtualItems());
        out.addAll(relationIds());
        for (int root : typeRoots) out.add(root * 2);
        return out;
    }

    /** Everything that can be at the other end of an entailed triple from {@code node}. */
    private Set<Integer> candidates(int node) {
        if (virtualItems().contains(node)) return allNodes();
        Set<Integer> out = new LinkedHashSet<>(List.of(node));
        Deque<Integer> queue = new ArrayDeque<>(List.of(node));
        while (!queue.isEmpty()) {
            for (Stored stored : byNode.getOrDefault(queue.poll(), List.of())) {
                for (int end : List.of(stored.subject(), stored.object())) {
                    if (out.add(end)) queue.add(end);
                }
            }
        }
        // Relations and types are typed without a stored triple saying so -- a relation read
        // backwards, or one only ever used as a predicate, is in no stored triple at all -- so every
        // one of them is a candidate, whatever it is connected to. There are few of them.
        out.addAll(relationIds());
        for (int root : typeRoots) out.add(root * 2);
        out.addAll(virtualItems());
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
