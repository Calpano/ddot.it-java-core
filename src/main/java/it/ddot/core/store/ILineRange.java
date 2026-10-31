package it.ddot.core.store;

/**
 * The lines of a file a fact was read from, 1-based and inclusive.
 *
 * <p>An interface rather than a record so that a store can keep the range type it already has -- adir's
 * {@code Doc.Range} implements it.
 */
public interface ILineRange {

    int startLine();

    int endLine();
}
