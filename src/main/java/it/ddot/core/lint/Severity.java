package com.calpano.ddot.it.core.lint;

/**
 * The three layered severities of a {@link Diagnostic}, per {@code linting.adoc}:
 *
 * <ul>
 *   <li>{@link #FIX} — safely auto-formattable (whitespace/canonical-spelling only); applied under {@code --fix}.</li>
 *   <li>{@link #WARN} — reported, never auto-changed because intent is ambiguous (the DL010–DL016 pitfalls).</li>
 *   <li>{@link #ERROR} — almost certainly a mistake.</li>
 * </ul>
 *
 * Only {@link #FIX} diagnostics correspond to a text edit; {@link #WARN}/{@link #ERROR} are report-only.
 */
public enum Severity {
    FIX,
    WARN,
    ERROR
}
