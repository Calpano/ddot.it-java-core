package it.ddot.core.cds;

import it.ddot.core.store.TripleStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static it.ddot.core.cds.Cds.*;

/**
 * Knowledge graphs to test and measure engines on: real ones, from adir reading a project, and
 * synthetic ones, grown from a seed to stress one thing each.
 *
 * <p>Reading a real project takes adir, which depends on this library, not the other way round. So
 * real corpora come from a {@link RealCorpusReader} found on the test classpath: adir's tests
 * register one, and run the real-corpus contracts through this module's test-jar. Here there is
 * none, and every real corpus counts as not checked out.
 *
 * <p>Synthetic corpora are the same for the same seed and scale, so a failure found on one can be
 * reproduced, and a measurement repeated.
 */
final class CdsCorpora {

    /** A named way to get a store. Real ones are indexed once per JVM and shared. */
    record Corpus(String name, Supplier<TripleStore<?>> store) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Path PROJECTS = Path.of(System.getProperty("user.home"), "_data_", "_git", "GitHub");

    /** The projects named for this: small enough to index in seconds, real enough to matter. */
    static final List<String> REAL = List.of("adir", "backstart-conf", "backstart-app");

    private static final Map<String, TripleStore<?>> indexed = new ConcurrentHashMap<>();

    /** Reads a checked-out project into a store. Registered as a {@link ServiceLoader} service. */
    public interface RealCorpusReader {
        TripleStore<?> read(Path project);
    }

    private static final Optional<RealCorpusReader> READER =
            ServiceLoader.load(RealCorpusReader.class).findFirst();

    private CdsCorpora() {}

    static boolean exists(String project) {
        return READER.isPresent() && Files.isDirectory(PROJECTS.resolve(project));
    }

    /** What adir makes of a project: its {@code KnowledgeGraph}, with adir's own vocabulary. */
    static TripleStore<?> real(String project) {
        return indexed.computeIfAbsent(project, name -> READER.orElseThrow().read(PROJECTS.resolve(name)));
    }

    static List<Corpus> synthetic(int scale) {
        return List.of(
                new Corpus("types-" + scale, () -> types(1, 20 * scale, 0.15, 50 * scale)),
                new Corpus("deep-types-" + scale, () -> deepTypes(2, 20 * scale, 50 * scale)),
                new Corpus("relations-" + scale, () -> relations(3, 4 * scale, 10 * scale, 50 * scale)),
                new Corpus("parts-" + scale, () -> parts(4, 30 * scale)),
                new Corpus("identity-" + scale, () -> identity(5, 20 * scale)),
                new Corpus("late-identity-" + scale, () -> lateIdentity(7, 20 * scale)),
                new Corpus("derived-chains-" + scale, () -> derivedChains(8, 10 * scale)),
                new Corpus("mixed-" + scale, () -> mixed(6, scale)),
                new Corpus("joins-" + scale, () -> joins(9, 100 * scale)));
    }

    // ---- generators ----------------------------------------------------------------------------

    /**
     * A type DAG: every type below an earlier one, some below two, a few aliases and one subtype
     * cycle; instances typed through all of them, some with {@code has instance} written the other
     * way round.
     */
    static MemoryTripleStore types(long seed, int types, double extraParent, int instances) {
        Random random = new Random(seed);
        MemoryTripleStore store = new MemoryTripleStore();
        for (int i = 1; i < types; i++) {
            store.add(type(parent(random, i)), HAS_SUBTYPE, type(i));
            if (i > 2 && random.nextDouble() < extraParent) store.add(type(random.nextInt(i)), HAS_SUBTYPE, type(i));
            if (random.nextDouble() < 0.05) store.add("alias of " + type(i), IS_ALIAS_OF, type(i));
        }
        if (types > 10) store.add(type(types - 1), HAS_SUBTYPE, type(types - 5)); // cycle, if on one path
        for (int i = 0; i < instances; i++) {
            int t = random.nextInt(types);
            if (random.nextBoolean()) store.add(thing(i), HAS_TYPE, type(t));
            else store.add(type(t), HAS_INSTANCE, thing(i));
        }
        return store;
    }

    /** A few long chains of types, so that r04 walks far. */
    static MemoryTripleStore deepTypes(long seed, int depth, int instances) {
        Random random = new Random(seed);
        MemoryTripleStore store = new MemoryTripleStore();
        for (int chain = 0; chain < 3; chain++) {
            for (int i = 1; i < depth; i++) store.add(type(chain * depth + i - 1), HAS_SUBTYPE, type(chain * depth + i));
        }
        for (int i = 0; i < instances; i++) store.add(thing(i), HAS_TYPE, type(random.nextInt(3 * depth)));
        return store;
    }

