package it.ddot.core.embed;

import it.ddot.core.token.DdotTokenizer;
import it.ddot.core.token.Token;
import it.ddot.core.token.TokenName;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Where entity names occur in a host file. The spec tokenizer decides what an entity is: a subject,
 * relation, object, metadata key or metadata value. Everything else — prose, {@code ddot.it/off} regions,
 * {@code !!block} bodies, commands — never produces such a token. Embedded blocks are tokenized chunk by
 * chunk and mapped back to offsets in the host file.
 */
public final class DdotOccurrences {

    private static final Set<TokenName> ENTITY_TOKENS = Set.of(
            TokenName.SUBJECT, TokenName.RELATION, TokenName.OBJECT,
            TokenName.META_RELATION, TokenName.META_OBJECT);

    private DdotOccurrences() {}

    /**
     * One entity occurrence: its text as written, its range in the host file, its role in the triple, and
     * the 0-based host line it stands on.
     */
    public record Occurrence(String name, int start, int end, TokenName role, int line) {}

    /** Every entity occurrence in a host file's text. */
    public static List<Occurrence> inText(@Nullable String extension, String text) {
        int[] lineStarts = lineStarts(text);
        List<Occurrence> out = new ArrayList<>();
        for (DdotEmbeds.Chunk chunk : DdotEmbeds.extractChunks(extension, text)) {
            int chunkLine0 = chunk.firstLine() - 1;
            for (Token t : DdotTokenizer.tokenize(chunk.text(), chunk.startsOff())) {
                if (!ENTITY_TOKENS.contains(t.tokenName())) continue;
                int hostLine = chunkLine0 + t.line();
                if (hostLine >= lineStarts.length) continue;
                int start = lineStarts[hostLine] + t.start();
                int end = lineStarts[hostLine] + t.end();
                // Trim, so the range covers exactly the name.
                while (start < end && Character.isWhitespace(text.charAt(start))) start++;
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
                if (start < end) out.add(new Occurrence(text.substring(start, end), start, end, t.tokenName(), hostLine));
            }
        }
        return out;
    }

    /** The occurrence covering {@code offset}, or ending right at it (caret after a word). */
    public static @Nullable Occurrence at(@Nullable String extension, String text, int offset) {
        for (Occurrence o : inText(extension, text)) {
            if (o.start() <= offset && offset <= o.end()) return o;
        }
        return null;
    }

    /** Offset of the first character of each {@code \n}-separated line. */
    static int[] lineStarts(CharSequence text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts.add(i + 1);
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) out[i] = starts.get(i);
        return out;
    }
}
