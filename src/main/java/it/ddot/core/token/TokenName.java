package it.ddot.core.token;

// Token names produced by DdotTokenizer (see test-data/tokens.md).
public enum TokenName {

    SUBJECT("subject"), //
    RELATION("relation"), //
    OBJECT("object"), //
    DOUBLEDOT("doubledot"), //
    META_DOUBLEDOT("meta-doubledot"), //
    META_DELIM("meta-delim"), //
    META_SEPARATOR("meta-separator"), //
    META_RELATION("meta-relation"), //
    META_OBJECT("meta-object"), //
    META_TEXT("meta-text"), //
    COMMAND("command"), //
    COMMAND_PARAM("command-param"), //
    VERBATIM("verbatim"), //
    BLOCK_END("block-end"), //
    EXCLUDED("excluded"), //
    ;

    public final String id;

    TokenName(String id) {
        this.id = id;
    }
}
