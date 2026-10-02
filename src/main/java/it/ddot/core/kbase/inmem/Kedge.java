package it.ddot.core.kbase.inmem;


import com.graphinout.foundation.pure.json.document.IJsonFactory;
import com.graphinout.foundation.pure.json.document.IJsonObject;
import com.graphinout.foundation.pure.json.document.IJsonObjectMutable;
import com.graphinout.foundation.pure.json.document.IJsonValue;
import it.ddot.core.event.IMeta;
import it.ddot.core.event.IMetaPair;
import it.ddot.core.kbase.IKnowledgeEdge;
import it.ddot.core.kbase.Kendpoint;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class Kedge extends Kitem implements IKnowledgeEdge {

    final List<Kendpoint> endpoints = new ArrayList<>();

    /**
     * {@code true} for edges materialized by {@link Kgraph#inference()} rather than ingested from a source.
     * Inferred edges carry no provenance and are discarded wholesale by {@link Kgraph#removeBySource(String)},
     * since their premises may have been removed; callers re-run {@code inference()} afterwards.
     */
    private boolean inferred;

    public void addEndpoint(Kendpoint.Kdirection direction, Knode node, Knode type) {
        endpoints.add(new Kendpoint(direction, node, type));
    }

    public List<Kendpoint> endpoints() {
        return endpoints;
    }

    /**
     * The edge's per-link metadata. It is the edge's {@code data}, one key per relation
     * ({@code ,, ..since.. 2025 ;; a note} is {@code {"since": "2025", "text": "a note"}}) — the same flat shape
     * graphinout's ddot reader gives a CJ edge, so CJ imported via {@code KnowledgeBases} reads back the same way.
     * <p>
     * A relation may repeat on one link ({@code ,, ..author.. Ann ;; ..author.. Bob}), which a JSON object cannot
     * hold twice — {@link #addMeta} therefore stores repeats as an ARRAY value, and this expands such an array back
     * into one pair per element. A single value stays one pair. Nested objects have no pair form and are skipped.
     */
    @Override
    public @Nullable IMeta meta() {
        IJsonValue data = data();
        if (data == null || !data.isObject()) return null;
        List<IMetaPair> pairs = new ArrayList<>();
        data.asObject().forEach((key, value) -> {
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

    /**
     * Appends {@code value} under {@code data.<key>}, preserving every other key. This is the write side of
     * {@link #meta()}.
     * <p>
     * APPENDS, not replaces: a relation may legitimately be written twice on one link, and a key that already has a
     * value therefore becomes an array of both rather than losing the first. That array is what {@link #meta()}
     * reads back as two pairs.
     */
    public void addMeta(String key, String value) {
        IJsonValue data = data();
        IJsonObject existing = (data != null && data.isObject()) ? data.asObject() : null;
        IJsonObjectMutable root = IJsonFactory.INSTANCE.createObjectMutable();
        if (existing != null) existing.forEach(root::addProperty);
        appendMetaValue(root, key, value);
        data(root);
    }

    public boolean isInferred() {
        return inferred;
    }

    public void inferred(boolean inferred) {
        this.inferred = inferred;
    }

}
