package it.ddot.core.lint;

import java.util.Comparator;
import java.util.List;

/**
 * One problem {@link DdotAnalysis} found, where to show it, and where the things it is about are stated.
 *
 * <p>Unlike a {@link Diagnostic}, which belongs to one plain {@code .ddot} text, a finding names its file and
 * points into the <em>host</em> file: for ddot.it embedded in Markdown or AsciiDoc, {@code line} and the
 * columns count in the Markdown or AsciiDoc file.
 *
 * @param ruleId      the rule that fired, such as {@code DL020}
 * @param url         the file, as the caller named it to {@link DdotAnalysis}
 * @param line        1-based line in the host file
 * @param startColumn 0-based column the problem starts at, or -1 for the whole line
 * @param endColumn   0-based column it ends before, or -1 for the whole line
 * @param related     where the things it is about are stated: the bound a value breaks, the other spelling
 *                    of a name, the other statement placing a relation; may be empty
 */
public record Finding(String ruleId, Severity severity, String url, int line, int startColumn, int endColumn,
                      String message, List<Location> related) {

    public static final Comparator<Finding> ORDER = Comparator.comparing(Finding::url)
            .thenComparingInt(Finding::line)
            .thenComparingInt(Finding::startColumn)
            .thenComparing(Finding::ruleId)
            .thenComparing(Finding::message);

    public Finding {
        related = List.copyOf(related);
    }

    /** A finding about a whole line. */
    public static Finding onLine(String ruleId, Location at, String message, List<Location> related) {
        return new Finding(ruleId, Severity.WARN, at.url(), at.line(), -1, -1, message, related);
    }

    public boolean isWholeLine() {
        return startColumn < 0;
    }

    public Location location() {
        return new Location(url, line);
    }

    /** {@code "docs/a.md:12:5: DL022 (WARN) 'Foo' differs …"} — the compiler-style line {@code ddot lint} prints. */
    public String describe() {
        return url + ":" + line + (isWholeLine() ? "" : ":" + (startColumn + 1)) + ": " + ruleId + " (" + severity
                + ") " + message;
    }
}
