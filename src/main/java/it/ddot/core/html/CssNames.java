package it.ddot.core.html;

import it.ddot.core.token.TokenName;

import java.util.Map;

public class CssNames {

    /** Token name → CSS class. The vocabulary is {@code test-data/tokens.md}. */
    public static final Map<TokenName, String> TOKEN_CLASS = Map.ofEntries(
            Map.entry(TokenName.SUBJECT, "ddot-subject"),
            Map.entry(TokenName.RELATION, "ddot-predicate"),
            Map.entry(TokenName.OBJECT, "ddot-object"),
            Map.entry(TokenName.DOUBLEDOT, "ddot-sep"),
            Map.entry(TokenName.META_DOUBLEDOT, "ddot-sep"),
            Map.entry(TokenName.META_DELIM, "ddot-meta-sep"),
            Map.entry(TokenName.META_SEPARATOR, "ddot-meta-sep"),
            Map.entry(TokenName.META_RELATION, "ddot-meta-relation"),
            Map.entry(TokenName.META_OBJECT, "ddot-meta-object"),
            Map.entry(TokenName.META_TEXT, "ddot-meta-text"),
            Map.entry(TokenName.COMMAND, "ddot-command"),
            Map.entry(TokenName.COMMAND_PARAM, "ddot-command-param"),
            Map.entry(TokenName.BLOCK_END, "ddot-block-end"),
            Map.entry(TokenName.EXCLUDED, "ddot-inactive"),
            Map.entry(TokenName.VERBATIM, "ddot-verbatim"));

    public static String cssClassOf(TokenName token) {
        return TOKEN_CLASS.get(token);
    }

}
