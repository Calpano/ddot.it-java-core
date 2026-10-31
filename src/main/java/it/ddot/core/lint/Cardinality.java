package it.ddot.core.lint;

import it.ddot.core.cds.Cds;
import it.ddot.core.cds.CdsReasoner;
import it.ddot.core.cds.Triple;
import it.ddot.core.store.IFact;
import it.ddot.core.embed.LocatedTriple;
import it.ddot.core.event.DdotTriple;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@value DdotAnalysis#CARDINALITY} and {@value DdotAnalysis#CARDINALITY_VALUE}: cardinality bounds of
 * relations, checked against what the project entails.
 *
 * <p>A bound is stated on the relation, in either of two ways:
 * <ul>
 *   <li>as a relation property (ddot-it-vocabulary.adoc §Cardinality) —
 *       {@code rel ..cardinality.. n}, {@code rel ..min cardinality.. n}, {@code rel ..max cardinality.. n},
 *       {@code rel ..has type.. required} (min 1), {@code rel ..has type.. single valued} (max 1);</li>
 *   <li>as metadata of the relation's range —
 *       {@code rel ..range.. Maven group ,, ..cardinality.. 1}, also with {@code doc:range}.</li>
 * </ul>
 *
 * <p>Who a bound applies to comes from the relation's domain, {@code rel ..domain.. Maven module} (also
 * {@code doc:domain}): every instance of the domain type, so an instance without any value violates a
 * minimum. Subjects that have a value for the relation are always checked as well. Without a domain, only
 * they are — so a minimum can then only catch too few values, never a missing one.
 *
 * <h2>Under CDS</h2>
 * Everything is asked of a {@link CdsReasoner} with {@link it.ddot.core.cds.RuleSet#ALL_EXCEPT_TRANSITIVE},
 * so the checks see what the project entails rather than only what it spells out:
 * <ul>
 *   <li>a value stated through an alias ({@code hasPart ..is alias of.. has part}), a sub-relation, an
 *       inverse ({@code tail ..is part of.. rex}) or a {@code same as} counts; two names of one thing count
 *       once;</li>
 *   <li>an instance of a subtype of the domain is an instance of the domain;</li>
 *   <li>a bound stated through an alias of {@code min cardinality} counts.</li>
 * </ul>
 * Chains are not followed: a {@code max cardinality} on {@code has part} counts the direct parts, not the
 * parts of parts. {@code replaces} / {@code is replaced by} do not make a second name of a relation — in CDS
 * that is {@code is alias of}. Metadata pairs are not statements, so the engine does not see the
 * {@code range} metadata; its keys are resolved as relations ({@code upperBound ..is alias of.. max cardinality}).
 */
final class Cardinality {

    private static final String MIN = "min cardinality";
    private static final String MAX = "max cardinality";
    private static final String BOTH = "cardinality";
    private static final List<String> DOMAIN = List.of("domain", "doc:domain");
    private static final Set<String> RANGE = Set.of("range", "doc:range");
    /** Relations whose statements only say what something is or is called, not a value. */
    private static final Set<String> NAMING = Set.of(Cds.HAS_SUBTYPE, Cds.HAS_SUPERTYPE, Cds.HAS_INVERSE,
            Cds.IS_ALIAS_OF, Cds.HAS_ALIAS, Cds.SAME_AS);

    /** One bound on a relation and where it is stated. */
    private record Bound(String relation, boolean isMin, int value, List<Location> statedAt) {}

    private final CdsReasoner cds;
    private final List<Finding> out = new ArrayList<>();

    private Cardinality(CdsReasoner cds) {
        this.cds = cds;
    }

    static List<Finding> check(CdsReasoner cds, Map<String, List<LocatedTriple>> byUrl) {
        Cardinality check = new Cardinality(cds);
        List<Bound> bounds = check.bounds(byUrl);
        for (Bound b : bounds) check.check(b);
        return check.out;
    }

    // ---- collecting the bounds -------------------------------------------------------------------

    private List<Bound> bounds(Map<String, List<LocatedTriple>> byUrl) {
        List<Bound> bounds = new ArrayList<>();
        // A relation property: `rel ..min cardinality.. n`, also through aliases and sub-relations.
        for (String key : List.of(MIN, MAX, BOTH)) {
            for (Triple t : cds.match(null, key, null)) {
                addBounds(key, t.object(), t.subject(), stated(t, null), bounds);
            }
        }
        for (Triple t : cds.match(null, Cds.HAS_TYPE, "required")) {
            bounds.add(new Bound(t.subject(), true, 1, stated(t, null)));
        }
        for (Triple t : cds.match(null, Cds.HAS_TYPE, "single valued")) {
            bounds.add(new Bound(t.subject(), false, 1, stated(t, null)));
        }
        // On the range: `rel ..range.. T ,, ..cardinality.. n` -- metadata, so read as written.
        byUrl.forEach((url, triples) -> {
            for (LocatedTriple l : triples) {
                DdotTriple t = l.triple();
                if (RANGE.stream().noneMatch(range -> isA(t.type(), range))) continue;
                for (LocatedTriple.Meta m : l.meta()) {
                    String key = m.relation();
                    for (String bound : List.of(MIN, MAX, BOTH)) if (isA(key, bound)) key = bound;
                    addBounds(key, m.value(), cds.canonical(t.from()), List.of(new Location(url, l.line())), bounds);
                }
            }
        });
        return bounds;
    }

    /**
     * Whether {@code relation} is {@code key}: the same concept, an alias of it, or a sub-relation. A name used
     * only as a metadata key is no relation to the engine, so its alias statement is asked for directly.
     */
    private boolean isA(String relation, String key) {
        if (relation.equals(key) || cds.canonical(relation).equals(cds.canonical(key))) return true;
        if (cds.holds(relation, Cds.IS_ALIAS_OF, key)) return true;
        return cds.isRelation(relation) && cds.isRelation(key) && cds.isSubRelation(relation, key);
    }

    private void addBounds(String key, String rawValue, String relation, List<Location> at, List<Bound> bounds) {
        boolean isMin = MIN.equals(key), isMax = MAX.equals(key), both = BOTH.equals(key);
        if (!isMin && !isMax && !both) return;
        Integer n = parseBound(rawValue);
        if (n == null) {
            for (Location l : at) {
                out.add(Finding.onLine(DdotAnalysis.CARDINALITY_VALUE, l,
                        "'" + key + "' must be a whole number, but is '" + rawValue + "'.", at));
            }
            return;
        }
        if (isMin || both) bounds.add(new Bound(relation, true, n, at));
        if (isMax || both) bounds.add(new Bound(relation, false, n, at));
    }

    private static @Nullable Integer parseBound(String s) {
        String v = s.strip();
        if (!v.matches("\\d{1,9}")) return null;
        return Integer.parseInt(v);
    }

    // ---- checking ----------------------------------------------------------------------------------

    private void check(Bound b) {
        // Instances of the domain, with the lines that make them instances.
        Map<String, String> domainOf = new LinkedHashMap<>();
        for (String key : DOMAIN) {
            for (Triple d : cds.match(b.relation(), key, null)) {
                for (Triple typed : cds.match(null, Cds.HAS_TYPE, d.object())) domainOf.putIfAbsent(typed.subject(), d.object());
            }
        }

        // Scope: every domain instance, and every subject that has a value for the relation.
        Map<String, Set<String>> values = new LinkedHashMap<>();
        for (String instance : domainOf.keySet()) values.put(instance, new LinkedHashSet<>());
        for (Triple t : cds.match(null, b.relation(), null)) {
            values.computeIfAbsent(t.subject(), k -> new LinkedHashSet<>()).add(t.object());
        }

        values.forEach((subject, objects) -> {
            String domain = domainOf.get(subject);
            if (objects.isEmpty()) {
                if (b.isMin() && b.value() > 0) {
                    String message = "'" + subject + "' is a '" + domain + "' and needs at least " + values(b.value())
                            + " for '" + b.relation() + "', but has none.";
                    for (Location at : stated(new Triple(subject, Cds.HAS_TYPE, domain), subject)) {
                        out.add(Finding.onLine(DdotAnalysis.CARDINALITY, at, message, b.statedAt()));
                    }
                }
                return;
            }
            int count = objects.size();
            boolean violated = b.isMin() ? count < b.value() : count > b.value();
            if (!violated) return;
            String as = domain == null ? "" : " (as a '" + domain + "')";
            String message = "'" + subject + "'" + as + " has " + values(count) + " for '" + b.relation() + "', but "
                    + (b.isMin() ? "at least " + b.value() + (b.value() == 1 ? " is" : " are") + " required."
                                 : "at most " + b.value() + (b.value() == 1 ? " is" : " are") + " allowed.");
            Set<Location> lines = new LinkedHashSet<>();
            for (String object : objects) lines.addAll(stated(new Triple(subject, b.relation(), object), subject));
            for (Location at : lines) out.add(Finding.onLine(DdotAnalysis.CARDINALITY, at, message, b.statedAt()));
        });
    }

    /**
     * Where an entailed triple is stated: the read facts its derivation rests on. The statements that only
     * name or place things -- an alias, a subtype edge -- are where the triple holds <em>through</em>, not
     * where it is stated, so they are left out unless nothing else is left.
     *
     * @param about when given, prefer the statements that mention it
     */
    private List<Location> stated(Triple t, @Nullable String about) {
        Optional<Set<IFact>> why = cds.why(t.subject(), t.relation(), t.object());
        if (why.isEmpty()) return List.of();
        List<IFact> read = why.get().stream().filter(IFact::isRead).toList();
        List<IFact> statements = read.stream().filter(f -> !NAMING.contains(f.relation())).toList();
        if (about != null) {
            List<IFact> mentioning = statements.stream()
                    .filter(f -> about.equals(f.subject()) || about.equals(f.object())).toList();
            if (!mentioning.isEmpty()) statements = mentioning;
        }
        if (statements.isEmpty()) statements = read;
        Set<Location> out = new LinkedHashSet<>();
        for (IFact f : statements) out.add(DdotAnalysis.locationOf(f));
        return List.copyOf(out);
    }

    private static String values(int n) {
        return n + (n == 1 ? " value" : " values");
    }
}
