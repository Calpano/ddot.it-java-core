package it.ddot.core.kbase;

import it.ddot.core.kbase.IKnowledgeGraph;
import it.ddot.core.kbase.IKnowledgeLabel;
import it.ddot.core.kbase.inmem.Kbase;
import it.ddot.core.kbase.inmem.Kedge;
import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.kbase.inmem.Klabel;
import it.ddot.core.kbase.inmem.Knode;
import com.graphinout.base.cj.document.CjDirection;
import com.graphinout.base.cj.document.CjDocuments;
import com.graphinout.base.cj.document.ICjDocument;
import com.graphinout.base.cj.document.ICjDocumentMutable;
import com.graphinout.base.cj.document.ICjEdge;
import com.graphinout.base.cj.document.ICjEdgeMutable;
import com.graphinout.base.cj.document.ICjGraph;
import com.graphinout.base.cj.document.ICjGraphMutable;
import com.graphinout.base.cj.document.ICjHasLabelMutable;
import com.graphinout.base.cj.document.ICjLabel;
import com.graphinout.base.cj.document.ICjNode;
import com.graphinout.base.cj.document.ICjNodeMutable;
import com.graphinout.base.cj.document.impl.CjDocumentElement;
import com.graphinout.foundation.pure.json.document.IJsonArrayMutable;
import com.graphinout.foundation.pure.json.document.IJsonObjectMutable;
import com.graphinout.foundation.pure.json.document.IJsonValue;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.graphinout.foundation.pure.functional.Nullables.ifPresentAccept;
import static com.graphinout.foundation.pure.json.path.IJsonContainerNavigationStep.pathOf;

public class KnowledgeBases {

    public record ImportResult(int graphs, int nodes, int edges) {}

    /**
     * Imports all graphs from {@code cjDoc} into an existing {@code kbase}.
     * Each graph is created if absent; nodes and edges are added directly.
     */
    public static ImportResult importCj(ICjDocument cjDoc, IKnowledgeBase kbase) {
        int[] counts = {0, 0, 0}; // graphs, nodes, edges
        cjDoc.graphs().forEach(cjGraph -> {
            String graphId = cjGraph.id() != null ? cjGraph.id() : ("graph-" + counts[0]);
            IKnowledgeGraph kgraph = kbase.getOrCreateGraph(graphId);
            if (cjGraph.label() != null) kgraph.label(fromCjLabel(cjGraph.label()));
            counts[0]++;

            Map<String, Knode> nodeMap = new LinkedHashMap<>();
            cjGraph.nodes().forEach(cjNode -> {
                Knode existing = kgraph.findNodeById(cjNode.id());
                Knode knode = existing != null ? existing : fromCjNode(cjNode);
                if (existing == null) kgraph.addNode(knode);
                nodeMap.put(cjNode.id(), knode);
                counts[1]++;
            });
            cjGraph.edges().forEach(cjEdge -> {
                Kedge kedge = fromCjEdge(cjEdge, nodeMap);
                kgraph.addEdge(kedge);
                counts[2]++;
            });
        });
        return new ImportResult(counts[0], counts[1], counts[2]);
    }

    public static Kbase fromCj(ICjDocument cjDoc) {
        Kbase kbase = new Kbase();
        cjDoc.graphs().forEach(cjGraph -> {
            String graphId = cjGraph.id() != null ? cjGraph.id() : "";
            Kgraph kgraph = kbase.createGraph(graphId);
            fromCj(cjGraph, kgraph);
        });
        return kbase;
    }

    private static void fromCj(ICjGraph cjGraph, Kgraph kgraph) {
        kgraph.label(fromCjLabel(cjGraph.label()));
        Map<String, Knode> nodeMap = new LinkedHashMap<>();
        cjGraph.nodes().forEach(cjNode -> {
            Knode knode = fromCjNode(cjNode);
            nodeMap.put(cjNode.id(), knode);
            kgraph.addNode(knode);
        });
        cjGraph.edges().forEach(cjEdge -> {
            Kedge kedge = fromCjEdge(cjEdge, nodeMap);
            kgraph.addEdge(kedge);
        });
    }

    private static Kendpoint.Kdirection fromCjDirection(@Nullable CjDirection cjDirection) {
        if (cjDirection == null) return Kendpoint.Kdirection.undir;
        return switch (cjDirection) {
            case IN -> Kendpoint.Kdirection.in;
            case OUT -> Kendpoint.Kdirection.out;
            case UNDIR -> Kendpoint.Kdirection.undir;
        };
    }

    private static Kedge fromCjEdge(ICjEdge cjEdge, Map<String, Knode> nodeMap) {
        Kedge kedge = new Kedge();
        kedge.label(fromCjLabel(cjEdge.label()));
        IJsonValue jsonVal = cjEdge.jsonValue();
        if (jsonVal != null) kedge.data(jsonVal);
        cjEdge.endpoints().forEach(cjEp -> {
            Kendpoint.Kdirection kdir = fromCjDirection(cjEp.direction());
            Knode node = nodeMap.get(cjEp.node());
            String typeId = cjEp.type();
            Knode type = typeId != null ? nodeMap.get(typeId) : null;
            kedge.endpoints().add(new Kendpoint(kdir, node, type));
        });
        return kedge;
    }

