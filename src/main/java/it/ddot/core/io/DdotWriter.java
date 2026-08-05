package it.ddot.core.io;

import it.ddot.core.DdotVocabulary;
import it.ddot.core.event.IMeta;
import it.ddot.core.event.IMetaPair;
import it.ddot.core.token.Markers;
import it.ddot.core.token.PreCommand;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static it.ddot.core.token.Markers.DT2_SPACE;
import static it.ddot.core.token.Markers.SPACE_DT2;
import static it.ddot.core.token.Markers.SPACE_DT4_SPACE;

/**
 * Serializes a single triple back to one ddot.it source line — the inverse of the line grammar in
 * {@code Source2TripleEvents}. Used by write-back to re-emit a rewritten triple onto its source line.
 *
 * <p>Emitted in the canonical ddot form — the {@code ..} hugs the relation, with a single space separating
 * subject and object from the dot-clusters (as in {@code Project Eagle ..started in.. 2024}):
 * <ul>
 *   <li>predicate {@value DdotVocabulary#LINKS_TO} → untyped {@code from .... to}. That constant is the only way to
 *       ask for the untyped form; a {@code null} predicate is rejected, not silently treated as untyped</li>
 *   <li>otherwise typed {@code from ..predicate.. to}</li>
 *   <li>metadata → a trailing {@code ,, ..key.. value}, further pairs separated by {@code ;;}</li>
 * </ul>
 *
 * <p>Everything this class writes must parse back to what it was given; that is asserted directly, against
 * this artifact's own {@code DdotEventExporter}, by {@code DdotLineSerializerRoundTripTest}.
 */
public final class DdotWriter {

    private DdotWriter() {}

    /**
     * Append every metadata pair, in written order: the first opens the metadata with {@code ,,}, each further pair is
     * introduced by {@code ;;}.
     *
     * <p>The {@code ;;} matters. A second {@code ,,} on the same line is <em>not</em> a delimiter — only the
     * first one is, the rest is ordinary text (Parse Spec: {@code CM2} is consumed only in the Object phase) — which is
     * why this method used to emit just {@code keys().getFirst()} and silently drop the rest. {@code ;;} is the spec'd
     * inline separator for further pairs (Parse Spec {@code MetaNextInline}, corpus case
     * {@code 22-inline-meta-separator}), so all of them now survive the round trip.
     *
     * <p>It walks {@link IMeta#pairs()}, not {@link IMeta#keys()}: keys are distinct, so a relation written twice
     * ({@code ,, ..author.. Ann ;; ..author.. Bob}) would have been written back once.
     */
    private static String appendMeta(String line, @Nullable IMeta meta) {
        if (meta == null || meta.isEmpty()) return line;
        StringBuilder sb = new StringBuilder(line);

        List<IMetaPair> pairs = meta.pairs();
        for (int i = 0; i < pairs.size(); i++) {
            sb.append(i == 0 ? //
                    " " + Markers.CM2 + " " + Markers.DT2 : //
                    " " + Markers.SC2 //
                            + SPACE_DT2).append(pairs.get(i).type()).append(DT2_SPACE).append(pairs.get(i).to());
        }
        return sb.toString();
    }

    /** A marker (e.g. {@code END}, {@code END1}, …) that does not equal any content line. */
    private static String chooseBlockMarker(List<String> objectLines) {
        java.util.Set<String> trimmed = new java.util.HashSet<>();
        for (String l : objectLines) trimmed.add(l.strip());
        String marker = "END";
        for (int i = 1; trimmed.contains(marker); i++) marker = "END" + i;
        return marker;
    }

    public static String serialize(String from, String type, String to) {
        return serialize(from, type, to, null);
    }

    public static String serialize(String from, String type, String to, @Nullable IMeta meta) {
        return appendMeta(from + spaceRelation(type) + to, meta);
    }

    /**
     * Serializes a triple to one or more ddot source lines. A single-line object yields one line; an object containing
     * newlines yields a {@code !!block?end=MARKER} form — the command line, the object's lines, and a generated end
     * marker guaranteed not to collide with the content.
     */
    public static List<String> serializeLines(String from, String type, String to, @Nullable IMeta meta) {
        if (!to.contains("\n")) {
            return List.of(serialize(from, type, to, meta));
        }
        List<String> objectLines = List.of(to.split("\n", -1));
        String marker = chooseBlockMarker(objectLines);
        String command = from + spaceRelation(type) + PreCommand.blockWithEnd(marker);
        List<String> out = new ArrayList<>(objectLines.size() + 3);
        // The opener MUST end its physical line: `!!block` is only an opener when
        // nothing follows it (Parse Spec "block-as-field"). Appending the metadata
        // here would demote it to ordinary command-shaped text, and the block this
        // method just built would not read back. Metadata therefore goes on a `,,`
        // continuation line after the terminator, which is what continues a block
        // object (Parse Spec "block-continuation").
        out.add(command);
        out.addAll(objectLines);
        out.add(marker);
        String metaLine = appendMeta("", meta);
        if (!metaLine.isEmpty()) out.add(metaLine.stripLeading());
        return out;
    }

    private static String spaceRelation(String relationType) {
        Objects.requireNonNull(relationType, //
                "relation must not be null; write DdotVocabulary.LINKS_TO for an untyped link");
        return DdotVocabulary.LINKS_TO.equals(relationType) ? //
                SPACE_DT4_SPACE : SPACE_DT2 + relationType + DT2_SPACE;
    }

}
