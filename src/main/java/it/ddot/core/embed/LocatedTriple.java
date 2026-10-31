package it.ddot.core.embed;

import it.ddot.core.event.DdotEvent;
import it.ddot.core.event.DdotTriple;
import it.ddot.core.event.IMetaPair;
import it.ddot.core.io.DdotParser;
import it.ddot.core.source.ISnippet;
import it.ddot.core.source.ISourceLocation;
import it.ddot.core.source.ISourceResourceAddress;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A triple, the 1-based line of the host file it is stated on, and its metadata pairs: what every
 * project-wide check reads. The IntelliJ plugin keeps one list per file in its index; {@code ddot lint}
 * extracts them from the files it crawls.
 */
public record LocatedTriple(DdotTriple triple, int line, List<Meta> meta) {

    /** A metadata pair of a triple: {@code ,, ..relation.. value}. */
    public record Meta(String relation, String value) {}

    public LocatedTriple(DdotTriple triple, int line) {
        this(triple, line, List.of());
    }

    /**
     * All triples in a host file's text, across every embedded ddot chunk ({@link DdotEmbeds}), with host
     * lines. A triple whose subject or object is a namespaced name ({@code ::}) is left out.
     *
     * @param extension the host file's extension, which says where the ddot.it is
     * @param source    the name the parser records as the source
     */
    public static List<LocatedTriple> extract(@Nullable String extension, CharSequence text, String source) {
        List<LocatedTriple> out = new ArrayList<>();
        ISnippet snippet = ISnippet.of("ddot", ISourceLocation.of(ISourceResourceAddress.of(source), 1));
        for (DdotEmbeds.Chunk chunk : DdotEmbeds.extractChunks(extension, text)) {
            for (DdotEvent e : DdotParser.parse(chunk.text(), snippet, chunk.startsOff(), chunk.firstLine())) {
                if (e.from.contains("::") || e.to.contains("::")) continue;
                List<Meta> meta = new ArrayList<>();
                for (IMetaPair pair : e.pairs()) meta.add(new Meta(pair.type(), pair.to()));
                out.add(new LocatedTriple(new DdotTriple(e.from, e.type, e.to), e.startLineInResource(), List.copyOf(meta)));
            }
        }
        return out;
    }
}
