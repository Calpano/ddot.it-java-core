package com.calpano.ddot.it.source;

import java.util.ArrayList;
import java.util.List;

/**
 * The host source region a triple occupies — the "graduation" of {@link LineMapping} from a single line to a
 * span. A snippet knows its source {@link #startLine()}..{@link #endLine()} range, whether it is
 * {@link #writable()} for write-back, and how to {@link #decorate(List)} the serialized ddot line(s) of a
 * (possibly rewritten) triple into the exact host source line(s) that replace that range.
 *
 * <p>Two shapes cover today's needs:
 * <ul>
 *   <li>{@link #line(int, LineMapping)} — a single source line, host-decorated by a {@link LineMapping}
 *       (verbatim / wrapped / synthesized). This is the common case.</li>
 *   <li>{@link #block(int, int)} — a multi-line {@code !!block} object literal spanning
 *       {@code startLine..endLine} (command line through terminator). Its ddot serialization is already
 *       multi-line, so it passes through verbatim.</li>
 * </ul>
 *
 * <p>The outer readers are the "host writers": they construct the snippet that captures how a triple maps back
 * to their syntax (see {@code refactor/TODO-write-back.adoc}).
 */
public interface ISnippet {

    /** First source line (1-based, inclusive). */
    int startLine();

    /** Last source line (1-based, inclusive); equals {@link #startLine()} for a single-line snippet. */
    int endLine();

    /** Whether write-back can produce source line(s) for this snippet. */
    boolean writable();

    /**
     * Turns the serialized ddot line(s) of a triple into the host source line(s) that occupy this snippet's
     * range. For a single line this applies the host wrapper; for a block it passes the multi-line ddot form
     * through.
     */
    List<String> decorate(List<String> ddotLines);

    /** A single-line snippet host-decorated by {@code mapping}. */
    static ISnippet line(int line, LineMapping mapping) {
        return new LineSnippet(line, mapping);
    }

    /** A verbatim single-line snippet (inner line == source line). */
    static ISnippet verbatim(int line) {
        return line(line, LineMapping.verbatim());
    }

    /** A multi-line {@code !!block} object spanning {@code startLine..endLine} (command line through terminator). */
    static ISnippet block(int startLine, int endLine) {
        return new BlockSnippet(startLine, endLine);
    }

    /** One source line, decorated by a {@link LineMapping}. */
    record LineSnippet(int startLine, LineMapping mapping) implements ISnippet {
        @Override public int endLine() {return startLine;}

        @Override public boolean writable() {return mapping.writable();}

        @Override public List<String> decorate(List<String> ddotLines) {
            List<String> out = new ArrayList<>(ddotLines.size());
            for (String l : ddotLines) out.add(mapping.toSourceLine(l));
            return out;
        }
    }

    /** A multi-line block object; its ddot serialization (command + content + marker) is verbatim in the source. */
    record BlockSnippet(int startLine, int endLine) implements ISnippet {
        @Override public boolean writable() {return true;}

        @Override public List<String> decorate(List<String> ddotLines) {return ddotLines;}
    }

}
