package it.ddot.core.token;

import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

public class Whitespace {

    /**
     * WS per the Parse Spec: TAB plus any Unicode <em>space separator</em> (category {@code Zs}), i.e.
     * {@code [\t\p{Zs}]}. NBSP and friends are `Zs` and separate slots exactly like a space; U+200B and U+FEFF are
     * `Cf`, not `Zs`, so they stay inside the name that contains them. Java's own {@code \s}, {@code trim()} and
     * {@code Character.isWhitespace} all get this wrong — the first two are ASCII-only and the third excludes the
     * no-break spaces — so neither is used for slot boundaries.
     */
    public static final String WS = "[\\t\\p{Zs}]";

    /**
     * True for the characters {@link String#trim()} strips (everything at or below U+0020, which covers TAB, CR and LF)
     * plus every Unicode space separator. {@code Character.isWhitespace} is deliberately not used: it rejects NBSP
     * (U+00A0) and NARROW NBSP (U+202F), which the spec's `Zs` class includes, while accepting them here keeps U+200B —
     * category `Cf` — correctly inside its slot.
     */
    @NullUnmarked
    public static boolean isWs(char c) {
        return c <= ' ' || Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    /** Range of the non-whitespace span within [lo, hi), or {@code null} if blank. */
    public static int @Nullable [] trimRange(String line, int lo, int hi) {
        int s = lo, e = hi;
        while (s < e && isWs(line.charAt(s))) s++;
        while (e > s && isWs(line.charAt(e - 1))) e--;
        return s < e ? new int[]{s, e} : null;
    }

    /** {@link String#trim()} over the spec's WS class. */
    public static String trimWs(String s) {
        int[] r = trimRange(s, 0, s.length());
        return r == null ? "" : s.substring(r[0], r[1]);
    }

}
