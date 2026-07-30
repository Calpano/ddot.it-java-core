package com.calpano.ddot.it.refactor;

/**
 * The condition of a {@link RewriteRule}: a triple of {@link MatchTerm}s that selects matching edges and binds
 * variables. A variable appearing in more than one position must bind consistently (same value).
 */
public record TriplePattern(MatchTerm s, MatchTerm p, MatchTerm o) {}
