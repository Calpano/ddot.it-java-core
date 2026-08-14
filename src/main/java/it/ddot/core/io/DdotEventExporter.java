package it.ddot.core.io;

import it.ddot.core.event.DdotEvent;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Emits the ddot.it event stream (see
 * <a href="https://ddot.it/developer-guide.html#events">the events spec</a>) as
 * JSONL.
 * <p>
 * Asserted against {@code expected.events.jsonl} in the shared corpus by {@code CorpusConformanceTest} here and by
 * {@code PreviewHtmlGoldenTest} in the IntelliJ plugin.
 *
 * <p><strong>This is a fold over {@link it.ddot.core.token.DdotTokenizer}, not a parser.</strong>
 * It used to be an independent line-oriented regex parser, and that is precisely why it silently dropped four spec'd
 * constructs: {@code !!block} bodies (the literal {@code "!!block"} was exported as the field value), free meta text,
 * {@code ;;}-separated inline meta pairs, and the untyped {@code ,, .... value} shortcut. The tokenizer already
 * implements all of them and is pinned to the canonical TextMate grammar by the token corpus, so consuming its output
 * makes those divergences impossible rather than merely fixed.
 *
 * <p>Reconstruction is positional: the tokenizer's slot phases are recovered by
 * replaying its separator transitions over each line's tokens. This matters for slots whose entire text is a command
 * ({@code !!this}, {@code !!block}), which the tokenizer reports as {@code command} — the token name alone no longer
 * says which slot it filled, but its position does.
 * <p>
 * The triple's metadata. An empty meta is omitted from the JSON entirely — never emitted as {@code []}.
 */
public final class DdotEventExporter {

    private DdotEventExporter() {}

    private static void appendField(StringBuilder b, @NonNull String key, @NonNull String value, boolean leadingComma) {
        if (leadingComma) b.append(',');
        b.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
    }

    // --- line reconstruction ------------------------------------------------

    private static String escapeJson(@NonNull String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    // --- `!!block` bodies ---------------------------------------------------


    /**
     * Field order matches the spec: from, type, to, meta?, kind, source, location. Hand-written to keep the output
     * deterministic and dependency-free.
     *
     * <p>{@code type} is always written, never omitted: a triple has all three of subject, relation and object, and an
     * untyped {@code ....} link carries {@link it.ddot.core.DdotVocabulary#LINKS_TO} explicitly rather than leaving the consumer to
     * default a missing field (Parse Spec, Triple Events).
     */
    private static String toJson(DdotEvent event) {
        StringBuilder b = new StringBuilder();
        b.append('{');
        appendField(b, "from", event.from, false);
        appendField(b, "type", event.type, true);
        appendField(b, "to", event.to, true);
        if (event.hasMeta()) {
            b.append(',').append('"').append("meta").append("\":[");
            event.meta().forEach((i, type, to) -> {
                if (i > 0) b.append(',');
                b.append('{');
                appendField(b, "type", type, false);
                appendField(b, "to", to, true);
                b.append('}');
            });
            b.append(']');
        }
        appendField(b, "kind", event.sourceKind(), true);
        appendField(b, "source", event.sourceUri(), true);
        // The spec's `location` is a line in the source file, so it is the resource line — for a whole-file snippet
        // that equals startLineInSnippet, but not for an event inside a lifted chunk.
        b.append(',').append('"').append("location").append("\":").append(event.startLineInResource());
        b.append('}');
        return b.toString();
    }

    public static String toJsonl(List<DdotEvent> events) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(toJson(events.get(i)));
        }
        return out.toString();
    }

}
