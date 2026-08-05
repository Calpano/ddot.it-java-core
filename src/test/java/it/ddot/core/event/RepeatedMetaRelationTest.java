package it.ddot.core.event;

import it.ddot.core.DdotSourceFormats;
import it.ddot.core.DdotVocabulary;
import it.ddot.core.io.DdotParser;
import it.ddot.core.io.DdotWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Metadata is a LIST of pairs, so a relation written twice stays two entries.
 *
 * <p>It used to be a {@code Map<String,String>}, which meant the second {@code ..author..} below overwrote the
 * first and a link carrying both a note and a {@code ,,} text block kept only one of the two texts — data lost
 * inside the parser, before any consumer could see it.
 */
class RepeatedMetaRelationTest {

    private static List<DdotEvent> parse(String text) {
        return DdotParser.parse(text, DdotSourceFormats.DDOT.sourceFormatId(), "t.ddot");
    }

    private static List<String> types(IMeta meta) {
        return meta.pairs().stream().map(IMetaPair::type).toList();
    }

    private static List<String> values(IMeta meta) {
        return meta.pairs().stream().map(IMetaPair::to).toList();
    }

    /** The lossy convenience views still behave predictably, and say which entry they picked. */
    @Test
    void convenienceViewsOnARepeatedRelation() {
        IMeta meta = parse("a ..b.. c ,, ..author.. Ann ;; ..author.. Bob\n").getFirst().meta();

        assertThat(meta.get("author")).isEqualTo("Ann");               // first wins
        assertThat(meta.getAll("author")).containsExactly("Ann", "Bob").inOrder();
        assertThat(meta.keys()).containsExactly("author");             // distinct relations
        assertThat(meta.size()).isEqualTo(2);                          // but two pairs
        assertThat(meta.asMap()).containsExactly("author", "Bob");     // lossy: last wins
    }

    /** A note on the link line and a {@code ,,} text block below it are two separate {@code text} entries. */
    @Test
    void inlineNoteAndTextBlockBothSurvive() {
        List<DdotEvent> events = parse("""
                x ..knows.. y ,, first note
                ,,
                second note
                ,,
                """);

        assertThat(events).hasSize(1);
        IMeta meta = events.getFirst().meta();
        assertThat(types(meta)).containsExactly(DdotVocabulary.TEXT, DdotVocabulary.TEXT);
        assertThat(values(meta)).containsExactly("first note", "second note").inOrder();
    }

    @Test
    void repeatedRelationParsesAsTwoPairs() {
        List<DdotEvent> events = parse("a ..b.. c ,, ..author.. Ann ;; ..author.. Bob\n");

        assertThat(events).hasSize(1);
        IMeta meta = events.getFirst().meta();
        assertThat(meta).hasSize(2);
        assertThat(types(meta)).containsExactly("author", "author");
        assertThat(values(meta)).containsExactly("Ann", "Bob").inOrder();
    }

    /** What the writer emits for a repeated relation must parse back to the same two pairs. */
    @Test
    void repeatedRelationRoundTripsThroughTheWriter() {
        IMeta written = IMeta.of(List.of(IMetaPair.of("author", "Ann"), IMetaPair.of("author", "Bob")));

        String line = DdotWriter.serialize("a", "b", "c", written);
        assertThat(line).isEqualTo("a ..b.. c ,, ..author.. Ann ;; ..author.. Bob");

        IMeta readBack = parse(line + "\n").getFirst().meta();
        assertThat(readBack.pairs()).isEqualTo(written.pairs());
    }

}
