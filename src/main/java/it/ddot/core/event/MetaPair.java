package com.calpano.ddot.it.core.event;

import org.jspecify.annotations.Nullable;

/**
 * One metadata entry — a (relation, object) pair, which is why it uses the same two key names as the triple itself.
 * Three source forms map onto it:
 *
 * <ul>
 *   <li>{@code ,, ..since.. 2016} — a typed pair: {@code {"type":"since","to":"2016"}}</li>
 *   <li>{@code ,, .... 2025} — the untyped shortcut: {@code {"to":"2025"}}</li>
 *   <li>{@code ,, a random note} — free meta text: {@code {"type":"text","to":"a random note"}}</li>
 * </ul>
 *
 * @param type the meta relation, or {@code null} for the untyped shortcut ({@code ,, .... value}), which is then
 *             omitted from the JSON exactly as an untyped triple omits its own {@code type} — consumers default it to
 *             {@code links to}. Free meta text ({@code ,, a note}) is <em>not</em> untyped: it carries the built-in
 *             relation {@code text}. The lines of one multi-line {@code ,,} text block are a single node and so join
 *             into one {@code text} entry with embedded newlines, not one entry per line.
 * @param to   the meta object, verbatim and never empty
 *             <p>
 *                                                                                                                                     TODO make this a file on its own
 */
public record MetaPair(@Nullable String type, String to) {}
