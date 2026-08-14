package it.ddot.core.hostreader.structuredtext;

import org.jspecify.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects a host list-item bullet at the start of a line and splits it from the residual ddot content, so the
 * bullet can be stripped before the triple grammar (else it leaks into the subject) and re-applied on write-back
 * ({@code sourceLine == marker + content}). Shared by the Markdown, HTML (a markdown subset) and AsciiDoc readers.
 */
public final class ListMarker {

    private ListMarker() {}

    /** Markdown/HTML bullets: {@code -}, {@code *}, {@code +} or {@code 1.}, plus optional indentation. */
    private static final Pattern MARKDOWN = Pattern.compile("^(\\s*(?:[-*+]|\\d+\\.)\\s+)(\\S.*)$");

    /**
     * AsciiDoc bullets: a single {@code *}, {@code -} or {@code .} (a single marker char followed by whitespace
     * can never match a ddot {@code ..} continuation, so those stay intact).
     */
    private static final Pattern ASCIIDOC = Pattern.compile("^(\\s*[*.\\-]\\s+)(\\S.*)$");

    /** The list marker (indentation + bullet + spaces) and the residual content. */
    public record Split(String marker, String content) {}

    /** Splits a Markdown/HTML list item, or returns {@code null} if {@code line} is not one. */
    public static @Nullable Split markdown(String line) {
        return split(MARKDOWN, line);
    }

    /** Splits an AsciiDoc list item, or returns {@code null} if {@code line} is not one. */
    public static @Nullable Split asciidoc(String line) {
        return split(ASCIIDOC, line);
    }

    private static @Nullable Split split(Pattern p, String line) {
        Matcher m = p.matcher(line);
        return m.matches() ? new Split(m.group(1), m.group(2)) : null;
    }

}
