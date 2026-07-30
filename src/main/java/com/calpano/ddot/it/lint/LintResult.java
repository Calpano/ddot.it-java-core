package com.calpano.ddot.it.lint;

import java.util.List;

/**
 * The outcome of linting one source: the {@code canonicalLines} (input with every recognised triple/command
 * line respelled, prose left byte-for-byte), the {@code changes} that produced it, and all {@code diagnostics}
 * (a {@link Severity#FIX} diagnostic per change, plus every {@link Severity#WARN}/{@link Severity#ERROR}
 * pitfall).
 *
 * @param canonicalLines the fully-linted text, one entry per input line (never {@code null})
 * @param changes        the reformatted lines only (empty when already canonical)
 * @param diagnostics    all findings (FIX + WARN + ERROR), in line order
 */
public record LintResult(List<String> canonicalLines, List<LineChange> changes, List<Diagnostic> diagnostics) {

    /** Joins {@link #canonicalLines} with {@code "\n"}. Does not append a trailing newline. */
    public String canonicalText() {
        return String.join("\n", canonicalLines);
    }

    /** True when nothing was reformatted and no WARN/ERROR fired — the input is fully canonical. */
    public boolean isClean() {
        return changes.isEmpty() && !hasWarningsOrErrors();
    }

    /** True when at least one non-FIX (WARN/ERROR) diagnostic fired. */
    public boolean hasWarningsOrErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() != Severity.FIX);
    }

    @SuppressWarnings("unused")
    public boolean hasChanges() {
        return !changes.isEmpty();
    }
}
