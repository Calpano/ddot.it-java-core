package it.ddot.core.kbase;

import it.ddot.core.kbase.inmem.Knode;

import java.util.List;

/**
 * An edge in an {@link IKnowledgeGraph}. An edge connects nodes through a list of {@link Kendpoint}s rather than a
 * fixed source/target pair, so it is n-ary (a hyperedge) by construction; an ordinary binary edge is simply
 * the two-endpoint case (one {@code in}, one {@code out}).
 */
public interface IKnowledgeEdge extends IKnowledgeItem {

    /**
     * Adds an endpoint connecting this edge to {@code node}.
     *
     * @param direction the role of {@code node} on this edge ({@code in}, {@code out} or {@code undir})
     * @param node      the participating node
     * @param type      the endpoint's type/role node, or {@code null} for an untyped endpoint
     */
    void addEndpoint(Kendpoint.Kdirection direction, Knode node, Knode type);

    /** The endpoints connecting this edge to its nodes. */
    List<Kendpoint> endpoints();

}
