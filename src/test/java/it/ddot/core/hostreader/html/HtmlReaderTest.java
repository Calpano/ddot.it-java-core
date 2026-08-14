package it.ddot.core.hostreader.html;

import it.ddot.core.event.IDdotEvent;
import it.ddot.core.source.FileResourceAddress;
import it.ddot.core.source.ISnippet;
import it.ddot.core.io.ISourceHandler;
import it.ddot.core.source.ISourceResourceAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;


class HtmlReaderTest {

    // ---- splitDataDdotLines: slicing one data-ddot value into per-triple lines ----

    private static List<IDdotEvent> read(Path dir, String html) throws Exception {
        File file = dir.resolve("t.html").toFile();
        Files.writeString(file.toPath(), html);
        List<IDdotEvent> events = new ArrayList<>();
        ISourceResourceAddress address = new FileResourceAddress(file);
        ISourceHandler handler = new ISourceHandler() {
            @Override
            public void activeSubject(String s) {}

            @Override
            public String activeSubject() {return null;}

            @Override
            public void onLine(String line, int lineNumber) {}

            @Override
            public void onTripleEvent(IDdotEvent e) {events.add(e);}

            @Override
            public void sourceEnd() {}

            @Override
            public void sourceResourceEnd() {}

            @Override
            public void sourceResourceStart(ISnippet a) {}

            @Override
            public void sourceStart() {}
        };
        new HtmlReader().readTo(address, handler);
        return events;
    }

    @Test
    void dataDdot_withMeta_emitsTripleWithMeta(@TempDir Path dir) throws Exception {
        List<IDdotEvent> events = read(dir,
                "<div data-ddot=\"Bob ..works at.. ACME ,, ..since.. 2020 ..knows.. Carol\">x</div>");

        assertThat(events).hasSize(2);
        IDdotEvent worksAt = events.getFirst();
        assertThat(worksAt.from()).isEqualTo("Bob");
        assertThat(worksAt.type()).isEqualTo("works at");
        assertThat(worksAt.to()).isEqualTo("ACME");
        assertThat(worksAt.meta()).isNotNull();
        assertThat(worksAt.meta().get("since")).isEqualTo("2020");

        IDdotEvent knows = events.get(1);
        assertThat(knows.from()).isEqualTo("Bob"); // continuation reuses the subject
        assertThat(knows.type()).isEqualTo("knows");
        assertThat(knows.to()).isEqualTo("Carol");
        assertThat(knows.hasMeta()).isFalse(); // an event without metadata carries an empty IMeta, never null
    }

    @Test
    void split_metaOnlyTrailingTriple() {
        assertThat(HtmlReader.splitDataDdotLines("Bob ..p.. o ,, ..since.. 2020"))
                .containsExactly("Bob ..p.. o ,, ..since.. 2020");
    }

    @Test
    void split_notATriple_unchanged() {
        assertThat(HtmlReader.splitDataDdotLines("just text")).containsExactly("just text");
    }

    @Test
    void split_singleTriple() {
        assertThat(HtmlReader.splitDataDdotLines("Alice ..knows.. Bob"))
                .containsExactly("Alice ..knows.. Bob");
    }

    @Test
    void split_twoChainedTriples() {
        assertThat(HtmlReader.splitDataDdotLines("ddot.it/this ..has tag.. intro ..has type.. overview"))
                .containsExactly("ddot.it/this ..has tag.. intro", "..has type.. overview")
                .inOrder();
    }

    @Test
    void split_typedMeta_staysWithItsTriple() {
        // first triple carries ,, ..since.. 2020 ; second triple is a separate line
        assertThat(HtmlReader.splitDataDdotLines("Bob ..works at.. ACME ,, ..since.. 2020 ..knows.. Carol"))
                .containsExactly("Bob ..works at.. ACME ,, ..since.. 2020", "..knows.. Carol")
                .inOrder();
    }

    // ---- end-to-end through HtmlReader: meta is actually attached ----

    @Test
    void split_untypedLink_keptOnOneLine() {
        assertThat(HtmlReader.splitDataDdotLines("Alice ....Carol"))
                .containsExactly("Alice ....Carol");
    }

    @Test
    void split_untypedMeta_staysWithItsTriple() {
        assertThat(HtmlReader.splitDataDdotLines("Bob ..p.. o ,, ....note ..q.. r"))
                .containsExactly("Bob ..p.. o ,, ....note", "..q.. r")
                .inOrder();
    }

}
