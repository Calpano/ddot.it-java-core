package it.ddot.core.lint;

/**
 * A single linting finding on one source line: the rule that fired ({@code DL0xx}), its {@link Severity}, the
 * 1-based {@code lineNumber}, and a human-readable {@code message}.
 *
 * <p>A {@link Severity#FIX} diagnostic mirrors a {@link LineChange} (the line was, or would be, reformatted). A
 * {@link Severity#WARN}/{@link Severity#ERROR} diagnostic is report-only — the line is left untouched.
 */
public record Diagnostic(String ruleId, Severity severity, int lineNumber, String message) {

    public static Diagnostic fix(String ruleId, int lineNumber, String message) {
        return new Diagnostic(ruleId, Severity.FIX, lineNumber, message);
    }

    public static Diagnostic warn(String ruleId, int lineNumber, String message) {
        return new Diagnostic(ruleId, Severity.WARN, lineNumber, message);
    }

    /** {@code "DL010 (WARN) L12: dead dot-run …"} — a compact one-line rendering for CLI output. */
    public String describe() {
        return ruleId + " (" + severity + ") L" + lineNumber + ": " + message;
    }
}
