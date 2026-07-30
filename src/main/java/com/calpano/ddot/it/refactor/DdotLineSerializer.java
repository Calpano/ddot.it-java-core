package com.calpano.ddot.it.refactor;

import com.calpano.ddot.it.kserver.base.IMeta;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Serializes a single triple back to one ddot.it source line — the inverse of the line grammar in
 * {@code Source2TripleEvents}. Used by write-back to re-emit a rewritten triple onto its source line.
 *
 * <p>Emitted in the canonical ddot form — the {@code ..} hugs the relation, with a single space separating
 * subject and object from the dot-clusters (as in {@code Project Eagle ..started in.. 2024}):
 * <ul>
 *   <li>predicate {@code null} or {@value #DEFAULT_PREDICATE} → untyped {@code from .... to}</li>
 *   <li>otherwise typed {@code from ..predicate.. to}</li>
 *   <li>metadata → a trailing {@code ,, ..key.. value}, further pairs separated by {@code ;;}</li>
 * </ul>
 *
 * <p>Everything this class writes must parse back to what it was given; that is asserted directly, against
 * this artifact's own {@code DdotEventExporter}, by {@code DdotLineSerializerRoundTripTest}.
 */
public final class DdotLineSerializer {

    /** Default predicate for an untyped link; matches {@code Kgraph}/{@code TripleEvent2Kgraph}. */
    public static final String DEFAULT_PREDICATE = "links to";

    private DdotLineSerializer() {}

    public static String serialize(String from, @Nullable String type, String to) {
        return serialize(from, type, to, null);
    }

    /**
     * Serializes a triple to one or more ddot source lines. A single-line object yields one line; an object
     * containing newlines yields a {@code !!block?end=MARKER} form — the command line, the object's lines, and a
     * generated end marker guaranteed not to collide with the content.
     */
    public static List<String> serializeLines(String from, @Nullable String type, String to, @Nullable IMeta meta) {
        if (!to.contains("\n")) {
            return List.of(serialize(from, type, to, meta));
        }
        List<String> objectLines = List.of(to.split("\n", -1));
        String marker = chooseBlockMarker(objectLines);
        String command = (type == null || DEFAULT_PREDICATE.equals(type))
                ? from + " .... !!block?end=" + marker
                : from + " .." + type + ".. !!block?end=" + marker;
        List<String> out = new java.util.ArrayList<>(objectLines.size() + 3);
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

    /** A marker (e.g. {@code END}, {@code END1}, …) that does not equal any content line. */
    private static String chooseBlockMarker(List<String> objectLines) {
        java.util.Set<String> trimmed = new java.util.HashSet<>();
        for (String l : objectLines) trimmed.add(l.strip());
        String marker = "END";
        for (int i = 1; trimmed.contains(marker); i++) marker = "END" + i;
        return marker;
    }

    /**
     * Append every metadata pair, in {@link IMeta#keys()} order: the first opens the metadata with
     * {@code ,,}, each further pair is introduced by {@code ;;}.
     *
     * <p>The {@code ;;} matters. A second {@code ,,} on the same line is <em>not</em> a delimiter — only the
     * first one is, the rest is ordinary text (Parse Spec: {@code CM2} is consumed only in the Object phase)
     * — which is why this method used to emit just {@code keys().getFirst()} and silently drop the rest.
     * {@code ;;} is the spec'd inline separator for further pairs (Parse Spec {@code MetaNextInline}, corpus
     * case {@code 22-inline-meta-separator}), so all of them now survive the round trip.
     */
    private static String appendMeta(String line, @Nullable IMeta meta) {
        if (meta == null || meta.keys().isEmpty()) return line;
        StringBuilder sb = new StringBuilder(line);
        List<String> keys = meta.keys();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            sb.append(i == 0 ? " ,, .." : " ;; ..").append(k).append(".. ").append(meta.get(k));
        }
        return sb.toString();
    }

    public static String serialize(String from, @Nullable String type, String to, @Nullable IMeta meta) {
        String line = (type == null || DEFAULT_PREDICATE.equals(type))
                ? from + " .... " + to
                : from + " .." + type + ".. " + to;
        return appendMeta(line, meta);
    }

}
