package it.ddot.core.cds;

import it.ddot.core.store.IFact;
import it.ddot.core.store.TripleStore;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static it.ddot.core.cds.Cds.*;

/**
 * The rules applied literally: every rule, to every triple, stated or derived, until nothing new
 * follows -- and every triple that follows stored.
 *
 * <p>The oracle for the oracle. {@link CdsInferenceEngine} is written to be read against the spec,
 * but it is still clever: it normalises names, stores each fact once, and works out at question
 * time what the rules would derive. Cleverness can be wrong in ways no test of it can see, since its
 * tests ask it what it believes. This is not clever. It is a worklist over a set of triples, and
 * each rule is a few lines that say what the spec says. It is slow, and its closure grows with
 * every rule, so it is for small graphs only.
 *
 * <h2>What it applies</h2>
 * <ul>
 * <li>r01–r18 of <a href="https://ddot.it/spec/ddot-it-cds.html">the spec</a>, r03 included --
 * the spec deactivates it only because r09 covers it.</li>
 * <li>The ddot.it additions: {@code replaces} is transitive (an axiom), and a {@code has subtype}
 * cycle makes its members the same.</li>
 * <li>{@code same as} is reflexive: anything is the same as itself, and so whatever lies above
 * {@code same as}. It is a tautology, answered for every name -- and no premise of any rule: as a
 * statement about an alias, r06 would hand it on, and make every alias the same as what it
 * aliases. So {@code x same as x} is never kept, however derived (r12 derives it for every relation
 * with an inverse, r14–r16 for every member of a same-as class), and only answered.</li>
 * <li>Aliases as the spec reads them (r06–r08): what is said about an alias holds for what it is an
 * alias of, and not the other way round. What the convention says of every relation -- its inverse
 * {@code ^p} -- is not said about the alias, so an alias does not hand it on; otherwise an alias of a
 * relation would end up the same as it.</li>
 * <li>The conventions of {@code doc/cds-inf.adoc}: whatever is used as a predicate is a relation;
 * a subtype edge with a relation at one end has a relation at the other; every relation has an
 * inverse, named {@code ^p} where nobody named it; a relation nothing places lies under
 * {@code related}, unless it lies under {@code has inverse}; and whatever is typed with or in a
 * subtype edge is a {@code type}, unless it is a relation.</li>
 * </ul>
 * The last two depend on what does <em>not</em> follow, so they run in strata: the monotone rules
 * to their fixpoint, then those two, then the monotone rules again, until neither adds anything.
 *
 * <h2>What it answers</h2>
 * Whether a triple is in the closure, and the questions about the vocabulary that are triples too.
 * It has no canonical names -- every name of a concept carries every triple -- so it does not
 * answer {@link #canonical}, {@link #canonicalForm} or {@link #match} in the engines' terms, and
 * {@link #why} says only that something holds.
 */
public final class CdsFixpointOracle implements CdsReasoner {

    private final TripleStore<?> store;
    private final RuleSet rules;
    private @Nullable Closure closure;
    private long builtAt = -1;

    public CdsFixpointOracle(TripleStore<?> store, RuleSet rules) {
        this.store = store;
        this.rules = rules;
        if (rules != RuleSet.ALL) throw new IllegalArgumentException("the fixpoint applies every rule; " + rules + " is not supported");
    }

    @Override
    public RuleSet rules() {
        return rules;
    }

    @Override
    public TripleStore<?> store() {
        return store;
    }

    /** How many triples the closure holds. */
    public int size() {
        return closure().triples.size();
    }

    @Override
    public boolean holds(String subject, String relation, String object) {
        Triple asked = written(subject, relation, object);
        if (closure().triples.contains(asked)) return true;
        // Anything is the same as itself, and so whatever lies above same as -- for every name,
        // the ones nothing was said about included.
        return asked.subject().equals(asked.object()) && isSubRelation(SAME_AS, asked.relation());
    }

    @Override
    public Optional<Set<IFact>> why(String subject, String relation, String object) {
        return holds(subject, relation, object) ? Optional.of(Set.of()) : Optional.empty();
    }

    @Override
    public List<Triple> match(@Nullable String subject, @Nullable String relation, @Nullable String object) {
        throw new UnsupportedOperationException("the fixpoint has no canonical names to match in");
    }

    @Override
    public boolean isSubRelation(String sub, String sup) {
        String a = plain(sub);
        String b = plain(sup);
        Set<Triple> triples = closure().triples;
        return a.equals(b) || triples.contains(new Triple(b, HAS_SUBTYPE, a)) || triples.contains(new Triple(a, SAME_AS, b));
    }

