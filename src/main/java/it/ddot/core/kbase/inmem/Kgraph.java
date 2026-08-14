package it.ddot.core.kbase.inmem;

import it.ddot.core.kbase.IKnowledgeGraph;
import it.ddot.core.kbase.Kendpoint;
import com.graphinout.foundation.pure.collections.IdFactory;
import com.graphinout.foundation.pure.json.document.IJsonFactory;
import com.graphinout.foundation.pure.json.query.JsonQuery;
import com.graphinout.foundation.pure.json.query.JsonQueryEngine;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.slf4j.LoggerFactory.getLogger;

// IMPROVE later we need a DB impl, too

/**
 * In-memory knowledge graph.
 *
 * <p><b>Thread-safety:</b> structural mutations ({@link #addNode}, {@link #addEdge}, {@link #removeNode},
 * {@link #removeEdge}, {@link #biEdge}/{@link #addBiEdge}, {@link #getOrCreateNodeWithIdAndLabel}) are
 * {@code synchronized} on the instance, so concurrent writers (e.g. the multi-threaded indexer
 * {@code Scheduler}) cannot corrupt the node/edge collections or the SPO index. Reads are <em>not</em>
 * guarded; callers must establish a happens-before barrier (the indexer does this via
 * {@code Scheduler.awaitQuiescence}) before querying a graph that was concurrently written.
 */
public class Kgraph implements IKnowledgeGraph {

    private static final Logger log = getLogger(Kgraph.class);

    // ---- Pattern / Query types -----------------------------------------------

    public static abstract sealed class Slot permits Constant, Variable, OneOf, Prefix, Initials {

        public abstract boolean matches(String s);

    }

    public static final class Constant extends Slot {

        public final String value;

        public Constant(String value) {this.value = value;}

        @Override
        public boolean matches(String s) {return value.equals(s);}

    }

    /**
     * A query variable, identified by {@link #name}. Equality is by name (not object identity), so a
     * {@link Match} binding can be read with any {@code Variable} instance carrying the same name — e.g.
     * {@code match.get(new Variable("x"))} or {@link Match#get(String)} — not only the exact instance used to
     * build the query.
     */
    public static final class Variable extends Slot {

        public final String name;

        public Variable(String name) {this.name = name;}

        @Override
        public boolean matches(String s) {return true;}

        @Override
        public boolean equals(Object o) {
            return o instanceof Variable v && name.equals(v.name);
        }

        @Override
        public int hashCode() {return name.hashCode();}

        @Override
        public String toString() {return "?" + name;}

    }

    public static final class OneOf extends Slot {

        public final Set<String> values;

        public OneOf(Set<String> values) {this.values = values;}

        @Override
        public boolean matches(String s) {return values.contains(s);}

    }

    /** Matches any value that starts with {@code prefix} (case-insensitive). */
    public static final class Prefix extends Slot {

        public final String prefix;

        public Prefix(String prefix) {this.prefix = prefix;}

        @Override
        public boolean matches(String s) {
            return s.toLowerCase().startsWith(prefix.toLowerCase());
        }

    }

    /**
     * Matches any value whose consecutive word-initial letters spell out {@code initials}
     * (case-insensitive). E.g. {@code "wa"} matches {@code "works at"}.
     */
    public static final class Initials extends Slot {

        public final String initials;

        public Initials(String initials) {this.initials = initials;}

        @Override
        public boolean matches(String s) {
            String[] words = s.toLowerCase().split("\\s+");
            String p = initials.toLowerCase();
            if (p.length() > words.length) return false;
            for (int i = 0; i < p.length(); i++) {
                if (words[i].isEmpty() || words[i].charAt(0) != p.charAt(i)) return false;
            }
            return true;
        }

    }

    public record Pattern(Slot s, Slot p, Slot o) {

        public void forEachSlot(Consumer<Slot> consumer) {
            consumer.accept(s);
            consumer.accept(p);
            consumer.accept(o);
        }

    }

    public record Query(List<Pattern> patterns) {}

    public enum NodeMatchMode { ID, LABEL, BOTH }

    public static class Match {

        final Map<Variable, String> bindings = new HashMap<>();

