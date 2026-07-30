/**
 * Pattern-conditioned refactoring of a ddot.it knowledge graph: rename ids/relations, add and remove triples,
 * conditioned on a triple pattern with bound variables and string-matched variables.
 *
 * <p>Entry point: {@link com.calpano.ddot.it.refactor.GraphRewriter#apply} with a
 * {@link com.calpano.ddot.it.refactor.RewriteRule} = a {@link com.calpano.ddot.it.refactor.TriplePattern}
 * condition + effects (remove matched / add {@link com.calpano.ddot.it.refactor.TripleTemplate}s). Match terms
 * ({@link com.calpano.ddot.it.refactor.MatchTerm}) bind variables and optionally constrain values via a
 * {@link com.calpano.ddot.it.refactor.StringMatcher}.
 *
 * <p>The requirements, worked use cases, and open questions (surface syntax, multi-pattern joins, capture
 * groups, write-back) are in {@code use-cases.adoc} in this package.
 */
package com.calpano.ddot.it.refactor;
