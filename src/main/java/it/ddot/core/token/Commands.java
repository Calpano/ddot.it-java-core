package it.ddot.core.token;

import org.jspecify.annotations.NullUnmarked;

import java.util.regex.Pattern;

public class Commands {

    /**
     * A command needs the slash and a non-empty name; bare `ddot.it` is not one. `?` and `#` do not terminate a
     * command; they introduce its query/fragment. The `!!` spelling is an EM2 token: EXACTLY two `!`, neither preceded
     * nor followed by another `!` — a 3+ run of `!` is plain text (Parse Spec, Token Sequences), so `!!!off` must not
     * read as `!` + `!!off`.
     */
    @SuppressWarnings("RegExpUnnecessaryNonCapturingGroup") public static final String P_BEGIN =
            // http:// OR https://
            "(?:"+
            "(?:https?://)?" +
                    // www.
                    "(?:www?\\.)?" +
                    // ddot.it
                    "ddot\\.it/" +
                    // OR !!
                    "|(?<!!)" +
                    Markers.EM2 +
                    "(?!!)"
            +")";
    public static final String P_PARAM = "(?:\\?[^\\s#]*)?(?:#\\S*)?";
    public static final String CMD_SRC = P_BEGIN + "[^\\s?#]+" + P_PARAM;
    /** In-text commands: command shape is recognised wherever it occurs (Highlight Spec D6). */
    public static final Pattern CMD_ANYWHERE = Pattern.compile(CMD_SRC);
    public static final Pattern CMD_ONLY = Pattern.compile("^" + CMD_SRC + "$");
    public static final String HTTPS = "https://";
    @SuppressWarnings("HttpUrlsUsage") public static final String HTTP = "http://";
    public static final String DDOT_IT = "ddot.it";

    public static boolean isCommand(String s) {return CMD_ONLY.matcher(s).matches();}

    /**
     * Normalises a token to its bare command spelling for command recognition: strips a leading {@code http://} /
     * {@code https://} scheme (and an optional {@code www.}) so the full-URL form of a command — e.g.
     * {@code https://ddot.it/off} — is recognised exactly like the bare {@code ddot.it/off} token.
     */
    @NullUnmarked
    public static String normalizeCommandToken(String token) {
        if (token.contains(DDOT_IT)) {
            String t = token;

            if (t.startsWith(HTTPS)) t = t.substring(HTTPS.length());
            else if (t.startsWith(HTTP)) t = t.substring(HTTP.length());

            if (t.startsWith("www.")) t = t.substring("www.".length());

            return t;
        } else {
            return token;
        }
    }

}
