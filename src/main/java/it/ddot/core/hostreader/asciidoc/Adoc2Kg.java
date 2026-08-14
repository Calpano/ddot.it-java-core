package it.ddot.core.hostreader.asciidoc;

import it.ddot.core.hostreader.asciidoc.doc.AdocDocument;
import it.ddot.core.hostreader.asciidoc.doc.AdocRenderer;
import it.ddot.core.hostreader.asciidoc.doc.Adocs;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an .adoc document and maps it to 1 KNode or n KNodes.
 */
public class Adoc2Kg {

    public interface IHandler {

        void createNode(String nodeId, @Nullable String label, @Nullable String inlineHtmlContent, @Nullable String nodeType);

    }

    private static final Pattern KG_TYPE_PARAM = Pattern.compile("kg-type=\"([^\"]+)\"");

    /** Extracts kg-type from block parameters like {@code [kg-type="concept"]}. */
    public static String extractKgType(String parameters) {
        if (parameters == null) return null;
        Matcher m = KG_TYPE_PARAM.matcher(parameters);
        if (m.find()) return m.group(1);
        return null;
    }


    public static void readToKnowledgeDocument(List<String> lines, IHandler handler) {
        AdocDocument adoc = Adocs.parse(lines);
        readToKnowledgeDocument(adoc, handler);
    }

    public static void readToKnowledgeDocument(AdocDocument adoc, IHandler handler) {
        Map<String, String> headers = AdocRenderer.parseHeaders(adoc.headers());

        if (headers.containsKey("kg-node")) {
            // Whole-doc mode: the entire document maps to one KG node
            String nodeId = headers.get("kg-node");
            String nodeType = headers.get("kg-type");
            if (nodeType != null && nodeType.isEmpty()) {
                nodeType = null;
            }
            handler.createNode(nodeId, adoc.title(), AdocRenderer.toHtml(adoc), nodeType);
        } else {
            // Section mode: sections with [[id]] anchors map to individual KG nodes
            List<AdocDocument.Section> sections = AdocDocument.groupSections(adoc.contents());
            for (AdocDocument.Section section : sections) {
                if (section.anchorId == null) continue; // skip preamble / unannotated sections
                // Render section blocks to HTML
                StringBuilder html = new StringBuilder();
                for (AdocDocument.Block block : section.blocks()) {
                    html.append(AdocRenderer.toHtml(adoc, block));
                }
                handler.createNode(section.anchorId, section.headlineText, html.toString(), section.nodeType());
            }
        }
    }

}