    @Override
    public String canonical(String name) {
        throw new UnsupportedOperationException("the fixpoint has no canonical names");
    }

    @Override
    public Triple canonicalForm(IFact fact) {
        throw new UnsupportedOperationException("the fixpoint has no canonical names");
    }

    @Override
    public boolean isRelation(String name) {
        return closure().triples.contains(new Triple(plain(name), HAS_TYPE, RELATION));
    }

    @Override
    public boolean isType(String name) {
        return closure().triples.contains(new Triple(plain(name), HAS_TYPE, TYPE));
    }

    @Override
    public boolean isSymmetric(String relation) {
        return isRelation(relation) && closure().triples.contains(new Triple(plain(relation), HAS_TYPE, SYMMETRIC_RELATION));
    }

    @Override
    public boolean isTransitive(String relation) {
        return closure().triples.contains(new Triple(plain(relation), HAS_TYPE, TRANSITIVE_RELATION));
    }

    private synchronized Closure closure() {
        if (closure == null || store.revision() != builtAt) {
            builtAt = store.revision();
            List<IFact> all = new ArrayList<>(Cds.axioms());
            all.addAll(store.all());
            closure = new Closure(all);
        }
        return closure;
    }

    /**
     * A triple as written, read: {@code a ^p b} is {@code b p a}, and {@code ^^p} is {@code p}. A
     * name read backwards is only meaningful as a relation, so only the relation is read so.
     */
    static Triple written(String subject, String relation, String object) {
        int flips = 0;
        while (flips < relation.length() && relation.charAt(flips) == '^') flips++;
        String plain = relation.substring(flips);
        return flips % 2 == 0 ? new Triple(subject, plain, object) : new Triple(object, plain, subject);
    }

    /** A relation's name with pairs of {@code ^} taken off: {@code ^^p} is {@code p}, {@code ^p} stays. */
    static String plain(String name) {
        int flips = 0;
        while (flips < name.length() && name.charAt(flips) == '^') flips++;
        return (flips % 2 == 0 ? "" : "^") + name.substring(flips);
    }

    /** The closure of a set of triples under every rule. */
    static final class Closure {

        final Set<Triple> triples = new HashSet<>();
        /** Predicate, subject, objects. */
        private final Map<String, Map<String, Set<String>>> out = new HashMap<>();
        /** Predicate, object, subjects. */
        private final Map<String, Map<String, Set<String>>> in = new HashMap<>();
        /** Every triple a name occurs in, in any position. */
        private final Map<String, List<Triple>> mentions = new HashMap<>();
        private final ArrayDeque<Triple> work = new ArrayDeque<>();

        Closure(List<? extends IFact> facts) {
            for (IFact fact : facts) add(written(fact.subject(), fact.relation(), fact.object()));
            boolean more = true;
            while (more) {
                run();
                more = types() | underRelated();
            }
        }

        private void add(Triple triple) {
            // x same as x is a tautology, answered for every name, and no premise of any rule: as a
            // statement about x, r06 would hand it to whatever x is an alias of, and make every
            // alias the same as what it aliases. However derived -- r12 derives it for every
            // relation with an inverse -- it is not kept.
            if (triple.relation().equals(SAME_AS) && triple.subject().equals(triple.object())) return;
            if (!triples.add(triple)) return;
            out.computeIfAbsent(triple.relation(), k -> new HashMap<>()).computeIfAbsent(triple.subject(), k -> new HashSet<>()).add(triple.object());
            in.computeIfAbsent(triple.relation(), k -> new HashMap<>()).computeIfAbsent(triple.object(), k -> new HashSet<>()).add(triple.subject());
            for (String name : new LinkedHashSet<>(List.of(triple.subject(), triple.relation(), triple.object()))) {
                mentions.computeIfAbsent(name, k -> new ArrayList<>()).add(triple);
            }
            work.add(triple);
        }

        private void add(String s, String p, String o) {
            add(new Triple(s, p, o));
        }

        private boolean has(String s, String p, String o) {
            return triples.contains(new Triple(s, p, o));
        }

        private Set<String> out(String p, String s) {
            Map<String, Set<String>> bySubject = out.get(p);
            if (bySubject == null) return Set.of();
            Set<String> objects = bySubject.get(s);
            return objects == null ? Set.of() : new HashSet<>(objects);
        }

