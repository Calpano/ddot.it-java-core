package it.ddot.core.hostreader.html;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class HtmlParserTest {

    /** Captures custom-element and data-ddot callbacks for assertions. */
    private static final class Capture implements HtmlParser.IHandler {
        record Custom(int startLine, String subject, Map<String, String> attrs, List<String> content) {}

        final List<Custom> customs = new ArrayList<>();
        final List<String> dataAttrs = new ArrayList<>();

        @Override public void onHtmlComment(int startLine, List<String> lines) {}
        @Override public void onTextBlock(int startLine, List<String> lines) {}
        @Override public void onDataAttribute(int lineNumber, String value) {
            dataAttrs.add(value);
        }
        @Override public void onCustomElement(int startLine, String subject, Map<String, String> attributes, List<String> contentLines) {
            customs.add(new Custom(startLine, subject, Map.copyOf(attributes), List.copyOf(contentLines)));
        }
    }

    private static Capture parse(String html) {
        Capture c = new Capture();
        HtmlParser.parse(List.of(html.split("\n", -1)), c);
        return c;
    }

    @Test
    void customElement_multiLineContent_preservesMarkupAndNewlines() {
        Capture c = parse("""
                <ddot-it subject='aaa' key1='value1' key2='value2'>
                Hello Mr. <em>Smith</em>!
                See this <a href='https://example.com'>link</a>.
                </ddot-it>
                """);
        assertThat(c.customs).hasSize(1);
        Capture.Custom e = c.customs.getFirst();
        assertThat(e.subject()).isEqualTo("aaa");
        assertThat(e.attrs()).containsEntry("key1", "value1");
        assertThat(e.attrs()).containsEntry("key2", "value2");
        assertThat(String.join("\n", e.content()))
                .isEqualTo("Hello Mr. <em>Smith</em>!\nSee this <a href='https://example.com'>link</a>.");
    }

    @Test
    void customElement_inline_singleLineContent() {
        Capture c = parse("<ddot-it subject='x' k='v'>Hello</ddot-it>");
        assertThat(c.customs).hasSize(1);
        assertThat(c.customs.getFirst().subject()).isEqualTo("x");
        assertThat(c.customs.getFirst().content()).containsExactly("Hello");
    }

    @Test
    void customElement_subjectFromIdThenName() {
        assertThat(parse("<ddot-it id='byId'>c</ddot-it>").customs.getFirst().subject()).isEqualTo("byId");
        assertThat(parse("<ddot-it name='byName'>c</ddot-it>").customs.getFirst().subject()).isEqualTo("byName");
    }

    @Test
    void customElement_doubleQuotedAttributes() {
        Capture c = parse("<ddot-it subject=\"q\" k=\"v\">c</ddot-it>");
        assertThat(c.customs.getFirst().subject()).isEqualTo("q");
        assertThat(c.customs.getFirst().attrs()).containsEntry("k", "v");
    }

    @Test
    void customElement_withoutSubject_ignored() {
        assertThat(parse("<ddot-it k='v'>content</ddot-it>").customs).isEmpty();
    }

    @Test
    void customElement_withoutSubject_multiLine_ignoredEntirely() {
        Capture c = parse("""
                <ddot-it k='v'>
                line one
                line two
                </ddot-it>
                """);
        assertThat(c.customs).isEmpty();
    }

    @Test
    void customElement_selfClosing_attributesOnly_noContent() {
        Capture c = parse("<ddot-it subject='s' k='v'/>");
        assertThat(c.customs).hasSize(1);
        assertThat(c.customs.getFirst().attrs()).containsEntry("k", "v");
        assertThat(c.customs.getFirst().content()).isEmpty();
    }

    @Test
    void notDdotIt_similarTagName_notTreatedAsCustom() {
        assertThat(parse("<ddot-item subject='s'>x</ddot-item>").customs).isEmpty();
    }

    // ---- data-ddot attribute --------------------------------------------------

    @Test
    void dataDdot_extractedFromAnyElement() {
        Capture c = parse("<section data-ddot=\"ddot.it/this ..has tag.. intro ..has type.. overview\"><h1>Hi</h1></section>");
        assertThat(c.dataAttrs).containsExactly("ddot.it/this ..has tag.. intro ..has type.. overview");
    }

    @Test
    void dataDdot_singleQuoted() {
        Capture c = parse("<div data-ddot='Alice ..knows.. Bob'>text</div>");
        assertThat(c.dataAttrs).containsExactly("Alice ..knows.. Bob");
    }

    @Test
    void dataDdot_absent_noCallback() {
        assertThat(parse("<div class='x'>text</div>").dataAttrs).isEmpty();
    }

    @Test
    void dataDdot_onCustomElement_handledIndependentlyOfContent() {
        Capture c = parse("<ddot-it subject='aaa' data-ddot='X ..p.. Y'>content</ddot-it>");
        assertThat(c.dataAttrs).containsExactly("X ..p.. Y");
        assertThat(c.customs).hasSize(1);
        assertThat(c.customs.getFirst().content()).containsExactly("content");
    }
}
