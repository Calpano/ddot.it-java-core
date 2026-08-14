package it.ddot.core.kbase.inmem;

import it.ddot.core.kbase.IDdotItKnowledgeBase;

import it.ddot.core.kbase.Kendpoint;
import it.ddot.core.DdotVocabulary;
import it.ddot.core.event.IMeta;
import it.ddot.core.event.IMetaPair;
import it.ddot.core.io.DdotWriter;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class DdotItBaseOnKbase implements IDdotItKnowledgeBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Kgraph.Variable ANY = new Kgraph.Variable("_");

    private final Kgraph kgraph;

    public DdotItBaseOnKbase(Kgraph kgraph) {this.kgraph = kgraph;}

    // ---- SPO extraction from Kedge (uses public Kendpoint fields) ---------------

    private static IDdotItKnowledgeBase.@Nullable KTriple edgeToTriple(Kedge e) {
        String subject = null, predicate = "links to", object = null;
        for (Kendpoint ep : e.endpoints()) {
            if (ep.kdirection == Kendpoint.Kdirection.in) {
                subject = ep.node.id;
                if (ep.type != null) predicate = ep.type.id;
            } else if (ep.kdirection == Kendpoint.Kdirection.out) {
                object = ep.node.id;
            }
        }
        return (subject != null && object != null) ? new KTriple(subject, predicate, object) : null;
    }

    // ---- Queries ----------------------------------------------------------------

    @Override
    public List<KTriple> query(@Nullable String subject, @Nullable String relation, @Nullable String object) {
        Kgraph.Variable sv = new Kgraph.Variable("s");
        Kgraph.Variable pv = new Kgraph.Variable("p");
        Kgraph.Variable ov = new Kgraph.Variable("o");
        Kgraph.Slot s = subject  != null ? new Kgraph.Constant(subject)  : sv;
        Kgraph.Slot p = relation != null ? new Kgraph.Constant(relation) : pv;
        Kgraph.Slot o = object   != null ? new Kgraph.Constant(object)   : ov;
        return kgraph.query(new Kgraph.Query(List.of(new Kgraph.Pattern(s, p, o)))).matches.stream()
                .map(match -> {
                    String sr = subject  != null ? subject  : match.get(sv);
                    String pr = relation != null ? relation : match.get(pv);
                    String or = object   != null ? object   : match.get(ov);
                    return (sr != null && pr != null && or != null) ? new KTriple(sr, pr, or) : null;
                })
                .filter(t -> t != null)
                .toList();
    }

    /**
     * Each prefix is matched as "starts-with OR word-initials". Because these are
     * disjunctive per dimension and span all three dimensions simultaneously, this
     * is implemented as a full index scan followed by in-memory filtering.
     */
    @Override
    public List<KTriple> queryByPrefix(@Nullable String subjectPrefix,
                                       @Nullable String relationPrefix,
                                       @Nullable String objectPrefix) {
        return patternToTriples(ANY, ANY, ANY)
                .filter(t -> matchesByPrefix(t.subject(),  subjectPrefix))
                .filter(t -> matchesByPrefix(t.relation(), relationPrefix))
                .filter(t -> matchesByPrefix(t.object(),   objectPrefix))
                .sorted(Comparator.comparing(KTriple::subject)
                        .thenComparing(KTriple::relation)
                        .thenComparing(KTriple::object))
                .toList();
    }

    /** Subjects that start with, or have word-initials matching, {@code prefix}. */
    @Override
    public List<String> subjects(@Nullable String prefix) {
        return slotsFor(prefix, ANY, ANY)
                .flatMap(p -> patternToTriples(p[0], p[1], p[2]))
                .map(KTriple::subject)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** Relations that start with, or have word-initials matching, {@code prefix}. */
    @Override
    public List<String> relations(@Nullable String prefix) {
        return slotsFor(ANY, prefix, ANY)
                .flatMap(p -> patternToTriples(p[0], p[1], p[2]))
                .map(KTriple::relation)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** Objects that start with, or have word-initials matching, {@code prefix}. */
    @Override
    public List<String> objects(@Nullable String prefix) {
        return slotsFor(ANY, ANY, prefix)
                .flatMap(p -> patternToTriples(p[0], p[1], p[2]))
                .map(KTriple::object)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    // ---- Mutations --------------------------------------------------------------

    @Override
    public void add(String subject, String relation, String object) {
        if (query(subject, relation, object).isEmpty()) {
            kgraph.addBiEdge(
                    kgraph.getOrCreateNodeWithIdAndLabel(subject),
                    relation,
                    kgraph.getOrCreateNodeWithIdAndLabel(object));
        }
    }

    @Override
    public void remove(KTriple triple) {
        throw new UnsupportedOperationException("Kgraph does not support edge removal");
    }

    @Override
    public int tripleCount() {
        return (int) kgraph.edges().count();
    }

    // ---- Persistence ------------------------------------------------------------

    @Override
    public void loadTripleEvents(Path file) throws IOException {
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    JsonNode s = node.get("s"), r = node.get("r"), o = node.get("o");
                    if (s != null && r != null && o != null) {
                        add(s.asText(), r.asText(), o.asText());
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void saveAsTripleEvents(Path file) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (KTriple t : query(null, null, null)) {
                writer.write(MAPPER.writeValueAsString(
                        Map.of("s", t.subject(), "r", t.relation(), "o", t.object())));
                writer.newLine();
            }
        }
    }

    /**
     * ddot triple-text writer that, unlike the generic default, re-emits each triple's {@code ,,} metadata.
     * It reads directly from the Kgraph edges (where the indexer folds metadata in under the edge's "meta"
     * data key) rather than from the metadata-less {@link KTriple} query result, so notes are not lost.
     */
    @Override
    public void saveAsDdot(Path file) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Kedge edge : (Iterable<Kedge>) kgraph.edges()::iterator) {
                for (String line : edgeToDdotLines(edge)) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
    }

    /**
     * Renders one Kedge as the ddot.it source line(s) {@code s ..p.. o} with any {@code ,,} metadata appended —
     * empty when the edge is malformed.
     *
     * <p>The spelling is {@link DdotWriter}'s, not hand-built, because two things here have to be exactly right and
     * were not: only the FIRST {@code ,,} on a line is a delimiter (further pairs need {@code ;;}, so every pair
     * after the first used to be written as unreadable text), and an object spanning lines needs the
     * {@code !!block} form, which is why this returns lines rather than one line.
     */
    private static List<String> edgeToDdotLines(Kedge e) {
        String subject = null, predicate = "links to", object = null;
        for (Kendpoint ep : e.endpoints()) {
            if (ep.kdirection == Kendpoint.Kdirection.in) {
                subject = ep.node.id;
                if (ep.type != null) predicate = ep.type.id;
            } else if (ep.kdirection == Kendpoint.Kdirection.out) {
                object = ep.node.id;
            }
        }
        if (subject == null || object == null) return List.of();

        List<IMetaPair> pairs = new ArrayList<>();
        IMeta meta = e.meta();
        if (meta != null) {
            meta.forEach((key, value) -> {
                if (value == null || value.isEmpty()) return;
                // A free-text note is the built-in `text` relation; an empty relation is this base's older
                // spelling of the same thing and cannot be written back as one (`,, .... v` is the untyped
                // link shortcut, a different entry entirely).
                pairs.add(IMetaPair.of(key.isEmpty() ? DdotVocabulary.TEXT : key, value));
            });
        }
        return DdotWriter.serializeLines(subject, predicate, object, IMeta.of(pairs));
    }

    // ---- Helpers ----------------------------------------------------------------

    /**
     * Runs {@code kgraph.pattern()} with the given slots and converts results to Triples.
     * Uses the public {@link Kendpoint} fields for SPO extraction, avoiding the query
     * variable-binding machinery when callers already control the slots directly.
     */
    private Stream<KTriple> patternToTriples(Kgraph.Slot s, Kgraph.Slot p, Kgraph.Slot o) {
        return kgraph.pattern(new Kgraph.Pattern(s, p, o)).stream()
                .map(DdotItBaseOnKbase::edgeToTriple)
                .filter(t -> t != null);
    }

    /**
     * For a single dimension with a meaningful prefix, returns a stream of two slot-arrays:
     * one using {@link Kgraph.Prefix} and one using {@link Kgraph.Initials}.
     * When the prefix is null/empty/"*", returns a single wildcard slot-array.
     */
    private static Stream<Kgraph.Slot[]> slotsFor(Kgraph.Slot s, @Nullable String prefix, Kgraph.Slot o) {
        if (prefix == null || prefix.isEmpty() || prefix.equals("*")) {
            return Stream.<Kgraph.Slot[]>of(new Kgraph.Slot[]{s, ANY, o});
        }
        return Stream.<Kgraph.Slot[]>of(
                new Kgraph.Slot[]{s, new Kgraph.Prefix(prefix),   o},
                new Kgraph.Slot[]{s, new Kgraph.Initials(prefix), o}
        );
    }

    // Overloads for each positional dimension
    private static Stream<Kgraph.Slot[]> slotsFor(@Nullable String prefix, Kgraph.Slot p, Kgraph.Slot o) {
        if (prefix == null || prefix.isEmpty() || prefix.equals("*")) {
            return Stream.<Kgraph.Slot[]>of(new Kgraph.Slot[]{ANY, p, o});
        }
        return Stream.<Kgraph.Slot[]>of(
                new Kgraph.Slot[]{new Kgraph.Prefix(prefix),   p, o},
                new Kgraph.Slot[]{new Kgraph.Initials(prefix), p, o}
        );
    }

    private static Stream<Kgraph.Slot[]> slotsFor(Kgraph.Slot s, Kgraph.Slot p, @Nullable String prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.equals("*")) {
            return Stream.<Kgraph.Slot[]>of(new Kgraph.Slot[]{s, p, ANY});
        }
        return Stream.<Kgraph.Slot[]>of(
                new Kgraph.Slot[]{s, p, new Kgraph.Prefix(prefix)},
                new Kgraph.Slot[]{s, p, new Kgraph.Initials(prefix)}
        );
    }

    private static boolean matchesByPrefix(String value, @Nullable String prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.equals("*")) return true;
        return new Kgraph.Prefix(prefix).matches(value) || new Kgraph.Initials(prefix).matches(value);
    }

}
