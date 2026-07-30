package com.calpano.ddot.it.refactor;

/** Thrown by {@link RuleParser} when a rule text cannot be parsed. Carries the offending 1-based line number. */
public class RuleSyntaxException extends RuntimeException {

    private final int line;

    public RuleSyntaxException(int line, String message) {
        super("line " + line + ": " + message);
        this.line = line;
    }

    public int line() {
        return line;
    }

}
