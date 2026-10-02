package it.ddot.core.kbase.inmem;

import it.ddot.core.kbase.inmem.Kedge;
import it.ddot.core.kbase.IKnowledgeItem;
import it.ddot.core.kbase.IKnowledgeItemResource;
import it.ddot.core.kbase.IKnowledgeLabel;
import com.graphinout.foundation.pure.json.document.IJsonArrayMutable;
import com.graphinout.foundation.pure.json.document.IJsonFactory;
import com.graphinout.foundation.pure.json.document.IJsonObjectMutable;
import com.graphinout.foundation.pure.json.document.IJsonValue;
import it.ddot.core.event.IMeta;
import it.ddot.core.source.ISourceLocation;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public abstract class Kitem implements IKnowledgeItem {

    final List<ISourceLocation> sourceLocations = new ArrayList<>();
    private IKnowledgeLabel label;
    private @Nullable IJsonValue data;
    private @Nullable IKnowledgeItemResource resource;

    /** Idempotent if label exists */
    public void addLabelWithoutLanguage(String label) {
        if (this.label == null) {
            this.label = new Klabel();
        }
        if (!this.label.hasLabelEntry(label)) {
            this.label.add(label);
        }
    }

    @Override
    public Kedge asEdge() {
        return (Kedge) this;
    }

    @Override
    public Knode asNode() {
        return (Knode) this;
    }

    public @Nullable IJsonValue data() {
        return data;
    }

    public void data(IJsonValue data) {this.data = data;}

    public boolean hasLabel(String label) {
        if (this.label == null) return false;
        return this.label.hasLabelEntry(label);
    }

    public boolean isEdge() {
        return this instanceof Kedge;
    }

    public boolean isNode() {
        return this instanceof Knode;
    }

    @Override
    public IKnowledgeLabel label() {
        return label;
    }

    @Override
    public void label(IKnowledgeLabel label) {this.label = label;}

    /**
     * Per-link metadata ({@code ,, ..since.. 2025}) exists only on edges, see {@link Kedge#meta()}; a node has none.
     */
    @Override
    public @Nullable IMeta meta() {
        return null;
    }

    /**
     * Adds {@code key -> value} to the metadata object {@code metaObj}: absent → a plain string, already present → an
     * array holding the value(s) already there plus this one, in written order. See {@link Kedge#meta()}.
     */
    public static void appendMetaValue(IJsonObjectMutable metaObj, String key, String value) {
        IJsonValue present = metaObj.get(key);
        if (present == null || present.isNull()) {
            metaObj.setString(key, value);
            return;
        }
        IJsonFactory jf = IJsonFactory.INSTANCE;
        IJsonArrayMutable values = jf.createArrayMutable();
        if (present.isArray()) present.asArray().forEach(values::add);
        else values.add(present);
        values.add(jf.createString(value));
        metaObj.setProperty(key, values);
    }

    public @Nullable IKnowledgeItemResource resource() {
        return resource;
    }

    public void resource(IKnowledgeItemResource resource) {this.resource = resource;}

    public List<ISourceLocation> sourceLocations() {
        return sourceLocations;
    }

    /**
     * Records {@code location} as provenance for this item, ignoring {@code null} and deduplicating by
     * (resourceUri, lineNumber) so that re-reading the same source does not pile up duplicate entries.
     */
    @Override
    public synchronized void addSourceLocation(@Nullable ISourceLocation location) {
        if (location == null) return;
        String uri = location.sourceResource().resourceUri();
        int line = location.lineNumber();
        for (ISourceLocation existing : sourceLocations) {
            if (existing.lineNumber() == line && existing.sourceResource().resourceUri().equals(uri)) return;
        }
        sourceLocations.add(location);
    }

    /**
     * Removes every provenance entry pointing at the source {@code resourceUri}.
     *
     * @return {@code true} if at least one entry was removed
     */
    @Override
    public synchronized boolean removeSourceLocationsOf(String resourceUri) {
        return sourceLocations.removeIf(loc -> loc.sourceResource().resourceUri().equals(resourceUri));
    }

}
