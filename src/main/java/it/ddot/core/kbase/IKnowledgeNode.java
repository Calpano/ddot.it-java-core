package it.ddot.core.kbase;


/**
 * A node in an {@link IKnowledgeGraph}: an {@link IKnowledgeItem} identified by a stable, graph-unique {@link #id()}.
 */
public interface IKnowledgeNode extends IKnowledgeItem {

    /** The stable, graph-unique identifier of this node. */
    String id();


}
