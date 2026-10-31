package it.ddot.core.cds;

import it.ddot.core.cds.CdsQuery.Query;
import it.ddot.core.store.IFact;
import it.ddot.core.store.TripleStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static it.ddot.core.cds.Cds.*;
import static it.ddot.core.cds.CdsQuery.*;

/**
 * Questions to put to an engine, drawn from a corpus, and what to check about the answers.
 *
 * <p>A probe is one question -- {@code holds}, {@code match}, a query, or one about the vocabulary
 * -- and its answer is a plain value, so two engines' answers compare with {@code equals}. The same
 * probes serve three purposes: differential tests against the oracle, properties any engine must
 * have, and the benchmark.
 */
final class CdsProbes {

    enum Kind {HOLDS, MATCH_SP, MATCH_PO, MATCH_SO, SUB, CANONICAL, IS_TYPE, IS_RELATION, IS_SYMMETRIC, IS_TRANSITIVE, CANONICAL_FORM, QUERY}

    /** One question. {@code query} is set for {@link Kind#QUERY}, the strings otherwise. */
    record Probe(Kind kind, String a, String b, String c, Query query) {

        Object answer(CdsReasoner reasoner) {
            return answer(reasoner, Integer.MAX_VALUE);
        }

        /** With at most {@code limit} solutions to a query, for measuring rather than comparing. */
        Object answer(CdsReasoner reasoner, int limit) {
            return switch (kind) {
                case HOLDS -> reasoner.holds(a, b, c);
                case MATCH_SP -> Set.copyOf(reasoner.match(a, b, null));
                case MATCH_PO -> Set.copyOf(reasoner.match(null, b, c));
                case MATCH_SO -> Set.copyOf(reasoner.match(a, null, c));
                case SUB -> reasoner.isSubRelation(a, b);
                case CANONICAL -> reasoner.canonical(a);
                case IS_TYPE -> reasoner.isType(a);
                case IS_RELATION -> reasoner.isRelation(a);
                case IS_SYMMETRIC -> reasoner.isSymmetric(a);
                case IS_TRANSITIVE -> reasoner.isTransitive(a);
                case CANONICAL_FORM -> reasoner.canonicalForm(new Triple(a, b, c));
                case QUERY -> Set.copyOf(new CdsQueryEngine(reasoner).select(query.limit(limit)));
            };
        }

        @Override
        public String toString() {
            return kind == Kind.QUERY ? "QUERY " + query : kind + "(" + a + ", " + b + ", " + c + ")";
        }
    }

    /**
     * How many of each kind to draw. Matching and queries cost the oracle a scan each, and a
     * {@code p+} costs it a scan per step: on the adir graph, one {@code links to+} took the oracle
     * 210 s. So the real corpora draw no {@code p+} unless {@code -Dcds.thorough} is set.
     */
    record Budget(int holds, int matches, int queries, int vocabulary, boolean closures) {
        static final Budget SMALL = new Budget(2_000, 60, 20, 300, true);
        static final Budget REAL = new Budget(3_000, 15, 5, 300, Boolean.getBoolean("cds.thorough"));
        static final Budget BENCH = new Budget(20_000, 200, 50, 2_000, true);
    }

    private CdsProbes() {}

