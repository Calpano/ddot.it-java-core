/**
 * The {@code !!find} rule surface — Position 15 of {@code refactor/rules-next.adoc}: minimal-diff SPARQL with
 * ddot-delimited terms, a DO-verb last (default {@code SELECT *}), and blank-line-terminated regions. One
 * grammar serves all three verbs — query ({@code SELECT}/{@code ASK}/{@code DESCRIBE}/{@code CONSTRUCT}),
 * check ({@code FILTER NOT EXISTS}), and rewrite ({@code REPLACE}/{@code INSERT}).
 *
 * <p>Entry point: {@link com.calpano.ddot.it.refactor.find.FindParser#parse} → a list of
 * {@link com.calpano.ddot.it.refactor.find.FindQuery} (a {@link com.calpano.ddot.it.refactor.find.MatchElement}
 * where-part + a {@link com.calpano.ddot.it.refactor.find.DoVerb}). The rewrite verbs execute today via
 * {@link com.calpano.ddot.it.refactor.find.FindQuery#toRewriteRule()}, which bridges to the
 * {@link com.calpano.ddot.it.refactor.GraphRewriter} engine; the query and lint verbs are represented for a
 * consumer to interpret (no query evaluator ships in this lib).
 *
 * <p>The term/triple grammar is shared with the legacy {@link com.calpano.ddot.it.refactor.RuleParser} via
 * {@link com.calpano.ddot.it.refactor.TermParser}.
 */
package com.calpano.ddot.it.refactor.find;
