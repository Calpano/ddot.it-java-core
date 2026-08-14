package it.ddot.core.hostreader.basic;

import it.ddot.core.io.handler.Source2TripleEvents;

import java.util.function.Consumer;

/**
 * Minimal, <strong>test-only</strong> parser for the bare syntax {@code 'SSS..PPP..OOO\n'} — one triple per
 * line, nothing stripped.
 * <p>
 * <strong>This is NOT the canonical ddot.it parser.</strong> It deliberately rejects anything it does not
 * understand: it throws {@link IllegalStateException} on a null/blank input, on a blank line, and on any line
 * that does not split into exactly three {@code ..}-separated parts. It does <em>not</em> support the real
 * grammar — continuation lines, untyped links ({@code S .... O}), {@code ,, ..key.. value} meta,
 * {@code ddot.it/block} literals, {@code ddot.it/on|off} regions, or whitespace trimming.
 * <p>
 * The canonical grammar lives in {@link Source2TripleEvents}; use that (via
 * a real reader such as Markdown/AsciiDoc/HTML) for anything other than tightly-controlled test fixtures.
 */
public class DdotItBaseReader {

    public record ParsedTriple(String s, String p, String o, int lineNumber) {}

    public static void parse(String s, Consumer<ParsedTriple> consumer) {
        if (s == null) throw new IllegalStateException("String is null");
        if (s.isBlank()) throw new IllegalStateException("String is blank");
        // split into lines. '\R' is platform-dependent line separator
        String[] lines = s.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) throw new IllegalStateException("Line is blank at line " + (i + 1));
            String[] parts = line.split("\\.\\.");
            if (parts.length == 3) {
                consumer.accept(new ParsedTriple(parts[0], parts[1], parts[2], i + 1));
            } else {
                throw new IllegalStateException("Invalid line format at line " + (i + 1) + ": " + line);
            }
        }
    }

}
