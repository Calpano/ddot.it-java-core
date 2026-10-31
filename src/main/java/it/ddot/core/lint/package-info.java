/**
 * The warnings about ddot.it a project gets, wherever they are shown: {@link it.ddot.core.lint.DdotAnalysis}, which
 * the IntelliJ plugin and {@code ddot lint} both report from, and its {@link it.ddot.core.lint.Finding}s.
 *
 * <p>Linting and canonical formatting of ddot.it source — the engine behind {@code ddot lint} / {@code ddot fmt},
 * specified in {@code linting.adoc} at the repository root.
 *
 * <p>Entry point: {@link DdotLinter#lint(java.util.List)} turns any whitespace-legal
 * plain {@code .ddot} line content into the one canonical spelling and reports the
 * pitfall lines that look like triples but are silently dropped. The FIX
 * transform reuses the canonical spelling of
 * {@link DdotWriter} and the line grammar of
 * {@link Source2TripleEvents}, guaranteeing that linting is
 * meaning-preserving, idempotent, prose-safe, and never touches an object or a {@code !!block}/{@code !!verbatim}
 * interior.
 */
package it.ddot.core.lint;

import it.ddot.core.io.DdotWriter;
import it.ddot.core.io.handler.Source2TripleEvents;
