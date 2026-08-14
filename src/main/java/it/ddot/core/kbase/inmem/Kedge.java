package it.ddot.core.kbase.inmem;


import it.ddot.core.kbase.IKnowledgeEdge;
import it.ddot.core.kbase.Kendpoint;

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

    public boolean isInferred() {
        return inferred;
    }

    public void inferred(boolean inferred) {
        this.inferred = inferred;
    }

}
