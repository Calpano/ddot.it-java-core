package it.ddot.core.embed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What counts as an entity name, and where: what rename, Find Usages and the letter-case check see. */
class DdotOccurrencesTest {

    private static List<String> names(String ext, String text) {
        return DdotOccurrences.inText(ext, text).stream()
                .map(o -> o.name() + "@" + text.substring(o.start(), o.end()).equals(o.name()))
                .toList();
    }

    private static List<String> plain(String ext, String text) {
        return DdotOccurrences.inText(ext, text).stream().map(DdotOccurrences.Occurrence::name).toList();
    }

    @Test
    void allRolesIncludingMetadata() {
        String text = String.join("\n",
                "John Doe  ..leads..   Project Eagle ,, ..since.. 2025",
                "..doc site.. example.com",
                "Dirk ..works at.. SAP ,,",
                "..year.. 2010",
                ",,");
        assertEquals(List.of("John Doe", "leads", "Project Eagle", "since", "2025",
                "doc site", "example.com", "Dirk", "works at", "SAP", "year", "2010"), plain("ddot", text));
        // Ranges point exactly at the names.
        for (String n : names("ddot", text)) assertTrue(n.endsWith("@true"), n);
    }

    @Test
    void proseOffRegionsBlocksAndCommandsAreNotEntities() {
        String text = String.join("\n",
                "Project Eagle notes",
                "ddot.it/off",
                "Hidden ..is.. off",
                "ddot.it/on",
                "doc ..body.. !!block?end=END",
                "Inside ..is.. verbatim",
                "END",
                "ddot.it/this ..is.. Visible");
        assertEquals(List.of("doc", "body", "is", "Visible"), plain("ddot", text));
    }

    @Test
    void embeddedBlocksMapToHostOffsets() {
        String md = String.join("\n",
                "# Alice in prose",
                "```ddot",
                "Alice ..knows.. Bob",
                "```");
        List<DdotOccurrences.Occurrence> occ = DdotOccurrences.inText("md", md);
        assertEquals(List.of("Alice", "knows", "Bob"), occ.stream().map(DdotOccurrences.Occurrence::name).toList());
        assertEquals("Alice", md.substring(occ.getFirst().start(), occ.getFirst().end()));
        assertEquals(md.indexOf("Alice ..knows"), occ.getFirst().start());
    }

    @Test
    void caretAtOrJustAfterAnEntity() {
        String text = "Alice ..knows.. Bob";
        assertEquals("Alice", DdotOccurrences.at("ddot", text, 5).name());
        assertEquals("knows", DdotOccurrences.at("ddot", text, 9).name());
        assertNull(DdotOccurrences.at("ddot", text, 14));
    }
}