        public String get(Variable v) {return bindings.get(v);}

        /** Reads a binding by variable name (equivalent to {@code get(new Variable(name))}). */
        public String get(String variableName) {return bindings.get(new Variable(variableName));}

        public void put(Variable v, String value) {bindings.put(v, value);}

        public void forEach(java.util.function.BiConsumer<Variable, String> consumer) {bindings.forEach(consumer);}

    }

    public static class QueryResult {

        public final List<Match> matches = new ArrayList<>();

    }

    // ---- Fields --------------------------------------------------------------

    private final String id;
    private final Map<String, Knode> nodes = new TreeMap<>();
    private final List<Kedge> edges = new ArrayList<>();
    /** SPO index: subject-id → predicate-id → object-id → edges */
    private final Map<String, Map<String, Map<String, Set<Kedge>>>> spo = new TreeMap<>();
    private final IdFactory idFactory = IdFactory.createCounting("n-");
    private final IJsonFactory jsonFactory = IJsonFactory.INSTANCE;
    private Klabel label;

    public Kgraph(String id) {this.id = id;}

    // ---- Node / edge mutation ------------------------------------------------

    private static Slot bindSlot(Slot slot, Match match) {
        if (slot instanceof Variable v && match.bindings.containsKey(v)) {
            return new Constant(match.bindings.get(v));
        }
        return slot;
    }

    /** Object id: the {@code OUT}-direction endpoint's node id. */
    public static @Nullable String objectIdOf(Kedge e) {
        return e.endpoints().stream()
                .filter(ep -> ep.kdirection == Kendpoint.Kdirection.out)
                .findFirst().map(ep -> ep.node.id()).orElse(null);
    }

    private static void pattern_o(Map<String, Set<Kedge>> index_o, Slot o, Consumer<Kedge> consumer) {
        if (o instanceof Constant c) {
            Set<Kedge> set = index_o.get(c.value);
            if (set != null) set.forEach(consumer);
        } else {
            index_o.forEach((key, set) -> { if (o.matches(key)) set.forEach(consumer); });
        }
    }

    private static void pattern_po(Map<String, Map<String, Set<Kedge>>> index_po, Slot p, Slot o, Consumer<Kedge> consumer) {
        if (p instanceof Constant c) {
            Map<String, Set<Kedge>> o_map = index_po.get(c.value);
            if (o_map != null) pattern_o(o_map, o, consumer);
        } else {
            index_po.forEach((key, o_map) -> { if (p.matches(key)) pattern_o(o_map, o, consumer); });
        }
    }

    // ---- Streams -------------------------------------------------------------

    public static void pattern_spo(Map<String, Map<String, Map<String, Set<Kedge>>>> spo, Pattern pattern, Consumer<Kedge> consumer) {
        if (pattern.s() instanceof Constant c) {
            Map<String, Map<String, Set<Kedge>>> po = spo.get(c.value);
            if (po != null) pattern_po(po, pattern.p(), pattern.o(), consumer);
        } else {
            spo.forEach((key, po) -> { if (pattern.s().matches(key)) pattern_po(po, pattern.p(), pattern.o(), consumer); });
        }
    }

    /** Predicate id: type of the {@code IN}-direction endpoint, or {@code "links to"}. */
    public static String predicateIdOf(Kedge e) {
        return e.endpoints().stream()
                .filter(ep -> ep.kdirection == Kendpoint.Kdirection.in && ep.type != null)
                .findFirst().map(ep -> ep.type.id())
                .orElse("links to");
    }

    // ---- Node lookup / creation ---------------------------------------------

    /**
     * Greedy sort: patterns with already-bound variables first (score +100 per bound var), then patterns with
     * {@link OneOf} predicates (score +50).
     */
    private static List<Pattern> sortedPatterns(Query query) {
        Set<Variable> boundVars = new HashSet<>();
        List<Pattern> sorted = new ArrayList<>();
        List<Pattern> remaining = new ArrayList<>(query.patterns());
        while (!remaining.isEmpty()) {
            Pattern best = null;
            int bestScore = -1;
            for (Pattern p : remaining) {
                int[] score = {0};
                p.forEachSlot(slot -> {
                    if (slot instanceof Variable v && boundVars.contains(v)) score[0] += 100;
                });
                if (p.p() instanceof OneOf) score[0] += 50;
                if (score[0] > bestScore) {
                    bestScore = score[0];
                    best = p;
                }
            }
            remaining.remove(best);
            sorted.add(best);
            if (best != null) {
                best.forEachSlot(slot -> {if (slot instanceof Variable v) boundVars.add(v);});
            }
        }
        return sorted;
    }

