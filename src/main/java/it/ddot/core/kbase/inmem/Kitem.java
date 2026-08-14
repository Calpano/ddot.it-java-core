package it.ddot.core.kbase.inmem;

import it.ddot.core.kbase.inmem.Kedge;
import it.ddot.core.kbase.IKnowledgeItem;
import it.ddot.core.kbase.IKnowledgeItemResource;
import it.ddot.core.kbase.IKnowledgeLabel;
import com.graphinout.foundation.pure.json.document.IJsonArrayMutable;
import com.graphinout.foundation.pure.json.document.IJsonFactory;
import com.graphinout.foundation.pure.json.document.IJsonObject;
import com.graphinout.foundation.pure.json.document.IJsonObjectMutable;
import com.graphinout.foundation.pure.json.document.IJsonValue;
import it.ddot.core.event.IMeta;
import it.ddot.core.event.IMetaPair;
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
     * The metadata stored under {@code data.meta}, as the ordered pair list {@link IMeta} is.
     * <p>
     * A relation may repeat on one link ({@code ,, ..author.. Ann ;; ..author.. Bob}), which a JSON object cannot
     * hold twice — {@link #addMeta} therefore stores repeats as an ARRAY value, and this expands such an array back
     * into one pair per element. A single value stays one pair.
     */
    public @Nullable IMeta meta() {
        if (data != null && data.isObject()) {
            IJsonValue metaValue = data.asObject().get("meta");
            if (metaValue != null && metaValue.isObject()) {
                List<IMetaPair> pairs = new ArrayList<>();
                metaValue.asObject().forEach((key, value) -> {
                    if (value.isPrimitive()) {
                        pairs.add(IMetaPair.of(key, value.asPrimitive().toJavaString()));
                    } else if (value.isArray()) {
                        value.asArray().forEach(v -> {
                            if (v.isPrimitive()) pairs.add(IMetaPair.of(key, v.asPrimitive().toJavaString()));
                        });
                    }
                });
                return IMeta.of(pairs);
            }
        }
        return null;
    }

    /**
     * Appends {@code value} under {@code data.meta.<key>}, preserving any existing {@code data} and {@code meta}
     * entries. This is the write side of {@link #meta()} (which reads {@code data.meta.*}).
     * <p>
     * APPENDS, not replaces: a relation may legitimately be written twice on one link, and a key that already has a
     * value therefore becomes an array of both rather than losing the first. That array is what {@link #meta()}
     * reads back as two pairs.
     */
    public void addMeta(String key, String value) {
        IJsonFactory jf = IJsonFactory.INSTANCE;
        IJsonObject existing = (data != null && data.isObject()) ? data.asObject() : null;
        IJsonObjectMutable root = jf.createObjectMutable();
        if (existing != null) existing.forEach(root::addProperty);
        IJsonObjectMutable metaObj = jf.createObjectMutable();
        IJsonValue existingMeta = existing != null ? existing.get("meta") : null;
        if (existingMeta != null && existingMeta.isObject()) existingMeta.asObject().forEach(metaObj::addProperty);
        appendMetaValue(metaObj, key, value);
        root.setProperty("meta", metaObj);
        this.data = root;
    }

    /**
     * Adds {@code key -> value} to {@code metaObj}: absent → a plain string, already present → an array holding the
     * value(s) already there plus this one, in written order.
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