    /**
     * User relations everywhere in the hierarchy: under the vocabulary's and under each other, some
     * with two parents, explicit inverses, aliases, symmetric and transitive declarations -- and a
     * few under meta relations, which only the meta fixpoint can find.
     */
    static MemoryTripleStore relations(long seed, int relations, int nodes, int facts) {
        Random random = new Random(seed);
        MemoryTripleStore store = new MemoryTripleStore();
        List<String> parents = new ArrayList<>(List.of(RELATED, LINKS_TO, HAS_DETAIL, ANNOTATES, HAS_NEXT, HAS_PART,
                HAS_SIMILAR, IS_TAG_OF, IS_LINKED_FROM, HAS_CONTEXT));
        List<String> usable = new ArrayList<>(List.of(LINKS_TO, HAS_PART, HAS_NEXT, HAS_TAG, HAS_TYPE, HAS_DETAIL,
                IS_PART_OF, REPLACES));
        for (int i = 0; i < relations; i++) {
            String relation = "rel " + i;
            double meta = random.nextDouble();
            String parent = meta < 0.03 ? HAS_SUBTYPE : meta < 0.05 ? IS_ALIAS_OF : pick(random, parents);
            store.add(parent, HAS_SUBTYPE, relation);
            if (parent.equals(IS_ALIAS_OF)) {
                // An alias relation the meta fixpoint must find -- but not one to state random
                // triples with: random aliases between things make, by the spec's r06, every two
                // aliases of one name aliases of each other, a dense alias graph no real one is,
                // and a compile quadratic in it. See doc/cds-inf-perf.adoc, "Dense aliases".
                continue;
            }
            if (random.nextDouble() < 0.1) store.add(pick(random, parents), HAS_SUBTYPE, relation);
            if (random.nextDouble() < 0.3) {
                store.add(relation, HAS_INVERSE, "inverse of " + relation);
                usable.add("inverse of " + relation);
            }
            if (random.nextDouble() < 0.1) store.add(relation, HAS_TYPE, SYMMETRIC_RELATION);
            if (random.nextDouble() < 0.1) store.add(relation, HAS_TYPE, TRANSITIVE_RELATION);
            if (random.nextDouble() < 0.05) {
                store.add("alias of " + relation, IS_ALIAS_OF, relation);
                usable.add("alias of " + relation);
            }
            parents.add(relation);
            usable.add(relation);
        }
        for (int i = 0; i < facts; i++) {
            String relation = pick(random, usable);
            if (random.nextDouble() < 0.1) relation = "^" + relation;
            store.add(thing(random.nextInt(nodes)), relation, thing(random.nextInt(nodes)));
        }
        return store;
    }

    /** Part trees with tags and types on the wholes, and sequences, for r01 and r18. */
    static MemoryTripleStore parts(long seed, int wholes) {
        Random random = new Random(seed);
        MemoryTripleStore store = new MemoryTripleStore();
        for (int w = 0; w < wholes; w++) {
            String whole = "whole " + w;
            store.add(whole, HAS_TAG, "tag " + random.nextInt(10));
            if (random.nextBoolean()) store.add(whole, HAS_TYPE, type(random.nextInt(5)));
            List<String> level = List.of(whole);
            for (int depth = 0; depth < 4; depth++) {
                List<String> next = new ArrayList<>();
                for (String part : level) {
                    for (int k = 0; k < 2; k++) {
                        String child = part + "." + k;
                        if (random.nextBoolean()) store.add(part, HAS_PART, child);
                        else store.add(child, IS_PART_OF, part);
                        next.add(child);
                    }
                }
                level = next;
            }
            store.add(whole, HAS_NEXT, "whole " + (w + 1));
        }
        store.add(type(0), HAS_SUBTYPE, type(1));
        return store;
    }

    /** Same-as clusters and alias chains, with facts stated about every name. */
    static MemoryTripleStore identity(long seed, int clusters) {
        Random random = new Random(seed);
        MemoryTripleStore store = new MemoryTripleStore();
        for (int c = 0; c < clusters; c++) {
            int size = 2 + random.nextInt(4);
            for (int k = 1; k < size; k++) {
                String a = "c" + c + "n" + (k - 1);
                String b = "c" + c + "n" + k;
                if (random.nextBoolean()) store.add(a, SAME_AS, b);
                else store.add(a, IS_ALIAS_OF, b);
            }
            for (int k = 0; k < size; k++) {
                store.add("c" + c + "n" + k, LINKS_TO, "c" + random.nextInt(clusters) + "n0");
            }
        }
        store.add("p", HAS_INVERSE, "q");
        store.add("q", HAS_INVERSE, "r");
        store.add("c0n0", "r", "c1n0");
        return store;
    }

