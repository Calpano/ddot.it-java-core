package it.ddot.core.hostreader.asciidoc;

import it.ddot.core.hostreader.AbstractFileSourceReader;
import it.ddot.core.hostreader.AbstractTextFileSourceReader;
import it.ddot.core.io.handler.StructuredSource2TripleEvents;
import it.ddot.core.DdotSourceFormats;
import it.ddot.core.source.ISnippet;
import it.ddot.core.io.ISourceHandler;
import it.ddot.core.source.ISourceLocation;
import it.ddot.core.source.ISourceResourceAddress;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


/**
 * Reads {@code .adoc} files in two layers:
 * <ol>
 *   <li><b>Primary — AsciiDoc syntax.</b> {@link AsciiDocParser} classifies the source into AsciiDoc structures
 *       (plain lines, line comments, {@code ////} comment blocks, {@code ----} source blocks). {@link StructureHandler}
 *       also resolves inline AsciiDoc constructs that would otherwise confuse the triple grammar — currently
 *       {@code link:TARGET[TEXT]} macros (see {@link #resolveLinkMacros}).</li>
 *   <li><b>Secondary — ddot.it triples.</b> The residual text of each structure is delegated to
 *       {@link StructuredSource2TripleEvents}, which knows nothing about AsciiDoc and applies the ddot grammar
 *       ({@code S .. P .. O}, continuation lines, {@code ,,} metadata, {@code ddot.it/on|off}).</li>
 * </ol>
 * Keeping AsciiDoc-specific handling in the primary layer is essential: e.g. a link target's relative path
 * ({@code ../..}) must be consumed as AsciiDoc before it reaches the secondary parser, where {@code ..} is the
 * triple separator.
 */
public class AsciiDocReader extends AbstractTextFileSourceReader {

    static class StructureHandler implements AsciiDocParser.IHandler {

        private final StructuredSource2TripleEvents handler;
        /**
         * Subject for emitted link triples (the repo-relative document id), or {@code null} to fall back to
         * {@code ddot.it/this}.
         */
        private final @Nullable String docId;

        StructureHandler(StructuredSource2TripleEvents handler, @Nullable String docId) {
            this.handler = handler;
            this.docId = docId;
        }

        @Override
        public void onCommentBlock(int startLine, List<String> lines) {
            handler.blockStart();
            // //// delimiters are on their own lines; content lines are verbatim in the source and correctly
            // numbered (startLine points at the first content line), so they are writable in place.
            for (int i = 0; i < lines.size(); i++) {
                handler.onLine(lines.get(i), startLine + i);
            }
            handler.blockEnd();
        }

        @Override
        public void onLineComment(int lineNumber, String commentText) {
            // Legacy path (no wrapper): treat as non-writable. AsciiDocParser uses the wrapper-aware overload below.
            handler.blockStart();
            handler.onLine(commentText, lineNumber);
            handler.blockEnd();
        }

        @Override
        public void onLineComment(int lineNumber, String inner, String prefix, String suffix) {
            // The wrapper is known → writable: re-serialize inner and re-wrap as prefix+inner+suffix (e.g. "// …").
            handler.blockStart();
            handler.onLine(inner, lineNumber);
            handler.blockEnd();
        }

        @Override
        public void onListItem(int lineNumber, String content, String marker) {
            // Strip the bullet for the triple grammar; record it as a wrapper so write-back re-applies it. No
            // block reset here — that would reset the subject and break continuation across list items.
            handler.onLine(content, lineNumber);
        }

        @Override
        public void onPlainLine(int lineNumber, String line) {
            // Resolve primary AsciiDoc inline syntax (link: macros) before delegating the residual text to the
            // secondary ddot.it parser. This keeps a link target's relative path ("../..") from colliding with
            // the ddot ".." separator inside handler.onLine().
            // If a link macro was rewritten, the residual line no longer matches the source → mark non-writable.
            boolean hasMacro = LINK_MACRO.matcher(line).find();
            handler.onLine(resolveLinkMacros(line, lineNumber, handler, docId), lineNumber);
        }

        @Override
        public void onSourceBlock(int startLine, List<String> lines) {
            handler.blockStart();
            // ---- delimiters are on their own lines; content lines are verbatim and correctly numbered → writable.
            for (int i = 0; i < lines.size(); i++) {
                handler.onLine(lines.get(i), startLine + i);
            }
            handler.blockEnd();
        }

    }

