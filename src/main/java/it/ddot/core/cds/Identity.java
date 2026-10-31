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

/**
 * Which names are one concept, and which relation names are one relation read backwards.
 *
 * <p>A union-find with parity. Every name joins a class, and carries a bit saying whether it reads
 * the class forwards (0) or backwards (1). {@code a same as b} joins with parity 0,
 * {@code p has inverse q} with parity 1, and {@code ^p} is {@code p} with the bit flipped. Inverse
 * chains therefore collapse by themselves (r12): {@code p} inverse {@code q} inverse {@code r}
 * puts {@code r} in {@code p}'s class with parity 0.
 *
 * <p>A class that ends up holding a name at both parities is its own inverse: symmetric (r10). From
 * then on the bit means nothing, and {@link #ref} reports 0.
 *
 * <p>Beside the forest it keeps every join with the triples that caused it, so that
 * {@link #explain} can say why two names are one.
 */
final class Identity {

    record Ref(int root, int parity) {}

    private record Edge(int to, List<IFact> why) {}

    private final Map<String, Integer> ids = new HashMap<>();
    private final List<String> names = new ArrayList<>();
    private final List<List<Edge>> edges = new ArrayList<>();
    private int[] parent = new int[64];
    private int[] parity = new int[64];
    private boolean[] symmetric = new boolean[64];

    /** The name without its leading {@code ^}s. */
    static String base(String name) {
        int i = 0;
        while (i < name.length() && name.charAt(i) == '^') i++;
        return name.substring(i);
    }

    /** 1 when the name reads backwards: an odd number of {@code ^}s, since {@code ^^p} is {@code p}. */
    static int flips(String name) {
        int i = 0;
        while (i < name.length() && name.charAt(i) == '^') i++;
        return i & 1;
    }

    int id(String name) {
        String base = base(name);
        Integer known = ids.get(base);
        if (known != null) return known;
        int id = names.size();
        if (id == parent.length) {
            parent = Arrays.copyOf(parent, id * 2);
            parity = Arrays.copyOf(parity, id * 2);
            symmetric = Arrays.copyOf(symmetric, id * 2);
        }
        parent[id] = id;
        names.add(base);
        edges.add(new ArrayList<>());
        ids.put(base, id);
        return id;
    }

    String name(int id) {
        return names.get(id);
    }

    int size() {
        return names.size();
    }

    /** The class of a name, and which way round the name reads it. */
    Ref ref(String name) {
        Ref found = find(id(name));
        if (symmetric[found.root()]) return new Ref(found.root(), 0);
        return new Ref(found.root(), found.parity() ^ flips(name));
    }

    /** Like {@link #ref}, for an id; the parity is the raw one, even in a symmetric class. */
    Ref find(int id) {
        int root = id;
        int toRoot = 0;
        while (parent[root] != root) {
            toRoot ^= parity[root];
            root = parent[root];
        }
        int current = id;
        int currentToRoot = toRoot;
        while (parent[current] != current) {
            int next = parent[current];
            int nextToRoot = currentToRoot ^ parity[current];
            parent[current] = root;
            parity[current] = currentToRoot;
            current = next;
            currentToRoot = nextToRoot;
        }
        return new Ref(root, toRoot);
    }

    boolean isSymmetric(int id) {
        return symmetric[find(id).root()];
    }

    /**
     * States that {@code a} is {@code b} (parity 0) or {@code b} read backwards (parity 1).
     *
     * @return whether anything changed: two classes merged, or a class became symmetric
     */
    boolean union(String a, String b, int relation, List<IFact> why) {
        int idA = id(a);
        int idB = id(b);
        edges.get(idA).add(new Edge(idB, why));
        if (idA != idB) edges.get(idB).add(new Edge(idA, why));
        int wanted = relation ^ flips(a) ^ flips(b);
        Ref refA = find(idA);
        Ref refB = find(idB);
        if (refA.root() == refB.root()) {
            if ((refA.parity() ^ refB.parity()) == wanted || symmetric[refA.root()]) return false;
            symmetric[refA.root()] = true;
            return true;
        }
        parent[refB.root()] = refA.root();
        parity[refB.root()] = refA.parity() ^ wanted ^ refB.parity();
        symmetric[refA.root()] |= symmetric[refB.root()];
        return true;
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
            for (Edge edge : edges.get(node)) {
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
