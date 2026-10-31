package it.ddot.core.store;

import java.util.List;

/**
 * Why a store holds a fact nobody wrote: the rule applied, and the facts it was applied to.
 *
 * <p>Those facts have provenance of their own -- a file, or another derivation -- so following
 * the chain always ends at something somebody wrote.
 */
public interface IDerivation {

    /** What was applied, in words a view can show. */
    String rule();

    /** The facts it was applied to, in the order the rule used them. */
    List<? extends IFact> from();
}
