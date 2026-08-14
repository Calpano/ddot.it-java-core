package it.ddot.core.kbase;

import it.ddot.core.kbase.IKnowledgeEdge;
import it.ddot.core.kbase.IKnowledgeItemResource;
import it.ddot.core.kbase.IKnowledgeLabel;
import it.ddot.core.kbase.IKnowledgeNode;
import com.graphinout.foundation.pure.json.document.IJsonValue;
import it.ddot.core.event.IMeta;
import it.ddot.core.source.ISourceLocation;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Common supertype of the two graph item kinds, {@link IKnowledgeNode} and {@link IKnowledgeEdge}. An item carries a
 * (multi-lingual) label, optional JSON {@code data}, an optional attached {@link IKnowledgeItemResource}, optional
 * {@link IMeta} and the source locations it was derived from.
 */
public interface IKnowledgeItem {

    /** Adds a label value with no language tag. */
    void addLabelWithoutLanguage(String label);

    /** Returns this item as an {@link IKnowledgeEdge}; behavior is undefined if {@link #isEdge()} is {@code false}. */
    IKnowledgeEdge asEdge();

    /** Returns this item as an {@link IKnowledgeNode}; behavior is undefined if {@link #isNode()} is {@code false}. */
    IKnowledgeNode asNode();

    /** Arbitrary JSON payload attached to this item, or {@code null} if none. */
    @Nullable IJsonValue data();

    /** Sets the JSON payload attached to this item. */
    void data(IJsonValue data);

    /** Returns {@code true} if any of this item's label entries equals {@code label}. */
    boolean hasLabel(String label);

    /** {@code true} if this item is an edge. */
    boolean isEdge();

    /** {@code true} if this item is a node. */
    boolean isNode();

    /** The label of this item, or {@code null} if unlabeled. */
    @Nullable IKnowledgeLabel label();

    /** Sets the label of this item. */
    void label(IKnowledgeLabel label);

    /** Optional key/value metadata attached to this item, or {@code null} if none. */
    @Nullable IMeta meta();

    /** The resource (file / inline / remote content) attached to this item, or {@code null} if none. */
    @Nullable IKnowledgeItemResource resource();

    /** Attaches a resource to this item. */
    void resource(IKnowledgeItemResource resource);

    /** The source locations this item was derived from (may be empty). */
    List<ISourceLocation> sourceLocations();

    /**
     * Records {@code location} as provenance for this item. {@code null} is ignored and entries are
     * deduplicated by (resourceUri, lineNumber), so re-reading a source does not accumulate duplicates.
     */
    void addSourceLocation(@Nullable ISourceLocation location);

    /**
     * Removes every provenance entry pointing at the source {@code resourceUri}.
     *
     * @return {@code true} if at least one entry was removed
     */
    boolean removeSourceLocationsOf(String resourceUri);

}
