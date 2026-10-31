package it.ddot.core.cds;

/**
 * The plain store of bare {@link Triple}s: the same statement added twice is one.
 */
public final class MemoryTripleStore extends MemoryFactStore<Triple> {

    public MemoryTripleStore() {
        super(Triple::new);
    }
}
