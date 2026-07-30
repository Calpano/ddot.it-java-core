package com.calpano.ddot.it.refactor;

import com.calpano.ddot.it.event.DdotEvent;
import com.calpano.ddot.it.event.DdotEventExporter;
import com.calpano.ddot.it.kserver.base.IMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

/**
 * What the serializer writes, the parser must read back. Both live in this
 * artifact, so a disagreement between them is a self-inconsistency rather than
 * an integration problem — and it is not hypothetical: the block form used to
 * append metadata to the {@code !!block?end=MARKER} line, which demotes the
 * opener to ordinary command-shaped text (the Parse Spec requires an opener to
 * END its physical line), so the block round-tripped as a literal
 * {@code "!!block?end=END"} object.
 */
class DdotLineSerializerRoundTripTest {

    private static List<DdotEvent> parse(List<String> lines) {
        return DdotEventExporter.parse(String.join("\n", lines) + "\n", "ddot", "t.ddot");
    }

    @Test
    void multiLineObjectRoundTrips() {
        List<String> lines = DdotLineSerializer.serializeLines(
                "john", "address", "Broadway 1\nBerlin\nGermany", null);

        List<DdotEvent> events = parse(lines);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().from).isEqualTo("john");
        assertThat(events.getFirst().type).isEqualTo("address");
        assertThat(events.getFirst().to).isEqualTo("Broadway 1\nBerlin\nGermany");
    }

    @Test
    void multiLineObjectWithMetadataRoundTrips() {
        List<String> lines = DdotLineSerializer.serializeLines(
                "john", "address", "Broadway 1\nBerlin", IMeta.of(Map.of("since", "2016")));

        List<DdotEvent> events = parse(lines);
        assertThat(events).hasSize(1);
        DdotEvent e = events.getFirst();
        assertThat(e.to).isEqualTo("Broadway 1\nBerlin");
        assertThat(e.meta).isNotNull();
        assertThat(e.meta).hasSize(1);
        assertThat(e.meta.getFirst().type()).isEqualTo("since");
        assertThat(e.meta.getFirst().to()).isEqualTo("2016");
    }

    @Test
    void theOpenerEndsItsLine() {
        List<String> lines = DdotLineSerializer.serializeLines(
                "john", "address", "Broadway 1\nBerlin", IMeta.of(Map.of("since", "2016")));
        assertThat(lines.getFirst()).endsWith("!!block?end=END");
    }

    @Test
    void singleLineObjectWithMetadataRoundTrips() {
        List<String> lines = DdotLineSerializer.serializeLines(
                "a", "note", "b", IMeta.of(Map.of("since", "2016")));

        List<DdotEvent> events = parse(lines);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().to).isEqualTo("b");
        assertThat(events.getFirst().meta.getFirst().to()).isEqualTo("2016");
    }
}
