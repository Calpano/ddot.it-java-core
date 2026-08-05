/**
 * The spec-conformant positional tokenizer for ddot.it source and the slot
 * vocabulary it classifies text into.
 *
 * <p>{@link DdotTokenizer#tokenize(String)} turns any text into the
 * <em>canonical highlighter token stream</em> — a flat list of {@code {line, start, end, token, text}} —
 * defined by the cross-implementation corpus in the {@code ddot.it} repo
 * ({@code test-data/cases/&#42;/expected.tokens.json}) and generated canonically by the TextMate grammar in
 * {@code ddot.it-syntax-tools}.
 */
@NullMarked
package it.ddot.core.token;

import org.jspecify.annotations.NullMarked;