    static List<Probe> draw(TripleStore<?> store, long seed, Budget budget) {
        Random random = new Random(seed);
        // Sorted: a real corpus is indexed in parallel, and the same seed must draw the same probes.
        List<IFact> facts = new ArrayList<>(store.all());
        facts.sort(java.util.Comparator.comparing(fact -> Triple.of(fact).toString()));
        List<Probe> out = new ArrayList<>();
        if (facts.isEmpty()) return out;
        List<String> nodes = new ArrayList<>(nodes(facts));
        List<String> relations = relations(facts);
        List<String> types = new ArrayList<>(types(facts));
        Map<String, List<IFact>> touching = new HashMap<>();
        for (IFact fact : facts) {
            touching.computeIfAbsent(fact.subject(), k -> new ArrayList<>()).add(fact);
            touching.computeIfAbsent(fact.object(), k -> new ArrayList<>()).add(fact);
        }

        for (int i = 0; i < budget.holds(); i++) {
            IFact fact = facts.get(random.nextInt(facts.size()));
            int kind = random.nextInt(10);
            if (kind < 2) {
                // What was stated, and the same read backwards.
                out.add(holds(fact.subject(), fact.relation(), fact.object()));
                out.add(holds(fact.object(), "^" + fact.relation(), fact.subject()));
            } else if (kind < 6) {
                // The same pair, any relation, either way round.
                String relation = pick(random, relations);
                out.add(random.nextBoolean() ? holds(fact.subject(), relation, fact.object()) : holds(fact.object(), relation, fact.subject()));
            } else if (kind < 8) {
                // Two steps away, for chains.
                String middle = fact.object();
                List<IFact> next = touching.getOrDefault(middle, List.of());
                IFact second = next.get(random.nextInt(next.size()));
                String end = second.subject().equals(middle) ? second.object() : second.subject();
                out.add(holds(fact.subject(), pick(random, relations), end));
            } else if (kind < 9 && !types.isEmpty()) {
                out.add(holds(pick(random, nodes), pick(random, List.of(HAS_TYPE, HAS_TAG, RELATED, HAS_INSTANCE)), pick(random, types)));
            } else {
                out.add(holds(pick(random, nodes), pick(random, relations), pick(random, nodes)));
            }
        }
        for (int i = 0; i < budget.matches(); i++) {
            IFact fact = facts.get(random.nextInt(facts.size()));
            String relation = random.nextBoolean() ? fact.relation() : pick(random, relations);
            out.add(switch (random.nextInt(3)) {
                case 0 -> new Probe(Kind.MATCH_SP, fact.subject(), relation, "", null);
                case 1 -> new Probe(Kind.MATCH_PO, "", relation, fact.object(), null);
                default -> new Probe(Kind.MATCH_SO, fact.subject(), "", fact.object(), null);
            });
        }
        for (int i = 0; i < budget.queries(); i++) {
            IFact fact = facts.get(random.nextInt(facts.size()));
            String second = pick(random, relations);
            int shape = random.nextInt(4);
            if (shape == 2 && !budget.closures()) shape = 1;
            Query query = switch (shape) {
                case 0 -> where(t(fact.subject(), fact.relation(), "?x"), t("?x", second, "?y"));
                case 1 -> where(t(fact.subject(), seq(rel(fact.relation()), rel(second)), "?y"));
                case 2 -> where(t(fact.subject(), plus(rel(fact.relation())), "?y"));
                default -> where(t("?x", inv(rel(fact.relation())), fact.subject()), t("?x", "?p", fact.subject()));
            };
            out.add(new Probe(Kind.QUERY, "", "", "", query));
        }
        for (int i = 0; i < budget.vocabulary(); i++) {
            String name = random.nextBoolean() ? pick(random, relations) : pick(random, nodes);
            IFact fact = facts.get(random.nextInt(facts.size()));
            out.add(switch (random.nextInt(7)) {
                case 0 -> new Probe(Kind.SUB, pick(random, relations), pick(random, relations), "", null);
                case 1 -> new Probe(Kind.CANONICAL, name, "", "", null);
                case 2 -> new Probe(Kind.IS_TYPE, name, "", "", null);
                case 3 -> new Probe(Kind.IS_RELATION, name, "", "", null);
                case 4 -> new Probe(Kind.IS_SYMMETRIC, name, "", "", null);
                case 5 -> new Probe(Kind.IS_TRANSITIVE, name, "", "", null);
                default -> new Probe(Kind.CANONICAL_FORM, fact.subject(), fact.relation(), fact.object(), null);
            });
        }
        return out;
    }

    static Probe holds(String subject, String relation, String object) {
        return new Probe(Kind.HOLDS, subject, relation, object, null);
    }

    // ---- properties ----------------------------------------------------------------------------