    /**
     * AsciiDoc inline link macro {@code link:TARGET[TEXT]}: TARGET is non-whitespace; TEXT runs up to the first
     * {@code ]}.
     */
    private static final java.util.regex.Pattern LINK_MACRO =
            java.util.regex.Pattern.compile("link:(\\S+?)\\[([^\\]]*)\\]");
    public AsciiDocReader() {
        super(DdotSourceFormats.ASCIIDOC);
    }

    /**
     * Document id used as the subject of link triples: the source file's path relative to the <em>parent</em> of its
     * enclosing git repository, so it begins with the repo directory name — e.g.
     * {@code graph-format-registry/formats/xml/kgml/kgml-0.7.2.adoc}. Path separators are normalised to {@code /}.
     *
     * @return the repo-relative id, or {@code null} when no enclosing {@code .git} directory is found.
     */
    static @Nullable String repoRelativeDocId(File file) {
        Path abs = file.getAbsoluteFile().toPath().normalize();
        Path dir = abs.getParent();
        while (dir != null && !Files.isDirectory(dir.resolve(".git"))) {
            dir = dir.getParent();
        }
        if (dir == null || dir.getParent() == null) return null;
        return dir.getParent().relativize(abs).toString().replace(File.separatorChar, '/');
    }

    /**
     * Resolves AsciiDoc inline {@code link:TARGET[TEXT]} macros within a plain line. Each macro is emitted to the
     * secondary ddot.it parser as {@code <docId> .... TARGET ,, TEXT} — i.e. an untyped "links to" edge from the
     * current document to {@code TARGET}, annotated with the link's display {@code TEXT} as {@code ,,} metadata — and
     * the macro is replaced by its {@code TEXT} in the returned residual line. {@code docId} is the repo-relative
     * document path (see {@link #repoRelativeDocId}); when {@code null} it falls back to {@code ddot.it/this}.
     * Anchoring on a concrete document subject rather than a {@code ....} continuation means the link is captured even
     * in prose where no triple subject is active (e.g. a "See also" paragraph after the source blocks).
     * <p>
     * Running this in the primary (AsciiDoc) layer is what prevents a relative-path target such as
     * {@code link:../../xml/graphml/graphml.adoc[GraphML]} from being mis-read as a {@code .. / ..} triple by the
     * secondary parser.
     *
     * @return the line with each link macro replaced by its display text (unchanged when no macro is present).
     */
    static String resolveLinkMacros(String line, int lineNumber, StructuredSource2TripleEvents handler, @Nullable String docId) {
        java.util.regex.Matcher m = LINK_MACRO.matcher(line);
        if (!m.find()) return line;
        String subject = docId != null ? docId : "ddot.it/this";
        StringBuilder residual = new StringBuilder();
        int last = 0;
        do {
            String target = m.group(1);
            String text = m.group(2);
            handler.onLine(subject + " .... " + target + " ,, " + text, lineNumber);
            residual.append(line, last, m.start()).append(text);
            last = m.end();
        } while (m.find());
        residual.append(line, last, line.length());
        return residual.toString();
    }

    @Override
    public void readTo(List<String> lines, StructuredSource2TripleEvents ss2te) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void readTo(ISourceResourceAddress sourceResourceAddress, ISourceHandler sourceHandler) throws IOException {
        assert sourceResourceAddress.isFile();
        StructuredSource2TripleEvents ss2te = new StructuredSource2TripleEvents(sourceHandler);
        ss2te.sourceStart();
        ISnippet snippet = ISnippet.of(DdotSourceFormats.ASCIIDOC.sourceFormatId(), ISourceLocation.of(sourceResourceAddress, 1));
        ss2te.sourceResourceStart(snippet);
        File file = toFile(snippet.sourceLocation().sourceResource());
        String docId = repoRelativeDocId(file);
        // Node labels/types/inline HTML are graph data, not triple events: only a handler that can hold nodes gets them.
        Adoc2Kg.@Nullable IHandler nodeHandler = sourceHandler instanceof Adoc2Kg.IHandler h ? h : null;
        AbstractFileSourceReader.readLines(file, lines -> {
            AsciiDocParser.parse(lines, new StructureHandler(ss2te, docId));
            if (nodeHandler != null) Adoc2Kg.readToKnowledgeDocument(lines, nodeHandler);
        });
        ss2te.sourceResourceEnd();
        ss2te.sourceEnd();
    }

}
