package com.calpano.ddot.it.source;

/**
 * Recognition helpers for {@code ddot.it/…} / {@code !!…} command tokens, shared by every component that has to
 * agree with the reader on what counts as a command.
 *
 * <p>The implementation used to live as a public static on
 * {@code com.calpano.ddot.it.source.handler.Source2TripleEvents}; it moved here (unchanged) when the library was
 * split, so that the linter — which lives in the zero-dependency {@code ddot-core} module — no longer has to
 * reach into the graph-bound reader package for it. {@code Source2TripleEvents.commandToken} still exists and
 * delegates here, so both spellings stay in sync by construction.
 */
public final class DdotCommands {

    private DdotCommands() {}

    /**
     * Normalises a token to its bare command spelling for command recognition: strips a leading
     * {@code http://} / {@code https://} scheme (and an optional {@code www.}) so the full-URL form of a
     * command — e.g. {@code https://ddot.it/off} — is recognised exactly like the bare {@code ddot.it/off}
     * token. A {@code ddot.it/…} command IS a self-documenting URL, so authors naturally paste the full URL;
     * without this it was silently treated as a value. Tokens without a scheme (including every {@code !!}
     * shorthand) are returned unchanged, so this never alters an ordinary object URL that is not a command.
     */
    public static String commandToken(String token) {
        String t;
        if (token.startsWith("https://")) t = token.substring("https://".length());
        else if (token.startsWith("http://")) t = token.substring("http://".length());
        else return token;
        if (t.startsWith("www.")) t = t.substring("www.".length());
        return t;
    }

}