    /**
     * What must be true of any engine's answers, the oracle's included -- a check on the oracle that
     * does not need a second oracle.
     *
     * @return one line per violation; empty when all hold
     */
    static List<String> violations(CdsReasoner reasoner, TripleStore<?> store, List<Probe> probes, RuleSet rules) {
        List<String> out = new ArrayList<>();
        Set<Triple> known = new HashSet<>(Cds.axioms());
        for (IFact fact : store.all()) known.add(Triple.of(fact));
        int matchChecks = 0;
        for (Probe probe : probes) {
            if (out.size() >= 20) break;
            if (probe.kind() != Kind.HOLDS) continue;
            String x = probe.a();
            String p = probe.b();
            String y = probe.c();
            boolean holds = reasoner.holds(x, p, y);
            if (rules.normalise && holds != reasoner.holds(y, "^" + p, x)) {
                out.add("reading backwards changes the answer: " + probe);
            }
            if (holds && rules.normalise) {
                for (String up : List.of(RELATED, LINKS_TO, IS_LINKED_FROM, HAS_SIMILAR)) {
                    if (reasoner.isSubRelation(p, up) && !reasoner.holds(x, up, y)) {
                        out.add(probe + " holds but not for " + up + ", above it");
                    }
                }
            }
            if (holds) {
                for (IFact support : reasoner.why(x, p, y).orElseThrow()) {
                    if (!known.contains(Triple.of(support))) out.add(probe + " rests on " + support + ", which nobody stated");
                }
            }
            if (holds && rules.normalise && matchChecks++ < 100) {
                String cx = reasoner.canonical(x);
                String cy = reasoner.canonical(y);
                if (!cx.equals(cy) && !reasoner.match(x, p, null).contains(new Triple(cx, reasoner.canonical(p), cy))) {
                    out.add(probe + " holds but match(" + x + ", " + p + ", ?) does not list " + cy);
                }
            }
        }
        for (IFact fact : store.all().subList(0, Math.min(500, store.size()))) {
            if (!reasoner.holds(fact.subject(), fact.relation(), fact.object())) out.add("stated but does not hold: " + fact);
        }
        return out;
    }

    /** Differences between two engines' answers, at most {@code limit}. */
    static List<String> differences(CdsReasoner oracle, CdsReasoner candidate, List<Probe> probes, int limit) {
        return differences(answers(oracle, probes), candidate, probes, limit);
    }

    static List<Object> answers(CdsReasoner engine, List<Probe> probes) {
        List<Object> out = new ArrayList<>(probes.size());
        for (Probe probe : probes) out.add(probe.answer(engine));
        return out;
    }

    /** Differences between answers worked out before and a candidate's, at most {@code limit}. */
    static List<String> differences(List<Object> answers, CdsReasoner candidate, List<Probe> probes, int limit) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < probes.size(); i++) {
            Probe probe = probes.get(i);
            Object expected = answers.get(i);
            Object actual = probe.answer(candidate);
            if (!expected.equals(actual)) {
                out.add(probe + ": oracle " + expected + ", candidate " + actual);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    // ---- vocabulary of a corpus ----------------------------------------------------------------

    private static Set<String> nodes(List<IFact> facts) {
        Set<String> out = new LinkedHashSet<>();
        for (IFact fact : facts) {
            out.add(fact.subject());
            out.add(fact.object());
        }
        return out;
    }

    /** Every relation used, the vocabulary's, and a few read backwards. */
    private static List<String> relations(List<IFact> facts) {
        Set<String> out = new LinkedHashSet<>();
        for (Cds.Pair pair : Cds.PAIRS) {
            out.add(pair.relation());
            out.add(pair.inverse());
        }
        for (IFact fact : facts) out.add(fact.relation());
        List<String> list = new ArrayList<>(out);
        for (int i = 0; i < list.size(); i += 5) out.add("^" + list.get(i));
        return new ArrayList<>(out);
    }

    private static Set<String> types(List<IFact> facts) {
        Set<String> out = new LinkedHashSet<>();
        for (IFact fact : facts) {
            if (fact.relation().equals(HAS_TYPE) || fact.relation().equals(HAS_SUBTYPE)) out.add(fact.object());
            if (fact.relation().equals(HAS_INSTANCE) || fact.relation().equals(HAS_SUBTYPE)) out.add(fact.subject());
        }
        out.add(TYPE);
        return out;
    }

    private static String pick(Random random, List<String> from) {
        return from.get(random.nextInt(from.size()));
    }
}
