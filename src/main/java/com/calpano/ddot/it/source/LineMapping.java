package com.calpano.ddot.it.source;

import org.jspecify.annotations.Nullable;

/**
 * How an inner (ddot) line maps back to its host source line — the piece of provenance write-back needs to edit
 * a source without corrupting the host syntax around it. Supplied by the outer host reader (Markdown, AsciiDoc)
 * and stamped onto each {@link ISourceLocation}. See {@code refactor/TODO-write-back.adoc}.
 */
public sealed interface LineMapping permits LineMapping.Verbatim, LineMapping.Wrapped, LineMapping.Synthesized {

    /** The full source line for {@code innerLine}, or {@code null} if this content has no writable source line. */
    @Nullable String toSourceLine(String innerLine);

    /** Whether write-back can produce a source line for this content. */
    boolean writable();

    static LineMapping verbatim() {return Verbatim.INSTANCE;}

    static LineMapping wrapped(String prefix, String suffix) {return new Wrapped(prefix, suffix);}

    static LineMapping synthesized() {return Synthesized.INSTANCE;}

    /** Inner line == source line (plain ddot lines, `.ddot` files, code-block content). */
    record Verbatim() implements LineMapping {
        static final Verbatim INSTANCE = new Verbatim();

        @Override public String toSourceLine(String innerLine) {return innerLine;}

        @Override public boolean writable() {return true;}
    }

    /** Source line = {@code prefix + inner + suffix} — e.g. a single-line HTML comment {@code <!-- inner -->}. */
    record Wrapped(String prefix, String suffix) implements LineMapping {
        @Override public String toSourceLine(String innerLine) {return prefix + innerLine + suffix;}

        @Override public boolean writable() {return true;}
    }

    /** No writable source line — the triple was synthesized from host structure (e.g. a heading's attributes). */
    record Synthesized() implements LineMapping {
        static final Synthesized INSTANCE = new Synthesized();

        @Override public @Nullable String toSourceLine(String innerLine) {return null;}

        @Override public boolean writable() {return false;}
    }

}
