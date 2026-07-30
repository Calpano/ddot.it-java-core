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
 *   <li>metadata → a trailing {@code ,, ..key.. value}</li>
 * </ul>
 *
 * <p>Limitation: the parser reads only one {@code ,,} pair per line, so only the first metadata entry is
 * emitted (extra keys are dropped).
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
        List<String> out = new java.util.ArrayList<>(objectLines.size() + 2);
        out.add(appendMeta(command, meta));
        out.addAll(objectLines);
        out.add(marker);
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

    private static String appendMeta(String line, @Nullable IMeta meta) {
        if (meta == null || meta.keys().isEmpty()) return line;
        String k = meta.keys().getFirst();
        return line + " ,, .." + k + ".. " + meta.get(k);
    }

    public static String serialize(String from, @Nullable String type, String to, @Nullable IMeta meta) {
        StringBuilder sb = new StringBuilder();
        if (type == null || DEFAULT_PREDICATE.equals(type)) {
            sb.append(from).append(" .... ").append(to);
        } else {
            sb.append(from).append(" ..").append(type).append(".. ").append(to);
        }
        if (meta != null) {
            List<String> keys = meta.keys();
            if (!keys.isEmpty()) {
                String k = keys.getFirst();
                sb.append(" ,, ..").append(k).append(".. ").append(meta.get(k));
            }
        }
        return sb.toString();
    }

}