    /** Subject id: the {@code IN}-direction endpoint's node id. */
    public static @Nullable String subjectIdOf(Kedge e) {
        return e.endpoints().stream()
                .filter(ep -> ep.kdirection == Kendpoint.Kdirection.in)
                .findFirst().map(ep -> ep.node.id()).orElse(null);
    }

    private static boolean tryBind(Slot slot, String value, Match match) {
        if (slot instanceof Variable v) {
            String bound = match.bindings.get(v);
            if (bound == null) {
                match.bindings.put(v, value);
                return true;
            }
            return bound.equals(value);
        }
        return slot.matches(value);
    }

    /**
     * Adds a subject–predicate–object triple as a {@link Kedge}. The predicate node is created (or reused) by id and
     * attached as the endpoint type.
     */
    public void addBiEdge(Knode subject, String predicate, Knode object) {
        biEdge(subject, predicate, object);
    }

    /**
     * Like {@link #addBiEdge} but returns the created edge, so callers can attach data or
     * {@link it.ddot.core.source.ISourceLocation source provenance} to it.
     */
    public synchronized Kedge biEdge(Knode subject, String predicate, Knode object) {
        Knode pNode = getOrCreateNodeWithIdAndLabel(predicate);
        Kedge edge = new Kedge();
        edge.addEndpoint(Kendpoint.Kdirection.in, subject, pNode);
        edge.addEndpoint(Kendpoint.Kdirection.out, object, pNode);
        addEdge(edge);
        return edge;
    }

    /** Adds an edge and maintains the SPO index. */
    public synchronized void addEdge(Kedge edge) {
        edges.add(edge);
        indexEdge(edge);
        int n = edges.size();
        if (n % 10000 == 0) log.info("[{}] {} edges", id, n);
    }

    /** Removes an edge and updates the SPO index. */
    public synchronized void removeEdge(Kedge edge) {
        edges.remove(edge);
        unindexEdge(edge);
    }

    public synchronized void addNode(Knode node) {
        nodes.put(node.id(), node);
        int n = nodes.size();
        if (n % 5000 == 0) log.info("[{}] {} nodes", id, n);
    }

    public synchronized void removeNode(Knode node) {nodes.remove(node.id());}

    /**
     * Removes everything that originated solely from {@code resourceUri}, plus all inferred edges. See
     * {@link IKnowledgeGraph#removeBySource(String)} for the full contract.
     */
    @Override
    public synchronized RemoveBySourceResult removeBySource(String resourceUri) {
        long t0 = System.currentTimeMillis();

        // Edges: drop matching provenance; delete any edge thereby left without provenance.
        List<Kedge> edgesToRemove = new ArrayList<>();
        for (Kedge edge : edges) {
            boolean had = edge.removeSourceLocationsOf(resourceUri);
            if (had && edge.sourceLocations().isEmpty()) edgesToRemove.add(edge);
        }
        // Inferred edges have no provenance and may now rest on removed premises: discard them all.
        // (Disjoint from edgesToRemove, since inferred edges never carry source locations.)
        List<Kedge> inferredToRemove = edges.stream().filter(Kedge::isInferred).toList();
        edgesToRemove.forEach(this::removeEdge);
        inferredToRemove.forEach(this::removeEdge);

        // Nodes: drop matching provenance; delete any node thereby left without provenance. A node still
        // referenced by a surviving edge keeps that edge's provenance, so it will not be emptied here.
        List<Knode> nodesToRemove = new ArrayList<>();
        for (Knode node : nodes.values()) {
            boolean had = node.removeSourceLocationsOf(resourceUri);
            if (had && node.sourceLocations().isEmpty()) nodesToRemove.add(node);
        }
        nodesToRemove.forEach(this::removeNode);

        RemoveBySourceResult result = new RemoveBySourceResult(
                edgesToRemove.size(), nodesToRemove.size(), inferredToRemove.size());
        log.info("[{}] removeBySource({}) {}ms -> {}", id, resourceUri, System.currentTimeMillis() - t0, result);
        return result;
    }

