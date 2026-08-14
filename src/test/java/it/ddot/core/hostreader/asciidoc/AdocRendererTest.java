package it.ddot.core.hostreader.asciidoc;

import it.ddot.core.hostreader.asciidoc.doc.AdocDocument;
import it.ddot.core.hostreader.asciidoc.doc.AdocRenderer;
import it.ddot.core.hostreader.asciidoc.doc.Adocs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class AdocRendererTest {

    @Test
    void parseHeaders_extractsKeyValuePairs() {
        List<String> headers = List.of(":product: Acme Widget", ":version: 2.0", ":toc:");
        Map<String, String> result = AdocRenderer.parseHeaders(headers);

        assertThat(result).containsEntry("product", "Acme Widget");
        assertThat(result).containsEntry("version", "2.0");
        assertThat(result).containsEntry("toc", "");
    }

    @Test
    void toHtml_singleBlock_rendersPlain() {
        AdocDocument doc = new AdocDocument();
        doc.title = "Test";
        AdocDocument.Block block = new AdocDocument.Block(AdocDocument.Kind.Plain);
        block.lines().add("Hello world.");
        doc.contents().add(block);

        String html = AdocRenderer.toHtml(doc, block);

        assertThat(html).contains("<p>Hello world.</p>");
        assertThat(html).contains("class=\"paragraph\"");
    }

    @Test
    void toHtml_singleBlock_substitutesAttributes() {
        AdocDocument doc = new AdocDocument();
        doc.title = "Test";
        doc.headers().add(":product: Acme Widget");
        doc.headers().add(":version: 2.0");
        AdocDocument.Block block = new AdocDocument.Block(AdocDocument.Kind.Plain);
        block.lines().add("Welcome to {product} version {version}.");
        doc.contents().add(block);

        String html = AdocRenderer.toHtml(doc, block);

        assertThat(html).contains("Welcome to Acme Widget version 2.0.");
        assertThat(html).doesNotContain("{product}");
        assertThat(html).doesNotContain("{version}");
    }

    @Test
    void toHtml_fullDoc_substitutesAttributes() {
        String content = """
                = Attribute Test
                :product: Acme Widget
                :version: 2.0

                Welcome to {product} version {version}.
                """;
        AdocDocument doc = Adocs.parse(content);
        String html = AdocRenderer.toHtml(doc);

        assertThat(html).contains("Welcome to Acme Widget version 2.0.");
        assertThat(html).doesNotContain("{product}");
        assertThat(html).doesNotContain("{version}");
    }

    @Test
    void toHtml_singleBlock_sectionHeadline_rendersTitleDiv() {
        AdocDocument doc = new AdocDocument();
        doc.title = "Test";
        AdocDocument.Block block = new AdocDocument.Block(AdocDocument.Kind.SectionHeadline);
        block.lines().add("My Section Title");
        doc.contents().add(block);

        String html = AdocRenderer.toHtml(doc, block);

        assertThat(html).contains("<div class=\"title\">My Section Title</div>");
    }

}
