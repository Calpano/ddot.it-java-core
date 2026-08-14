package it.ddot.core.kbase.inmem;

import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.kbase.IKnowledgeBase;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.slf4j.LoggerFactory.getLogger;

public class Kbase implements IKnowledgeBase {

    private static final Logger log = getLogger(Kbase.class);

    private final List<Kgraph> graphs = new ArrayList<>();
    private @Nullable Map<String, String> context;

    @Override
    public Kgraph createGraph(String id) {
        Kgraph graph = new Kgraph(id);
        graphs.add(graph);
        log.info("createGraph '{}'", id);
        return graph;
    }

    @Override
    public @Nullable Kgraph findGraphById(String id) {
        return graphs.stream().filter(g -> g.id().equals(id)).findFirst().orElse(null);
    }

    @Override
    public Kgraph getOrCreateDefaultGraph() {
        return getOrCreateGraph("main");
    }

    @Override
    public Kgraph getOrCreateGraph(String id) {
        Kgraph graph = findGraphById(id);
        if (graph != null) return graph;
        return createGraph(id);
    }

    @Override
    public Stream<Kgraph> graphs() {
        return graphs.stream();
    }

    @Override
    public void deleteGraph(String id) {
        graphs.removeIf(g -> g.id().equals(id));
        log.info("deleteGraph '{}'", id);
    }

    @Override
    public @Nullable Map<String, String> context() {
        return context;
    }

    @Override
    public void context(@Nullable Map<String, String> context) {
        this.context = context;
    }

}