    public Kedge createEdge() {return new Kedge();}

    public String createId() {return idFactory.createId();}

    public Stream<Kedge> edges() {return edges.stream();}

    // ---- SPO helpers ---------------------------------------------------------

    public @Nullable Knode findNodeById(String id) {return nodes.get(id);}

    public Stream<Knode> findNodesByLabel(String label) {
        return nodes().filter(node -> node.hasLabel(label));
    }

    public Knode getOrCreateNodeWithIdAndLabel(String s) {
        return getOrCreateNodeWithIdAndLabel(s, s);
    }

    public synchronized Knode getOrCreateNodeWithIdAndLabel(String id, String label) {
        Knode node = findNodeById(id);
        if (node != null) return node;
        node = new Knode(id);
        node.addLabelWithoutLanguage(label);
        addNode(node);
        return node;
    }

    // ---- Pattern matching (static, index-agnostic) --------------------------

    public boolean hasItem(String id) {return nodes.containsKey(id);}

    public String id() {return id;}

    /**
     * Sub-type inference: {@code A type S, T has-subtype S ⟹ A type T}. Newly inferred triples are added to the graph
     * via {@link #addBiEdge}.
     */
    public void inference() {
        long t0 = System.currentTimeMillis();
        int edgesBefore = edges.size();
        Variable x = new Variable("x"), z = new Variable("z"), u = new Variable("u");
        Slot relType = new OneOf(Set.of("has type", "type", "is a"));
        Slot relSubtype = new OneOf(Set.of("has subtype", "subtype"));
        Query q = new Query(List.of(
                new Pattern(x, relType, z),
                new Pattern(u, relSubtype, z)
        ));
        QueryResult res = query(q);

        // Collect first to avoid modifying the index while iterating it
        List<String[]> inferred = new ArrayList<>();
        res.matches.forEach(match -> {
            String xv = match.get(x);
            String uv = match.get(u);
            if (xv != null && uv != null) inferred.add(new String[]{xv, "has type", uv});
        });
        inferred.forEach(triple -> {
            Kedge e = biEdge(
                    getOrCreateNodeWithIdAndLabel(triple[0]),
                    triple[1],
                    getOrCreateNodeWithIdAndLabel(triple[2]));
            e.inferred(true);
        });
        log.info("[{}] inference() {}ms, +{} edges inferred", id, System.currentTimeMillis() - t0, edges.size() - edgesBefore);
    }

    public IJsonFactory jsonFactory() {
        return jsonFactory;
    }

    // ---- Public query API ---------------------------------------------------

    public Klabel label() {return label;}

    public void label(Klabel label) {this.label = label;}

    public Stream<Knode> nodes() {return nodes.values().stream();}

    public Stream<Knode> nodesMatchingSlot(Slot slot, NodeMatchMode mode) {
        if (slot instanceof Variable) return nodes();
        Set<String> ids = nodes.values().stream()
                .filter(n -> {
                    boolean matchesId = mode != NodeMatchMode.LABEL && slot.matches(n.id());
                    boolean matchesLabel = mode != NodeMatchMode.ID && (n.label() instanceof Klabel kl
                            && kl.entries().stream().anyMatch(e -> slot.matches(e.value)));
                    return matchesId || matchesLabel;
                })
                .map(Knode::id)
                .collect(Collectors.toSet());
        return ids.stream().map(nodes::get).filter(java.util.Objects::nonNull);
    }

    // ---- Query internals ----------------------------------------------------

    @Override
    public Stream<Knode> nodesMatchingData(JsonQuery query) {
        return nodes().filter(n -> n.data() != null && JsonQueryEngine.matches(query, n.data()));
    }

