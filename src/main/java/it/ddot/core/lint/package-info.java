/**
 * Linting and canonical formatting of ddot.it source — the engine behind {@code ddot lint} / {@code ddot fmt},
 * specified in {@code linting.adoc} at the repository root.
 *
 * <p>Entry point: {@link DdotLinter#lint(java.util.List)} turns any whitespace-legal
 * plain {@code .ddot} line content into the one canonical spelling and reports the
 * pitfall lines that look like triples but are silently dropped. The FIX
 * transform reuses the canonical spelling of
 * {@link com.calpano.ddot.it.refactor.DdotLineSerializer} and the line grammar of
 * {@link com.calpano.ddot.it.core.source.handler.Source2TripleEvents}, guaranteeing that linting is
 * meaning-preserving, idempotent, prose-safe, and never touches an object or a {@code !!block}/{@code !!verbatim}
 * interior.
 */
package com.calpano.ddot.it.core.lint;

