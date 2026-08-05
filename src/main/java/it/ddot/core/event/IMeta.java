package com.calpano.ddot.it.kserver.base;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Optional key/value metadata attached to a triple or item (e.g. {@code since -> 2025}). Read-only; the
 * abstract surface is just {@link #get(String)} and {@link #keys()}, with map/iteration helpers as defaults.
 */
public interface IMeta {

    /** Returns an {@link IMeta} backed by a copy-free view of the given map. */
    static IMeta of(Map<String, String> map) {
        return new IMeta() {
            @Override
            public String get(String key) {
                return map.get(key);
            }

            @Override
            public List<String> keys() {
                return new ArrayList<>(map.keySet());
            }
        };
    }

    /** Returns the metadata as a fresh, mutable {@link Map}. */
    default Map<String, String> asMap() {
        Map<String, String> map = new java.util.HashMap<>();
        for (String key : keys()) {
            map.put(key, get(key));
        }
        return map;
    }

    /** Invokes {@code keyValueConsumer} once per entry, in {@link #keys()} order. */
    default void forEach(BiConsumer<String, String> keyValueConsumer) {
        keys().forEach(key -> keyValueConsumer.accept(key, get(key)));
    }

    /** Returns the value for {@code key}, or {@code null} if absent. */
    String get(String key);

    /** Returns all metadata keys. */
    List<String> keys();

}
