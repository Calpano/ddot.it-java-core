/**
 * The ddot.it triple-event stream: the model
 * ({@link com.calpano.ddot.it.event.DdotEvent}, {@link com.calpano.ddot.it.event.DdotTriple}) and its
 * canonical generator ({@link com.calpano.ddot.it.event.DdotEventExporter}).
 *
 * <p>{@link com.calpano.ddot.it.event.DdotEventExporter#parse} is a <strong>fold over
 * {@link com.calpano.ddot.it.token.DdotTokenizer}</strong>, not a parser: it replays the tokenizer's slot
 * phases over each line's tokens and reads the slot values back out. That is deliberate — as an independent
 * line-oriented regex parser it used to silently drop {@code !!block} bodies, free meta text,
 * {@code ;;}-separated inline meta pairs and the untyped {@code ,, .... value} shortcut. Consuming the
 * corpus-pinned token stream makes those divergences impossible rather than merely fixed.
 *
 * <p>{@link com.calpano.ddot.it.event.DdotEventExporter#toJsonl} writes the JSONL spelling asserted
 * byte-for-byte against {@code test-data/cases/&#42;/expected.events.jsonl} in the {@code ddot.it} repo.
 *
 * <p>Everything here is a pure function of a {@code String}: no I/O, no platform, no third-party types —
 * the JSON is hand-written precisely to keep it that way.
 */
@NullMarked
package com.calpano.ddot.it.event;

import org.jspecify.annotations.NullMarked;
