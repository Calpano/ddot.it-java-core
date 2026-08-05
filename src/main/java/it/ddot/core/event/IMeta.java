package it.ddot.core.event;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The metadata attached to one triple — everything written after its {@code ,,} — as an ORDERED LIST of
 * {@link IMetaPair}s.
 *
 * <p><strong>Why a list and not a map.</strong> A link's metadata is a sequence of (relation, object) pairs, and
 * nothing in the grammar makes the relation unique: {@code ,, ..author.. Ann ;; ..author.. Bob} is two pairs, and a
 * link carrying both a note and a {@code ,,} text block has two {@code text} entries. Holding that in a
 * {@code Map<String,String>} silently dropped every entry but the last for a repeated relation — the pair was gone
 * before any consumer could see it. The list keeps each entry, in written order.
 *
 * <p>{@link #get(String)}, {@link #keys()} and {@link #asMap()} remain as convenience views for the common
 * single-valued case; each one is lossy when a relation repeats and says so on its own javadoc. Iterate
 * {@link #pairs()} (or the {@link Iterable} itself) whenever every entry has to survive.
 */
public interface IMeta extends Iterable<IMetaPair> {

    interface IMetaConsumer {

        void onMetaPair(int i, String key, String value);

    }

    /** An empty, immutable metadata list. */
    static IMeta empty() {
        return of(List.of());
    }

    /** Returns an {@link IMeta} over a copy of the given pairs, in the order given. */
    static IMeta of(List<? extends IMetaPair> pairs) {
        List<IMetaPair> copy = List.copyOf(pairs);
        return () -> copy;
    }

    /**
     * Returns an {@link IMeta} over the map's entries, in the map's own iteration order. Convenience for callers that
     * already hold single-valued metadata; a map cannot express a repeated relation, so prefer
     * {@link #of(List)} when it can.
     */
    static IMeta of(Map<String, String> map) {
        List<IMetaPair> pairs = new ArrayList<>(map.size());
        map.forEach((type, to) -> pairs.add(IMetaPair.of(type, to)));
        return of(pairs);
    }

    /**
     * Returns the metadata as a fresh, mutable {@link Map}.
     *
     * <p>LOSSY: a relation written more than once yields one entry, the last one. Use {@link #pairs()} to see
     * every entry.
     */
    default Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (IMetaPair pair : pairs()) {
            map.put(pair.type(), pair.to());
        }
        return map;
    }

    /** Invokes {@code keyValueConsumer} once per pair, in written order — repeated relations included. */
    default void forEach(BiConsumer<String, String> keyValueConsumer) {
        for (IMetaPair pair : pairs()) {
            keyValueConsumer.accept(pair.type(), pair.to());
        }
    }

    /** Invokes {@code metaConsumer} once per pair, in written order, with the pair's index. */
    default void forEach(IMetaConsumer metaConsumer) {
        List<IMetaPair> pairs = pairs();
        for (int i = 0; i < pairs.size(); i++) {
            metaConsumer.onMetaPair(i, pairs.get(i).type(), pairs.get(i).to());
        }
    }

    /** The pair at {@code i}, in written order. */
    default IMetaPair get(int i) {
        return pairs().get(i);
    }

    /**
     * Returns the value of the FIRST pair whose relation is {@code key}, or {@code null} if there is none.
     *
     * <p>LOSSY: a relation written more than once has further values, which only {@link #getAll(String)} and
     * {@link #pairs()} report.
     */
    default @Nullable String get(String key) {
        for (IMetaPair pair : pairs()) {
            if (pair.type().equals(key)) return pair.to();
        }
        return null;
    }

    /** The values of every pair whose relation is {@code key}, in written order; empty when there is none. */
    default List<String> getAll(String key) {
        List<String> values = new ArrayList<>();
        for (IMetaPair pair : pairs()) {
            if (pair.type().equals(key)) values.add(pair.to());
        }
        return values;
    }

    default IMetaPair getFirst() {
        return pairs().getFirst();
    }

    default boolean isEmpty() {
        return pairs().isEmpty();
    }

    default Iterator<IMetaPair> iterator() {
        return pairs().iterator();
    }

    /**
     * The DISTINCT relations, in the order they first occur.
     *
     * <p>LOSSY: this is a set, so it says nothing about how often a relation was written, and
     * {@code keys().size()} is not {@link #size()} once one repeats.
     */
    default Set<String> keys() {
        Set<String> keys = new LinkedHashSet<>();
        for (IMetaPair pair : pairs()) {
            keys.add(pair.type());
        }
        return keys;
    }

    /** Every metadata entry, in written order. Repeated relations are separate entries. */
    List<IMetaPair> pairs();

    /** The number of PAIRS — not of distinct relations (see {@link #keys()}). */
    default int size() {
        return pairs().size();
    }

}