        private Set<String> in(String p, String o) {
            Map<String, Set<String>> byObject = in.get(p);
            if (byObject == null) return Set.of();
            Set<String> subjects = byObject.get(o);
            return subjects == null ? Set.of() : new HashSet<>(subjects);
        }

        /** Every triple with predicate {@code p}, as a copy, since the rules add while it is read. */
        private List<Triple> withPredicate(String p) {
            List<Triple> outList = new ArrayList<>();
            Map<String, Set<String>> bySubject = out.get(p);
            if (bySubject == null) return outList;
            for (Map.Entry<String, Set<String>> entry : bySubject.entrySet()) {
                for (String o : entry.getValue()) outList.add(new Triple(entry.getKey(), p, o));
            }
            return outList;
        }

        /** The worklist: each triple, once, against everything there is when it is taken. */
        private void run() {
            while (!work.isEmpty()) {
                Triple t = work.poll();
                String x = t.subject();
                String p = t.relation();
                String y = t.object();

                // Convention: whatever is used as a predicate is a relation.
                add(p, HAS_TYPE, RELATION);


                // r01 transitive
                if (has(p, HAS_TYPE, TRANSITIVE_RELATION)) {
                    for (String z : out(p, y)) add(x, p, z);
                    for (String w : in(p, x)) add(w, p, y);
                }
                if (p.equals(HAS_TYPE) && y.equals(TRANSITIVE_RELATION)) {
                    for (Triple step : withPredicate(x)) for (String z : out(x, step.object())) add(step.subject(), x, z);
                }
                // r02 inverse (A-box)
                for (String q : out(HAS_INVERSE, p)) add(y, q, x);
                if (p.equals(HAS_INVERSE)) for (Triple step : withPredicate(x)) add(step.object(), y, step.subject());
                // r03 inverse (T-box)
                if (p.equals(HAS_INVERSE)) add(y, HAS_INVERSE, x);
                // r04 type inheritance
                if (p.equals(HAS_TYPE)) for (String u : out(HAS_SUPERTYPE, y)) add(x, HAS_TYPE, u);
                if (p.equals(HAS_SUPERTYPE)) for (String a : in(HAS_TYPE, x)) add(a, HAS_TYPE, y);
                // r05 relation inheritance
                for (String u : out(HAS_SUPERTYPE, p)) add(x, u, y);
                if (p.equals(HAS_SUPERTYPE)) for (Triple step : withPredicate(x)) add(step.subject(), y, step.object());
                // r06–r08 alias, r14–r16 same as: each position rewritten
                for (String rewriting : List.of(IS_ALIAS_OF, SAME_AS)) {
                    if (p.equals(rewriting)) {
                        for (Triple mention : List.copyOf(mentions.getOrDefault(x, List.of()))) {
                            if (handsOn(mention, rewriting)) rewrite(mention, x, y);
                        }
                    }
                    if (!handsOn(t, rewriting)) continue;
                    for (String resolved : out(rewriting, x)) rewrite(t, x, resolved);
                    for (String resolved : out(rewriting, p)) rewrite(t, p, resolved);
                    for (String resolved : out(rewriting, y)) rewrite(t, y, resolved);
                }
                // r09 symmetric (A-box)
                if (has(p, HAS_TYPE, SYMMETRIC_RELATION)) add(y, p, x);
                if (p.equals(HAS_TYPE) && y.equals(SYMMETRIC_RELATION)) for (Triple step : withPredicate(x)) add(step.object(), x, step.subject());
                // r10, r11 self-inverse and symmetric
                if (p.equals(HAS_INVERSE) && x.equals(y)) add(x, HAS_TYPE, SYMMETRIC_RELATION);
                if (p.equals(HAS_TYPE) && y.equals(SYMMETRIC_RELATION)) add(x, HAS_INVERSE, x);
                // r12 inverse of inverse
                if (p.equals(HAS_INVERSE)) {
                    for (String r : out(HAS_INVERSE, y)) add(x, SAME_AS, r);
                    for (String w : in(HAS_INVERSE, x)) add(w, SAME_AS, y);
                }
                // r13 inverse inherits type
                if (p.equals(HAS_INVERSE)) for (String type : out(HAS_TYPE, x)) add(y, HAS_TYPE, type);
                if (p.equals(HAS_TYPE)) for (String q : out(HAS_INVERSE, x)) add(q, HAS_TYPE, y);
                // r17 sub-relation carries over to inverses
                if (p.equals(HAS_SUPERTYPE)) {
                    for (String b : out(HAS_INVERSE, x)) for (String d : out(HAS_INVERSE, y)) add(b, HAS_SUPERTYPE, d);
                }
                if (p.equals(HAS_INVERSE)) {
                    for (String c : out(HAS_SUPERTYPE, x)) for (String d : out(HAS_INVERSE, c)) add(y, HAS_SUPERTYPE, d);
                    for (String a : in(HAS_SUPERTYPE, x)) for (String b : out(HAS_INVERSE, a)) add(b, HAS_SUPERTYPE, y);
                }
                // r18 tags propagate to parts
                if (p.equals(HAS_TAG)) for (String part : out(HAS_PART, x)) add(part, HAS_TAG, y);
                if (p.equals(HAS_PART)) for (String tag : out(HAS_TAG, x)) add(y, HAS_TAG, tag);

                // ddot.it: a has-subtype cycle makes its members the same
                if (p.equals(HAS_SUBTYPE) && has(y, HAS_SUBTYPE, x)) add(x, SAME_AS, y);

                // Convention: a subtype edge with a relation at one end has a relation at the other.
                if (p.equals(HAS_SUBTYPE)) {
                    if (has(x, HAS_TYPE, RELATION)) add(y, HAS_TYPE, RELATION);
                    if (has(y, HAS_TYPE, RELATION)) add(x, HAS_TYPE, RELATION);
                }
                if (p.equals(HAS_TYPE) && y.equals(RELATION)) {
                    for (String b : out(HAS_SUBTYPE, x)) add(b, HAS_TYPE, RELATION);
                    for (String a : in(HAS_SUBTYPE, x)) add(a, HAS_TYPE, RELATION);
                    // Convention: every relation has an inverse, ^p where nobody named it.
                    if (!x.startsWith("^")) add(x, HAS_INVERSE, "^" + x);
                }
            }
        }

