package it.ddot.core.io;

import it.ddot.core.DdotSourceKinds;
import it.ddot.core.event.DdotEvent;
import it.ddot.core.event.IMeta;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
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
class DdotWriterRoundTripTest {

    /** Two pairs in a defined order, so the emitted `;;` separator is assertable. */
    private static IMeta sinceUntil() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("since", "2016");
        m.put("until", "2020");
        return IMeta.of(m);
    }

    private static List<DdotEvent> parse(List<String> lines) {
        return DdotParser.parse(String.join("\n", lines) + "\n", DdotSourceKinds.DDOT, "t.ddot");
    }

    @Test
    void multiLineObjectRoundTrips() {
        List<String> lines = DdotWriter.serializeLines(
                "john", "address", "Broadway 1\nBerlin\nGermany", null);

        List<DdotEvent> events = parse(lines);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().from).isEqualTo("john");
        assertThat(events.getFirst().type).isEqualTo("address");
        assertThat(events.getFirst().to).isEqualTo("Broadway 1\nBerlin\nGermany");
    }

    @Test
    void multiLineObjectWithMetadataRoundTrips() {
        List<String> lines = DdotWriter.serializeLines(
                "john", "address", "Broadway 1\nBerlin", IMeta.of(Map.of("since", "2016")));

        List<DdotEvent> events = parse(lines);
        assertThat(events).hasSize(1);
        DdotEvent e = events.getFirst();
        assertThat(e.to).isEqualTo("Broadway 1\nBerlin");
        assertThat(e.meta()).isNotNull();
        assertThat(e.meta()).hasSize(1);
        assertThat(e.meta().getFirst().type()).isEqualTo("since");
        assertThat(e.meta().getFirst().to()).isEqualTo("2016");
    }

    @Test
    void theOpenerEndsItsLine() {
        List<String> lines = DdotWriter.serializeLines(
                "john", "address", "Broadway 1\nBerlin", IMeta.of(Map.of("since", "2016")));
        assertThat(lines.getFirst()).endsWith("!!block?end=END");
    }

    /**
     * Every metadata pair must survive, not just the first. A second {@code ,,} on a line is ordinary text,
     * so further pairs need {@code ;;} (corpus {@code 22-inline-meta-separator}); emitting a second
     * {@code ,,} — or dropping the pair, as this class used to — loses data.
     */
    @Test
    void severalMetadataPairsRoundTrip() {
        List<String> lines = DdotWriter.serializeLines("a", "note", "b", sinceUntil());

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).isEqualTo("a ..note.. b ,, ..since.. 2016 ;; ..until.. 2020");

        List<DdotEvent> events = parse(lines);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().meta()).hasSize(2);
        assertThat(events.getFirst().meta().get(0).type()).isEqualTo("since");
        assertThat(events.getFirst().meta().get(0).to()).isEqualTo("2016");
        assertThat(events.getFirst().meta().get(1).type()).isEqualTo("until");
        assertThat(events.getFirst().meta().get(1).to()).isEqualTo("2020");
    }

    /** The same, on the {@code ,,} continuation line that carries a block object's metadata. */
    @Test
    void severalMetadataPairsAfterABlockRoundTrip() {
        List<String> lines = DdotWriter.serializeLines("john", "address", "Broadway 1\nBerlin", sinceUntil());

        assertThat(lines.getFirst()).endsWith("!!block?end=END");
        assertThat(lines.getLast()).isEqualTo(",, ..since.. 2016 ;; ..until.. 2020");

        List<DdotEvent> events = parse(lines);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().to).isEqualTo("Broadway 1\nBerlin");
        assertThat(events.getFirst().meta()).hasSize(2);
    }

    @Test
    void singleLineObjectWithMetadataRoundTrips() {
        List<String> lines = DdotWriter.serializeLines(
                "a", "note", "b", IMeta.of(Map.of("since", "2016")));

        List<DdotEvent> events = parse(lines);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().to).isEqualTo("b");
        assertThat(events.getFirst().meta().getFirst().to()).isEqualTo("2016");
    }
}
