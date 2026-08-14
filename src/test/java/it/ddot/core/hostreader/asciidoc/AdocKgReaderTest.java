package it.ddot.core.hostreader.asciidoc;

import it.ddot.core.kbase.inmem.Kbase;
import it.ddot.core.kbase.inmem.Knode;
import it.ddot.core.kbase.IKnowledgeGraph;
import it.ddot.core.kbase.IKnowledgeItemResource;
import it.ddot.core.kbase.KnowledgeBases;
import com.graphinout.base.cj.document.CjDocuments;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdocKgReaderTest {

    private List<String> loadLines(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            assertNotNull(is, "Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    private Kbase parseToKbase(List<String> lines) {
        Kbase kbase = new Kbase();
        IKnowledgeGraph graph = kbase.getOrCreateDefaultGraph();
        Adoc2Kg.readToKnowledgeDocument(lines, (nodeId, label, htmlContent, nodeType) -> {
            Knode node = graph.getOrCreateNodeWithIdAndLabel(nodeId, label != null ? label : nodeId);
            if (htmlContent != null) node.resource(new IKnowledgeItemResource() {
                public @Nullable String content() { return htmlContent; }
                public @Nullable String location() { return null; }
                public @Nullable String mimeType() { return "text/html"; }
            });
            if (nodeType != null) graph.addTypeId(node, nodeType);
        });
        return kbase;
    }

    @Test
    void readToKnowledgeDocument_docNodeMode_createsOneNode() throws IOException {
        List<String> lines = loadLines("/text/adoc/test-doc-node.adoc");
        Kbase kbase = parseToKbase(lines);

        List<? extends IKnowledgeGraph> graphs = kbase.graphs().toList();
        assertThat(graphs).hasSize(1);

        String json = CjDocuments.toJsonString(KnowledgeBases.toCj(kbase));
        assertThat(json).contains("Concept: Linked Data");
        assertThat(json).contains("concept");
        assertThat(json).contains("linked-data");
    }

    @Test
    void readToKnowledgeDocument_sectionNodeMode_createsNodesPerSection() throws IOException {
        List<String> lines = loadLines("/text/adoc/test-section-nodes.adoc");
        Kbase kbase = parseToKbase(lines);

        List<? extends IKnowledgeGraph> graphs = kbase.graphs().toList();
        assertThat(graphs).hasSize(1);

        IKnowledgeGraph graph = graphs.getFirst();
        // preamble and H3 sub-section are not separate nodes; only [[concept-rdf]] and [[concept-owl]]
        List<Knode> nodes = graph.nodes().toList();
        assertThat(nodes).hasSize(2);

        String json = CjDocuments.toJsonString(KnowledgeBases.toCj(kbase));
        assertThat(json).contains("RDF");
        assertThat(json).contains("OWL");
        assertThat(json).contains("concept-rdf");
        assertThat(json).contains("concept-owl");
    }

}
