package it.ddot.core.hostreader.asciidoc;

import it.ddot.core.hostreader.asciidoc.doc.AdocDocument;
import it.ddot.core.hostreader.asciidoc.doc.Adocs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdocsTest {

    @Test
    void parse_testAdoc() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/text/adoc/test.adoc")) {
            assert is != null : "Resource /text/adoc/test.adoc not found";
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            AdocDocument doc = Adocs.parse(content);
            System.out.println(doc);
        }
    }

    @Test
    void write_roundTrip() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/text/adoc/test.adoc")) {
            assert is != null : "Resource /text/adoc/test.adoc not found";
            String original = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            AdocDocument doc = Adocs.parse(original);

            StringWriter w = new StringWriter();
            Adocs.write(doc, w);
            String written = w.toString();

            assertEquals(original, written);
        }
    }

}
