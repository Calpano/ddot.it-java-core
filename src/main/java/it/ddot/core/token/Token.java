package it.ddot.core.token;

import java.util.List;

/**
 * A single highlighter token.
 *
 * @param line   the 0-based, end-exclusive line number
 * @param start  the 0-based, end-exclusive start column
 * @param end    the 0-based, end-exclusive end column
 * @param scopes mirroring the TextMate conformance output: it is empty for every recognized token and carries the
 *               residual scope stack (just the root {@code source.ddot}) only for {@code (unclassified)} spans the
 *               grammar leaves unscoped.
 */
public record Token(int line, int start, int end, TokenName tokenName,
                    String text, List<String> scopes) {

    public static Token of(int line, int start, int end, TokenName tokenName, String text) {
        return new Token(line, start, end, tokenName, text, List.of());
    }

}
