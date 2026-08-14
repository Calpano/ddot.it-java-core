package it.ddot.core.hostreader.basic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DdotItBaseReaderTest {

    private List<DdotItBaseReader.ParsedTriple> parse(String input) {
        List<DdotItBaseReader.ParsedTriple> result = new ArrayList<>();
        DdotItBaseReader.parse(input, result::add);
        return result;
    }

    @Test
    void singleLine_parsedCorrectly() {
        var triples = parse("Alice..knows..Bob");
        assertThat(triples).hasSize(1);
        assertThat(triples.getFirst().s()).isEqualTo("Alice");
        assertThat(triples.getFirst().p()).isEqualTo("knows");
        assertThat(triples.getFirst().o()).isEqualTo("Bob");
        assertThat(triples.getFirst().lineNumber()).isEqualTo(1);
    }

    @Test
    void multipleLines_allParsed() {
        var triples = parse("A..p..B\nC..q..D");
        assertThat(triples).hasSize(2);
        assertThat(triples.getFirst().s()).isEqualTo("A");
        assertThat(triples.get(1).s()).isEqualTo("C");
        assertThat(triples.get(1).lineNumber()).isEqualTo(2);
    }

    @Test
    void lineNumbers_countFrom1() {
        var triples = parse("a..b..c\nd..e..f\ng..h..i");
        assertThat(triples.getFirst().lineNumber()).isEqualTo(1);
        assertThat(triples.get(1).lineNumber()).isEqualTo(2);
        assertThat(triples.get(2).lineNumber()).isEqualTo(3);
    }

    @Test
    void nullInput_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () -> DdotItBaseReader.parse(null, t -> {}));
    }

    @Test
    void blankInput_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () -> DdotItBaseReader.parse("   ", t -> {}));
    }

    @Test
    void blankLine_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () -> DdotItBaseReader.parse("a..b..c\n\nd..e..f", t -> {}));
    }

    @Test
    void invalidFormat_twoPartsOnly_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () -> DdotItBaseReader.parse("a..b", t -> {}));
    }

    @Test
    void invalidFormat_fourParts_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () -> DdotItBaseReader.parse("a..b..c..d", t -> {}));
    }

    @Test
    void emptySubject_preserved() {
        // Leading separator: " ..p..B" → s="", p="p", o="B"
        var triples = parse(" ..p..B");
        assertThat(triples).hasSize(1);
        assertThat(triples.getFirst().s()).isEqualTo(" ");
        assertThat(triples.getFirst().p()).isEqualTo("p");
        assertThat(triples.getFirst().o()).isEqualTo("B");
    }
}
