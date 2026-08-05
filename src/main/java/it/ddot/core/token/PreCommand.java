package it.ddot.core.token;

import java.util.regex.Pattern;

public enum PreCommand {

    ON, OFF, BLOCK;

    /** A block opener must END its physical line (block-as-field). */
    @SuppressWarnings("RegExpUnnecessaryNonCapturingGroup") public static final Pattern BLOCK_OPENER = Pattern.compile("(?:" +

            "(" + Commands.P_BEGIN + "block" + ")(?:(\\?end=)(\\S+))?" + Whitespace.WS + "*$"

            + ")");

    /**
     * `!!off` / `!!on` are recognised ANYWHERE on a line, not only alone on one: the common way to write them is inside
     * a host-language comment (`<!-- ddot.it/off -->`, `# !!off`). The directive line itself yields only the command
     * token; the decoration around it stays unscoped, and the region starts on the NEXT line. `(?![^\s?#])` pins the
     * command NAME, so `!!office` is not `!!off`. Group 2 is the optional `?query#fragment` part, a `command-param`
     * token of its own.
     */
    public static final Pattern OFF_LINE = Pattern.compile("(" + Commands.P_BEGIN + "off)(?![^\\s?#])(" + Commands.P_PARAM + ")");
    public static final Pattern ON_LINE = Pattern.compile("(" + Commands.P_BEGIN + "on)(?![^\\s?#])(" + Commands.P_PARAM + ")");
    /**
     * Whole-string directive tests, shared so callers need not re-derive the four spellings (× optional query/fragment)
     * of each directive themselves.
     */
    public static final Pattern OFF_ONLY = Pattern.compile(Commands.P_BEGIN + "off(?![^\\s?#])" + Commands.P_PARAM);
    public static final Pattern ON_ONLY = Pattern.compile(Commands.P_BEGIN + "on(?![^\\s?#])" + Commands.P_PARAM);
    public static final Pattern OFF_CMD = Pattern.compile("(?:(?:https?://)?ddot\\.it/|!!)off");
    public static final Pattern ON_CMD = Pattern.compile("(?:(?:https?://)?ddot\\.it/|!!)on");

    public static String blockWithEnd(String marker) {
        return "!!block?end=" + marker;
    }

    /**
     * True when {@code text} (surrounding whitespace ignored) is exactly an `off` directive in any of its four command
     * spellings — `!!off`, `ddot.it/off`, `http://ddot.it/off`, `https://ddot.it/off` — with an optional
     * `?query`/`#fragment`. The name is matched exactly (`!!office` is no directive) and the `!!` spelling is a strict
     * EM2, so `!!!off` is not one either.
     */
    public static boolean isOffCommand(String text) {
        return OFF_ONLY.matcher(Whitespace.trimWs(text)).matches();
    }

    /** True when {@code text} is exactly an `on` directive; see {@link #isOffCommand}. */
    public static boolean isOnCommand(String text) {
        return ON_ONLY.matcher(Whitespace.trimWs(text)).matches();
    }
}
