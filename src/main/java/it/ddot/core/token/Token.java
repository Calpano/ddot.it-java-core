package com.calpano.ddot.it.core.token;

import it.ddot.core.token.TokenName;

import java.util.List;

/**
 * A single highlighter token. {@code line}/{@code start}/{@code end} are 0-based, end-exclusive. {@code scopes} mirrors
 * the TextMate conformance output: it is empty for every recognized token and carries the residual scope stack (just
 * the root {@code source.ddot}) only for {@code (unclassified)} spans the grammar leaves unscoped.
 */
public record Token(int line, int start, int end, TokenName token,
                    String text, List<String> scopes) {

    static Token of(int line, int start, int end, TokenName tokenName, String text) {
        return new Token(line, start, end, tokenName, text, List.of());
    }

}