    /**
     * Identity stated last, as a real graph states it: first what is said about many names -- links,
     * types, parts -- and only then that some of them are one, by {@code same as} or an alias. A
     * Java file and the type it declares are stated about for a while before adir folds them.
     */
    static MemoryTripleStore lateIdentity(long seed, int names) {
        Random random = new Random(seed);
        MemoryTripleStore store = new MemoryTripleStore();
        for (int i = 0; i < names; i++) {
            store.add("n" + i, LINKS_TO, "n" + random.nextInt(names));
            store.add("n" + i, random.nextBoolean() ? HAS_TYPE : HAS_TAG, type(random.nextInt(5)));
            if (random.nextInt(4) == 0) store.add("n" + i, HAS_PART, "n" + random.nextInt(names));
        }
        store.add(type(0), HAS_SUBTYPE, type(1));
        for (int i = 0; i < names / 3; i++) {
            String a = "n" + random.nextInt(names);
            String b = "n" + random.nextInt(names);
            switch (random.nextInt(3)) {
                case 0 -> store.add(a, SAME_AS, b);
                case 1 -> store.add(a, IS_ALIAS_OF, b);
                default -> store.add(b, HAS_ALIAS, a);
            }
            if (random.nextInt(5) == 0) store.add("n" + random.nextInt(names), LINKS_TO, a);
        }
        return store;
    }

    /**
     * Chains whose steps another rule derived. A user relation declared transitive sits above
     * {@code has type}, another above {@code has tag}: a thing typed {@code Poodle} is then
     * {@code is classified as Dog} by type inheritance (r04), and further along whatever
     * {@code Dog} is classified as -- a chain the spec's r01 follows over derived triples.
     */
    static MemoryTripleStore derivedChains(long seed, int things) {
        Random random = new Random(seed);
        MemoryTripleStore store = new MemoryTripleStore();
        store.add("is classified as", HAS_TYPE, TRANSITIVE_RELATION);
        store.add("is classified as", HAS_SUBTYPE, HAS_TYPE);
        store.add("is labelled", HAS_TYPE, TRANSITIVE_RELATION);
        store.add("is labelled", HAS_SUBTYPE, HAS_TAG);
        int types = Math.max(4, things / 4);
        for (int t = 1; t < types; t++) {
            store.add(type(t), HAS_SUPERTYPE, type(random.nextInt(t)));
            if (random.nextInt(3) == 0) store.add(type(t), "is classified as", "class " + random.nextInt(types));
        }
        for (int c = 1; c < types; c++) {
            if (random.nextBoolean()) store.add("class " + c, "is classified as", "class " + random.nextInt(c));
        }
        for (int i = 0; i < things; i++) {
            store.add(thing(i), HAS_TYPE, type(random.nextInt(types)));
            if (random.nextInt(3) == 0) store.add(thing(i), HAS_TAG, "label " + random.nextInt(types));
            if (random.nextInt(3) == 0) store.add(thing(i), HAS_PART, thing(random.nextInt(things)));
        }
        for (int l = 1; l < types; l++) {
            if (random.nextBoolean()) store.add("label " + l, "is labelled", "label " + random.nextInt(l));
        }
        return store;
    }

    /**
     * Where join order matters: animals typed under a type tree of three groups of ten, linked at
     * random, a quarter of them linking one hub, and a handful tagged rare. A big type next to a
     * rare tag, a hub next to a link: started from the wrong pattern, a query lists thousands to
     * keep ten.
     */
    static MemoryTripleStore joins(long seed, int animals) {
        Random random = new Random(seed);
        MemoryTripleStore store = new MemoryTripleStore();
        List<String> leaves = new ArrayList<>();
        for (String group : List.of("mammal", "bird", "fish")) {
            store.add("animal", HAS_SUBTYPE, group);
            for (int i = 0; i < 10; i++) {
                store.add(group, HAS_SUBTYPE, group + " " + i);
                leaves.add(group + " " + i);
            }
        }
        for (int i = 0; i < animals; i++) store.add(thing(i), HAS_TYPE, pick(random, leaves));
        for (int i = 0; i < 2 * animals; i++) store.add(thing(random.nextInt(animals)), LINKS_TO, thing(random.nextInt(animals)));
        for (int i = 0; i < animals / 4; i++) store.add(thing(random.nextInt(animals)), LINKS_TO, thing(0));
        for (int i = 0; i < Math.max(3, animals / 2_000); i++) store.add(thing(random.nextInt(animals)), HAS_TAG, "rare");
        return store;
    }

    /** All of the above in one graph, so the rules meet. */
    static MemoryTripleStore mixed(long seed, int scale) {
        MemoryTripleStore store = new MemoryTripleStore();
        for (TripleStore<?> part : List.of(types(seed, 10 * scale, 0.1, 20 * scale), relations(seed + 1, 3 * scale, 10 * scale, 30 * scale),
                parts(seed + 2, 5 * scale), identity(seed + 3, 5 * scale))) {
            part.all().forEach(fact -> store.add(fact.subject(), fact.relation(), fact.object()));
        }
        return store;
    }

    private static int parent(Random random, int i) {
        // Half near the child, for depth; half anywhere, for breadth.
        return random.nextBoolean() ? Math.max(0, i - 1 - random.nextInt(Math.min(i, 3))) : random.nextInt(i);
    }

    private static String type(int i) {
        return "T" + i;
    }

    private static String thing(int i) {
        return "x" + i;
    }

    private static String pick(Random random, List<String> from) {
        return from.get(random.nextInt(from.size()));
    }
}
