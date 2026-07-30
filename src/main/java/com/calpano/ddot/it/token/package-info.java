/**
 * The spec-conformant positional tokenizer for ddot.it source and the slot
 * vocabulary it classifies text into.
 *
 * <p>{@link com.calpano.ddot.it.token.DdotTokenizer#tokenize(String)} turns any text into the
 * <em>canonical highlighter token stream</em> — a flat list of {@code {line, start, end, token, text}} —
 * defined by the cross-implementation corpus in the {@code ddot.it} repo
 * ({@code test-data/cases/&#42;/expected.tokens.json}) and generated canonically by the TextMate grammar in
 * {@code ddot.it-syntax-tools}. Every other ddot.it consumer of source text (the event exporter in
 * {@link com.calpano.ddot.it.event}, editor highlighting, completion, indexing) is a fold over this stream
 * rather than a parser of its own, so the corpus pins them all at once.
 *
 * <p>{@link com.calpano.ddot.it.token.DdotRole} is the coarser five-slot view of the same vocabulary
 * (s / p / o / mr / mo) used by completion and by structure/PSI layers.
 *
 * <p>Everything here is a pure function of a {@code String}: no I/O, no platform, no third-party types.
 */
@NullMarked
package com.calpano.ddot.it.token;

import org.jspecify.annotations.NullMarked;
