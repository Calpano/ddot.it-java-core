package it.ddot.core.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The mutable {@link IMeta}: an append-only list of pairs, in written order. Adding a relation that is already
 * present appends a second entry rather than replacing the first — see {@link IMeta} for why that matters.
 */
public class Meta implements IMeta {

    private final List<IMetaPair> pairs = new ArrayList<>();

    public void add(String type, String to) {
        pairs.add(IMetaPair.of(type, to));
    }

    /** Appends every pair of {@code meta}, keeping its order. */
    public void addAll(IMeta meta) {
        pairs.addAll(meta.pairs());
    }

    @Override
    public List<IMetaPair> pairs() {
        return Collections.unmodifiableList(pairs);
    }

    public void putAll(Map<String, String> map) {
        map.forEach(this::add);
    }

}
