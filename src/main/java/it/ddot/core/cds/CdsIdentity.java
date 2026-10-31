package it.ddot.core.cds;

import it.ddot.core.store.IFact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which names are one concept, and which relation names are one relation read backwards -- for
 * {@link CdsModel}, safe for any number of readers while no one writes.
 *
 * <p>A union-find with parity. Every name joins a class, and carries a bit saying whether it reads
 * the class forwards (0) or backwards (1). {@code a same as b} joins with parity 0,
 * {@code p has inverse q} with parity 1, and {@code ^p} is {@code p} with the bit flipped. Inverse
 * chains therefore collapse by themselves (r12): {@code p} inverse {@code q} inverse {@code r}
 * puts {@code r} in {@code p}'s class with parity 0. A class that ends up holding a name at both
 * parities is its own inverse: symmetric (r10). Beside the forest it keeps every join with the
 * facts that caused it, so that {@link #explain} can say why two names are one; a name nothing was
 * joined to has no list of joins at all, which is most of them.
 *
 * <h2>Threads</h2>
 * Questions change it in one way: asking about a name nobody used registers it. That is
 * synchronized, and published through a concurrent map, with the arrays it grows written before
 * and read after. What else writes -- joining classes, and compressing the paths to their roots --
 * happens only while the engine holds its write lock, which {@link #writing} marks: paths are
 * compressed as they are walked then, and all of them when writing ends, so that a question only
 * ever reads.
 *
 * <p>{@link Identity} is the oracle's version: the same forest, without the threads.
 */
final class CdsIdentity {

    record Ref(int root, int parity) {}

    private record Edge(int to, List<IFact> why) {}

    private final Map<String, Integer> ids = new ConcurrentHashMap<>();
    /** Joins, by name: written only while writing. */
    private final Map<Integer, List<Edge>> edges = new HashMap<>();
    private volatile String[] names = new String[64];
    private volatile int[] parent = new int[64];
    private volatile int[] parity = new int[64];
    private volatile boolean[] symmetric = new boolean[64];
    private volatile int size;
    private boolean writing;
    /**
     * The roots joined into another since {@link #joined} last emptied it, and the roots that became
     * their own inverse: what a model must move, since its nodes are roots.
     */
    private final List<Integer> joined = new ArrayList<>();
    private final List<Integer> madeSymmetric = new ArrayList<>();

    static String base(String name) {
        int i = 0;
        while (i < name.length() && name.charAt(i) == '^') i++;
        return name.substring(i);
    }

    static int flips(String name) {
        int i = 0;
        while (i < name.length() && name.charAt(i) == '^') i++;
        return i & 1;
    }

    /** Marks the start or end of a write; at the end every path is compressed to its root. */
    void writing(boolean writing) {
        this.writing = writing;
        if (!writing) compressAll();
    }

    int id(String name) {
        String base = base(name);
        Integer known = ids.get(base);
        if (known != null) return known;
        synchronized (this) {
            known = ids.get(base);
            if (known != null) return known;
            int id = size;
            if (id == parent.length) {
                int grown = id * 2;
                names = Arrays.copyOf(names, grown);
                parity = Arrays.copyOf(parity, grown);
                symmetric = Arrays.copyOf(symmetric, grown);
                int[] parents = Arrays.copyOf(parent, grown);
                parents[id] = id;
                parent = parents;
            } else {
                parent[id] = id;
            }
            names[id] = base;
            size = id + 1;
            ids.put(base, id);
            return id;
        }
    }

    String name(int id) {
        return names[id];
    }

    int size() {
        return size;
    }

    Ref ref(String name) {
        Ref found = find(id(name));
        if (symmetric[found.root()]) return new Ref(found.root(), 0);
        return new Ref(found.root(), found.parity() ^ flips(name));
    }

    /** The class of an id; compresses the path only while writing, so that a question only reads. */
    Ref find(int id) {
        int[] parents = parent;
        int[] parities = parity;
        int root = id;
        int toRoot = 0;
        while (parents[root] != root) {
            toRoot ^= parities[root];
            root = parents[root];
        }
        if (writing) {
            int current = id;
            int currentToRoot = toRoot;
            while (parents[current] != current) {
                int next = parents[current];
                int nextToRoot = currentToRoot ^ parities[current];
                parents[current] = root;
                parities[current] = currentToRoot;
                current = next;
                currentToRoot = nextToRoot;
            }
        }
        return new Ref(root, toRoot);
    }

    private void compressAll() {
        boolean was = writing;
        writing = true;
        for (int id = 0; id < size; id++) find(id);
        writing = was;
    }

    boolean isSymmetric(int id) {
        return symmetric[find(id).root()];
    }

    /** Only while writing. */
    boolean union(String a, String b, int relation, List<IFact> why) {
        int idA = id(a);
        int idB = id(b);
        edgesOf(idA).add(new Edge(idB, why));
        if (idA != idB) edgesOf(idB).add(new Edge(idA, why));
        int wanted = relation ^ flips(a) ^ flips(b);
        Ref refA = find(idA);
        Ref refB = find(idB);
        if (refA.root() == refB.root()) {
            if ((refA.parity() ^ refB.parity()) == wanted || symmetric[refA.root()]) return false;
            symmetric[refA.root()] = true;
            madeSymmetric.add(refA.root());
            return true;
        }
        parent[refB.root()] = refA.root();
        parity[refB.root()] = refA.parity() ^ wanted ^ refB.parity();
        joined.add(refB.root());
        if (symmetric[refB.root()] && !symmetric[refA.root()]) {
            symmetric[refA.root()] = true;
            madeSymmetric.add(refA.root());
        }
        return true;
    }

    /** The roots joined into another since the last call, in the order they were. */
    List<Integer> drainJoined() {
        List<Integer> out = List.copyOf(joined);
        joined.clear();
        return out;
    }

    /** The roots that became their own inverse since the last call; each may have been joined since. */
    List<Integer> drainSymmetric() {
        List<Integer> out = List.copyOf(madeSymmetric);
        madeSymmetric.clear();
        return out;
    }

    private List<Edge> edgesOf(int id) {
        return edges.computeIfAbsent(id, k -> new ArrayList<>(1));
    }

    /** Whether nothing was ever joined to this name. */
    boolean isAlone(int id) {
        return !edges.containsKey(id);
    }

    /** The triples that make two names one; empty when they are the same name. */
    List<IFact> explain(String from, String to) {
        int start = id(from);
        int goal = id(to);
        if (start == goal) return List.of();
        Map<Integer, Integer> previous = new HashMap<>();
        Map<Integer, List<IFact>> via = new HashMap<>();
        Deque<Integer> queue = new ArrayDeque<>(List.of(start));
        previous.put(start, start);
        while (!queue.isEmpty()) {
            int node = queue.poll();
            List<Edge> joins = edges.get(node);
            if (joins == null) continue;
            for (Edge edge : joins) {
                if (previous.containsKey(edge.to())) continue;
                previous.put(edge.to(), node);
                via.put(edge.to(), edge.why());
                if (edge.to() == goal) {
                    Set<IFact> out = new LinkedHashSet<>();
                    for (int at = goal; at != start; at = previous.get(at)) out.addAll(via.get(at));
                    return List.copyOf(out);
                }
                queue.add(edge.to());
            }
        }
        return List.of();
    }
}
