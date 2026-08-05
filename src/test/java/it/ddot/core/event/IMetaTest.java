package it.ddot.core.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class IMetaTest {

    @Test
    void of_get_returnsValue() {
        IMeta meta = IMeta.of(Map.of("key", "value"));
        assertThat(meta.get("key")).isEqualTo("value");
    }

    @Test
    void of_get_missingKey_returnsNull() {
        IMeta meta = IMeta.of(Map.of("key", "value"));
        assertThat(meta.get("other")).isNull();
    }

    @Test
    void keys_returnsAllKeys() {
        IMeta meta = IMeta.of(Map.of("a", "1", "b", "2"));
        assertThat(meta.keys()).containsExactly("a", "b");
    }

    @Test
    void asMap_roundTrips() {
        Map<String, String> original = Map.of("x", "1", "y", "2");
        IMeta meta = IMeta.of(original);
        assertThat(meta.asMap()).isEqualTo(original);
    }

    @Test
    void forEach_iteratesAllEntries() {
        IMeta meta = IMeta.of(Map.of("p", "predicate", "s", "since"));
        List<String> collected = new ArrayList<>();
        meta.forEach((k, v) -> collected.add(k + "=" + v));
        assertThat(collected).containsExactly("p=predicate", "s=since");
    }

    @Test
    void emptyMeta_hasNoKeys() {
        IMeta meta = IMeta.of(Map.of());
        assertThat(meta.keys()).isEmpty();
        assertThat(meta.asMap()).isEmpty();
    }
}
