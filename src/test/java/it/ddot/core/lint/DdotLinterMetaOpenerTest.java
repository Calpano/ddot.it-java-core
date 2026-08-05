package it.ddot.core.lint;

import org.junit.jupiter.api.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * DL009 — "strip end-of-line whitespace on triple/command lines" — applies to the
 * linter's own output, including the line that OPENS a multi-line metadata block.
 *
 * <p>The canonical form of such an opener is a triple line ending in {@code ␣,,}
 * with nothing after it. Emitting the usual {@code ␣,,␣} separator there leaves a
 * trailing space, so the formatter would introduce exactly the defect DL009 exists
 * to remove — and would do it to 5 of the 35 corpus cases.
 */
class DdotLinterMetaOpenerTest {

    private static String canonical(String line) {
        return String.join("\n", new DdotLinter().lint(line).canonicalLines());
    }

    @Test
    void metaBlockOpenerHasNoTrailingWhitespace() {
        assertThat(canonical("Dirk Hagemann ..works at.. SAP ,,")).isEqualTo("Dirk Hagemann ..works at.. SAP ,,");
    }

    @Test
    void metaBlockOpenerIsNormalisedFromSloppySpacing() {
        assertThat(canonical("Dirk Hagemann  ..works at..  SAP   ,,   ")).isEqualTo("Dirk Hagemann ..works at.. SAP ,,");
    }

    @Test
    void aRealMetaPairKeepsItsSeparatingSpace() {
        assertThat(canonical("a ..note.. b ,, ..since.. 2016")).isEqualTo("a ..note.. b ,, ..since.. 2016");
    }

    @Test
    void aFreeMetaNoteKeepsItsSeparatingSpace() {
        assertThat(canonical("a ..note.. b ,, just a note")).isEqualTo("a ..note.. b ,, just a note");
    }

    @Test
    void formattingIsIdempotentAcrossTheOpener() {
        String once = canonical("Dirk Hagemann ..works at.. SAP ,,");
        assertThat(canonical(once)).isEqualTo(once);
    }
}