    private static @Nullable Klabel fromCjLabel(@Nullable ICjLabel cjLabel) {
        if (cjLabel == null) return null;
        Klabel klabel = new Klabel();
        cjLabel.entries().forEach(entry -> klabel.add(entry.value(), entry.language()));
        return klabel;
    }

    private static Knode fromCjNode(ICjNode cjNode) {
        Knode knode = new Knode(cjNode.id());
        knode.label(fromCjLabel(cjNode.label()));
        IJsonValue jsonVal = cjNode.jsonValue();
        if (jsonVal != null && jsonVal.isObject()) {
            IJsonValue dataVal = jsonVal.asObject().get("data");
            if (dataVal != null) knode.data(dataVal);
            IJsonValue resourceVal = jsonVal.asObject().get("resource");
            if (resourceVal != null && resourceVal.isObject()) knode.resource(fromCjResource(resourceVal));
        }
        return knode;
    }

    // --- fromCj ---

    private static IKnowledgeItemResource fromCjResource(IJsonValue resourceVal) {
        String mimeType = getStringProp(resourceVal, "mimeType");
        String location = getStringProp(resourceVal, "location");
        String content = getStringProp(resourceVal, "content");
        return new IKnowledgeItemResource() {
            @Override
            public @Nullable String content() {return content;}

            @Override
            public @Nullable String location() {return location;}

            @Override
            public @Nullable String mimeType() {return mimeType;}
        };
    }

    private static @Nullable String getStringProp(IJsonValue obj, String key) {
        IJsonValue v = obj.asObject().get(key);
        return v != null ? v.asString() : null;
    }

    public static Kbase loadFromConnectedJson(Path kbFile) throws IOException {
        // load CJ file to ICjDocument
        String json = Files.readString(kbFile, StandardCharsets.UTF_8);
        ICjDocument cjDoc =
        CjDocuments.parseCjJsonString(kbFile.toFile().getAbsolutePath(), json);
        return fromCj(cjDoc);
    }

    public static ICjDocumentMutable toCj(Kbase kbase) {
        ICjDocumentMutable cjDoc = new CjDocumentElement();
        toCj(kbase, cjDoc);
        return cjDoc;
    }

    /** Exports a single graph as a Connected JSON document containing that one graph. */
    public static ICjDocumentMutable toCj(IKnowledgeGraph graph) {
        ICjDocumentMutable cjDoc = new CjDocumentElement();
        cjDoc.addGraph(cjGraph -> toCjGraph(graph, cjGraph));
        return cjDoc;
    }

    private static void toCj(Kbase kbase, ICjDocumentMutable cjDoc) {
        kbase.graphs().forEach(kgraph -> cjDoc.addGraph(cjGraph -> toCjGraph(kgraph, cjGraph)));
    }

    static void toCjGraph(IKnowledgeGraph kgraph, ICjGraphMutable cjGraph) {
        cjGraph.id(kgraph.id());
        toCj(kgraph.label(), cjGraph);
        kgraph.nodes().forEach(knode -> cjGraph.addNode(cjNode -> toCj(knode, cjNode)));
        kgraph.edges().forEach(kedge -> cjGraph.addEdge(cjEdge -> toCj(kedge, cjEdge)));
    }

    private static void toCj(Kedge kedge, ICjEdgeMutable cjEdge) {
        // A relation repeated on one link is several pairs, and `meta` is a JSON OBJECT, which cannot hold the key
        // twice — so repeats go into an array under that key, the same shape Kitem.meta() reads back.
        ifPresentAccept(kedge.meta(), km -> //
                km.keys().forEach(key -> cjEdge.dataMutable(cjData -> {
                    List<String> values = km.getAll(key);
                    if (values.size() == 1) {
                        cjData.add(pathOf("meta", key), values.getFirst());
                    } else {
                        IJsonArrayMutable array = cjData.factory().createArrayMutable();
                        values.forEach(array::add);
                        cjData.add(pathOf("meta", key), array);
                    }
                })));
        kedge.endpoints().forEach(kendpoint -> {
            CjDirection cjDir = kendpoint.kdirection.toCj();
            cjEdge.addEndpoint(cjEp -> {
                cjEp.direction(cjDir);
                cjEp.node(kendpoint.node.id());
                ifPresentAccept(kendpoint.type, t -> cjEp.type(t.id()));
            });
        });
    }

    private static void toCj(Knode knode, ICjNodeMutable cjNode) {
        cjNode.id(knode.id());
        toCj(knode.label(), cjNode);
        cjNode.dataMutable(cjData -> {
            ifPresentAccept(knode.data(), v -> cjData.add("data", v));
            ifPresentAccept(knode.resource(), r -> {
                IJsonObjectMutable o = cjData.factory().createObjectMutable();
                ifPresentAccept(r.mimeType(), m -> o.add("mimeType", m));
                ifPresentAccept(r.location(), l -> o.add("location", l));
                ifPresentAccept(r.content(), c -> o.add("content", c));
                cjData.add("resource", o);
            });
        });
    }

    private static void toCj(@Nullable IKnowledgeLabel klabel, ICjHasLabelMutable cjHasLabelMutable) {
        if (klabel != null) {
            klabel.entries().forEach(entry -> cjHasLabelMutable.addLabel(entry.value, entry.language));
        }
    }

}