        /**
         * Whether r06–r08 ({@code is alias of}) or r14–r16 ({@code same as}) hand {@code t} on. An alias
         * hands on what is said about it, not what the convention says of every relation: that it has the
         * inverse {@code ^p}. Handed on, {@code a has inverse ^a} becomes {@code r has inverse ^a} for the
         * relation {@code r} that {@code a} aliases, and r03 and r12 then make {@code a same as r} -- an
         * alias of a relation would be a second name of it, against the decided reading that aliases are
         * one way. Two names of one concept do share their inverses, so {@code same as} hands it on.
         */
        private boolean handsOn(Triple t, String rewriting) {
            if (!rewriting.equals(IS_ALIAS_OF)) return true;
            // In any of its spellings: r02 reads `a has inverse ^a` backwards as `^a ^has inverse a`.
            String p = t.relation();
            if (!p.equals(HAS_INVERSE) && !has(p, SAME_AS, HAS_INVERSE)) return true;
            return !t.object().equals("^" + t.subject()) && !t.subject().equals("^" + t.object());
        }

        private void rewrite(Triple t, String from, String to) {
            if (t.subject().equals(from)) add(to, t.relation(), t.object());
            if (t.relation().equals(from)) add(t.subject(), to, t.object());
            if (t.object().equals(from)) add(t.subject(), t.relation(), to);
        }

        /** Convention: whatever is typed with, or in a subtype edge, is a type -- unless a relation. */
        private boolean types() {
            Set<String> candidates = new LinkedHashSet<>();
            for (Triple t : withPredicate(HAS_TYPE)) candidates.add(t.object());
            for (Triple t : withPredicate(HAS_SUBTYPE)) {
                candidates.add(t.subject());
                candidates.add(t.object());
            }
            boolean added = false;
            for (String candidate : candidates) {
                if (has(candidate, HAS_TYPE, RELATION) || has(candidate, HAS_TYPE, TYPE)) continue;
                add(candidate, HAS_TYPE, TYPE);
                added = true;
            }
            return added;
        }

        /** Convention: a relation lies under {@code related}, unless it lies under {@code has inverse}. */
        private boolean underRelated() {
            boolean added = false;
            for (String relation : in(HAS_TYPE, RELATION)) {
                if (relation.equals(RELATED) || relation.equals(HAS_INVERSE)) continue;
                if (has(relation, SAME_AS, HAS_INVERSE) || has(relation, HAS_SUPERTYPE, HAS_INVERSE)) continue;
                // related's own other names -- ^related, related being symmetric -- are related.
                if (has(relation, SAME_AS, RELATED)) continue;
                if (has(RELATED, HAS_SUBTYPE, relation)) continue;
                add(RELATED, HAS_SUBTYPE, relation);
                added = true;
            }
            return added;
        }
    }
}