    @Override
    public Stream<Kedge> edgesMatchingData(JsonQuery query) {
        return edges().filter(e -> e.data() != null && JsonQueryEngine.matches(query, e.data()));
    }

    /** Returns all edges whose SPO matches {@code pattern}. */
    public List<Kedge> pattern(Pattern pattern) {
        long t0 = System.currentTimeMillis();
        List<Kedge> results = new ArrayList<>();
        pattern_spo(spo, expandPattern(pattern), results::add);
        long ms = System.currentTimeMillis() - t0;
        if (ms > 50) log.warn("[{}] pattern() {}ms -> {} results", id, ms, results.size());
        else log.debug("[{}] pattern() {}ms -> {} results", id, ms, results.size());
        return results;
    }

    /**
     * Expands {@link Prefix} and {@link Initials} slots into {@link OneOf} by scanning all nodes
     * and matching against both their id and their label text. All other slot types pass through unchanged.
     */
    private Pattern expandPattern(Pattern p) {
        return new Pattern(expandLabelMatches(p.s()), expandLabelMatches(p.p()), expandLabelMatches(p.o()));
    }

    private Slot expandLabelMatches(Slot slot) {
        if (!(slot instanceof Prefix || slot instanceof Initials)) return slot;
        Set<String> ids = nodes.values().stream()
                .filter(n -> slot.matches(n.id()) || (n.label() instanceof Klabel kl
                        && kl.entries().stream().anyMatch(e -> slot.matches(e.value))))
                .map(Knode::id)
                .collect(Collectors.toSet());
        return new OneOf(ids);
    }

    /** Evaluates a conjunctive {@link Query} and returns all variable bindings. */
    public QueryResult query(Query query) {
        long t0 = System.currentTimeMillis();
        List<Pattern> sorted = sortedPatterns(query);
        QueryResult result = new QueryResult();
        executePattern(sorted, 0, new Match(), result);
        long ms = System.currentTimeMillis() - t0;
        if (ms > 50) log.warn("[{}] query() {}ms -> {} matches", id, ms, result.matches.size());
        else log.debug("[{}] query() {}ms -> {} matches", id, ms, result.matches.size());
        return result;
    }

    private Pattern bind(Pattern p, Match match) {
        return new Pattern(bindSlot(p.s(), match), bindSlot(p.p(), match), bindSlot(p.o(), match));
    }

    private void executePattern(List<Pattern> patterns, int index, Match currentMatch, QueryResult result) {
        if (index == patterns.size()) {
            Match finalMatch = new Match();
            finalMatch.bindings.putAll(currentMatch.bindings);
            result.matches.add(finalMatch);
            return;
        }
        Pattern p = patterns.get(index);
        pattern_spo(spo, expandPattern(bind(p, currentMatch)), edge -> {
            String s = subjectIdOf(edge);
            String pred = predicateIdOf(edge);
            String o = objectIdOf(edge);
            if (s == null || o == null) return;
            Match nextMatch = new Match();
            nextMatch.bindings.putAll(currentMatch.bindings);
            if (tryBind(p.s(), s, nextMatch)
                    && tryBind(p.p(), pred, nextMatch)
                    && tryBind(p.o(), o, nextMatch)) {
                executePattern(patterns, index + 1, nextMatch, result);
            }
        });
    }

    private void unindexEdge(Kedge edge) {
        String s = subjectIdOf(edge);
        String p = predicateIdOf(edge);
        String o = objectIdOf(edge);
        if (s == null || o == null) return;
        Map<String, Map<String, Set<Kedge>>> po = spo.get(s);
        if (po == null) return;
        Map<String, Set<Kedge>> o_map = po.get(p);
        if (o_map == null) return;
        Set<Kedge> set = o_map.get(o);
        if (set != null) set.remove(edge);
    }

    private void indexEdge(Kedge edge) {
        String s = subjectIdOf(edge);
        String p = predicateIdOf(edge);
        String o = objectIdOf(edge);
        if (s == null || o == null) return;
        spo.computeIfAbsent(s, k -> new TreeMap<>())
                .computeIfAbsent(p, k -> new HashMap<>())
                .computeIfAbsent(o, k -> new HashSet<>())
                .add(edge);
    }

}
