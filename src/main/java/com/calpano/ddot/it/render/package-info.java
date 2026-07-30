/**
 * HTML rendering of ddot.it source, as a fold over the canonical token stream.
 *
 * <p>{@link com.calpano.ddot.it.render.DdotHtmlRenderer#body(String)} turns any text into role-classed
 * {@code <span>} markup by tokenizing once with
 * {@link com.calpano.ddot.it.token.DdotTokenizer#tokenize(String)} and then mapping each token to its CSS
 * class. It owns no lexical rules of its own — every question of "what is this text" is answered by the
 * tokenizer, so the corpus at {@code test-data/cases/&#42;/expected.tokens.json} pins the renderer too.
 *
 * <p>Everything here is a pure function of a {@code String}: no I/O, no platform, no third-party types.
 */
@NullMarked
package com.calpano.ddot.it.render;

import org.jspecify.annotations.NullMarked;
