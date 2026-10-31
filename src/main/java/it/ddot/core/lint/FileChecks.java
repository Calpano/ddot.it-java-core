package it.ddot.core.lint;

import it.ddot.core.embed.DdotEmbeds;
import it.ddot.core.token.DdotTokenizer;
import it.ddot.core.token.Token;
import it.ddot.core.token.TokenName;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The checks that need nothing but one file: {@value DdotAnalysis#INCOMPLETE_TRIPLE} on the token stream,
 * and the report-only diagnostics of {@link DdotLinter} (DL010–DL012). Run per embedded chunk and mapped
 * to host lines.
 */
final class FileChecks {

    private FileChecks() {}

    static List<Finding> check(String url, @Nullable String extension, CharSequence text) {
        List<Finding> out = new ArrayList<>();
        for (DdotEmbeds.Chunk chunk : DdotEmbeds.extractChunks(extension, text)) {
            int line0 = chunk.firstLine() - 1; // host line (0-based) of the chunk's first line
            Set<Integer> incomplete = new HashSet<>();
            for (Finding f : incompleteTriples(url, chunk, line0)) {
                incomplete.add(f.line());
                out.add(f);
            }
            // The linter has no notion of a chunk that starts inside !!off, so it only sees chunks that start on.
            if (chunk.startsOff()) continue;
            for (Diagnostic d : new DdotLinter().lint(chunk.text()).diagnostics()) {
                if (d.severity() == Severity.FIX) continue;
                int line = line0 + d.lineNumber();
                if (incomplete.contains(line)) continue; // one warning per line is enough
                out.add(new Finding(d.ruleId(), d.severity(), url, line, -1, -1, d.message(), List.of()));
            }
        }
        return out;
    }

    /**
     * {@value DdotAnalysis#INCOMPLETE_TRIPLE}: a triple line whose object slot is empty — {@code s ..p..} or
     * {@code ..p..} alone on a line. These parse to nothing in the event stream, which is a footgun.
     *
     * <p>A line is incomplete when it carries a main-part {@code doubledot} operator but nothing fills the
     * slot after the last one: no {@code object} and no command-shaped field (a {@code !!block} opener is a
     * filled object). Meta-only continuations, verbatim bodies and excluded lines never carry a main
     * {@code doubledot} token, so they are naturally exempt.
     */
    private static List<Finding> incompleteTriples(String url, DdotEmbeds.Chunk chunk, int line0) {
        List<Finding> out = new ArrayList<>();
        List<Token> tokens = DdotTokenizer.tokenize(chunk.text(), chunk.startsOff());
        int i = 0;
        int n = tokens.size();
        while (i < n) {
            int line = tokens.get(i).line();
            int j = i;
            Token lastDoubleDot = null;
            boolean filledAfter = false;
            while (j < n && tokens.get(j).line() == line) {
                Token t = tokens.get(j);
                switch (t.tokenName()) {
                    case TokenName.DOUBLEDOT -> {
                        lastDoubleDot = t;
                        filledAfter = false;
                    }
                    case TokenName.OBJECT, TokenName.COMMAND, TokenName.COMMAND_PARAM, TokenName.BLOCK_END ->
                            filledAfter = true;
                    default -> {
                    }
                }
                j++;
            }
            if (lastDoubleDot != null && !filledAfter) {
                out.add(new Finding(DdotAnalysis.INCOMPLETE_TRIPLE, Severity.WARN, url, line0 + line + 1,
                        lastDoubleDot.start(), lastDoubleDot.end(), "Incomplete ddot.it triple: object is missing",
                        List.of()));
            }
            i = j;
        }
        return out;
    }
}
