package it.ddot.core.kbase.inmem;


import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.kbase.Kendpoint;
import it.ddot.core.event.IMeta;
import it.ddot.core.event.IMetaPair;
import it.ddot.core.io.DdotWriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class KbaseDiff {

    public static final String META_FREE_TEXT_KEY = "ddot.it/meta";

    /**
     * One triple as compared by the diff — its metadata an ordered LIST of pairs, not a map: a relation may be
     * written twice on one link ({@code ,, ..author.. Ann ;; ..author.. Bob}), and a map kept only the last of
     * them, so the diff could not see the difference it exists to report.
     *
     * <p>The pairs are sorted by relation (repeats staying in written order) so that two graphs that stored the
     * same metadata in a different order still compare equal, as they did when this was a {@link java.util.Map}.
     */
    public record TripleValue(String subject, String predicate, String object, List<IMetaPair> meta) {
        public static TripleValue from(Kedge e) {
            String subject = null, predicate = "links to", object = null;
            for (Kendpoint ep : e.endpoints()) {
                if (ep.kdirection == Kendpoint.Kdirection.in) {
                    subject = ep.node.id;
                    if (ep.type != null) predicate = ep.type.id;
                } else if (ep.kdirection == Kendpoint.Kdirection.out) {
                    object = ep.node.id;
                }
            }
            if (subject == null || object == null) return null;

            List<IMetaPair> pairs = new ArrayList<>();
            IMeta m = e.meta();
            if (m != null) {
                m.forEach((key, value) -> {
                    if (value == null || value.isEmpty()) return;
                    pairs.add(IMetaPair.of(key.isEmpty() ? META_FREE_TEXT_KEY : key, value));
                });
            }
            pairs.sort(Comparator.comparing(IMetaPair::type)); // stable: repeats keep their written order
            return new TripleValue(subject, predicate, object, List.copyOf(pairs));
        }

        /**
         * The triple as one ddot.it source line, via {@link DdotWriter} rather than by hand: only the FIRST
         * {@code ,,} on a line is a delimiter, so the second pair this used to introduce with another {@code ,,}
         * was ordinary text and could not be read back. The writer separates further pairs with {@code ;;}.
         */
        public String toDdotLine() {
            return DdotWriter.serialize(subject, predicate, object, IMeta.of(meta));
        }
    }

    public record DiffResult(List<TripleValue> added, List<TripleValue> removed) {}

    public static DiffResult diff(Kgraph a, Kgraph b) {
        Set<TripleValue> setA = a.edges()
                .map(TripleValue::from)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<TripleValue> setB = b.edges()
                .map(TripleValue::from)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<TripleValue> added = setB.stream()
                .filter(t -> !setA.contains(t))
                .sorted(Comparator.comparing(TripleValue::toDdotLine))
                .toList();

        List<TripleValue> removed = setA.stream()
                .filter(t -> !setB.contains(t))
                .sorted(Comparator.comparing(TripleValue::toDdotLine))
                .toList();

        return new DiffResult(added, removed);
    }
}
