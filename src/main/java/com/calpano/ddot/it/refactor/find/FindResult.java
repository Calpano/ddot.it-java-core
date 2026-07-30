package com.calpano.ddot.it.refactor.find;

import java.util.List;
import java.util.Map;

/**
 * The outcome of executing one {@code !!find} region against a graph. Query/lint verbs produce these directly;
 * rewrite verbs ({@code REPLACE}/{@code INSERT}) yield {@link Rewrite}, signalling the caller to run the
 * region's {@link FindQuery#toRewriteRule()} through the {@code GraphRewriter} write-back path.
 */
public sealed interface FindResult {

    /** {@code SELECT} — an ordered variable list and the projected rows. */
    record Table(List<String> vars, List<Map<String, String>> rows) implements FindResult {}

    /** {@code ASK} — whether any solution exists. */
    record Bool(boolean value) implements FindResult {}

    /** {@code DESCRIBE} / {@code CONSTRUCT} — a list of {@code {subject, predicate, object}} triples. */
    record Triples(List<String[]> triples) implements FindResult {}

    /** {@code WARN} / {@code ERROR} — one interpolated message per match; {@code error} fails CI. */
    record Messages(List<String> lines, boolean error) implements FindResult {}

    /** {@code REPLACE} / {@code INSERT} — a transform; the caller drives {@code GraphRewriter}. */
    record Rewrite(FindQuery query) implements FindResult {}
}
