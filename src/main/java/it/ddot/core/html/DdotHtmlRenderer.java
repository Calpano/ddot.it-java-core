package it.ddot.core.html;

import it.ddot.core.token.DdotTokenizer;
import it.ddot.core.token.Token;

import it.ddot.core.token.TokenName;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Role-classed body HTML for ddot.it source — a pure <em>fold over the canonical token stream</em>.
 *
 * <p>This class contains NO lexical rules. Every question of "what is this text" is answered by
 * {@link DdotTokenizer#tokenize(String)}; all that happens here is token → CSS class, plus HTML escaping of the text
 * the tokenizer does not tokenize (whitespace, decoration around directives, and whole lines that are not ddot.it at
 * all). That is deliberate: an earlier copy of this renderer carried its own duplicate of the tokenizer and drifted
 * from it, so the fold is the invariant that keeps highlighting, events and preview pinned by one corpus.
 *
 * <p>Two mirrors must produce byte-identical output for every case of the cross-implementation corpus at
 * {@code ../ddot.it/test-data/cases/} ({@code expected.body.html}):
 * <ul>
 *   <li>Ruby: {@code ddot-render.rb} in the IntelliJ plugin — <strong>canonical</strong>, it generates the
 *       corpus files;</li>
 *   <li>JS: {@code preview/ddot-preview.js} in the IntelliJ plugin, checked by
 *       {@code node scripts/test-preview-js.mjs}.</li>
 * </ul>
 * Neither can call Java, so both keep their own port of the tokenizer; this Java side does not.
 *
 * <p>Normative rules: {@code site/spec/ddot-it-parse.adoc} (token counting, the gate, off/on,
 * {@code !!block}) and {@code site/spec/ddot-it-highlight.adoc} (roles D1–D9).
 *
 * <p>Pure functions, no I/O, no platform types — usable from plain unit tests and from any host.
 */
public final class DdotHtmlRenderer {

    /**
     * A token nested inside another token's span: the tokenizer emits an in-text {@code command} in addition to (and
     * inside) the slot token that contains it (Highlight Spec D6), so the slot's span has to be rendered as a container
     * rather than as flat escaped text.
     */
    private record Node(Token token, List<Node> children) {

        Node(Token token) {this(token, new ArrayList<>());}

    }


    private DdotHtmlRenderer() {}

    /**
     * Body-only HTML — role-classed span markup, no {@code <style>} or {@code <pre>} wrapper. Mirrors
     * {@code DdotIt::Render.body} in {@code ddot-render.rb}; the cross-implementation golden corpus at
     * {@code ../ddot.it/test-data/cases/} asserts byte equivalence.
     */
    public static String body(String raw) {
        // Strip a single trailing newline that the Markdown lib often leaves on
        // fence bodies; preserve internal blank lines.
        String stripped = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
        String[] lines = stripped.split("\n", -1);
        List<List<Node>> perLine = byLine(DdotTokenizer.tokenize(stripped), lines.length);

        StringBuilder out = new StringBuilder(stripped.length() * 4);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            render(lines[i], perLine.get(i), 0, lines[i].length(), out);
        }
        return out.toString();
    }

    /**
     * Split the flat stream into one containment forest per line. The tokenizer emits a container before the tokens
     * nested in it and never emits overlapping siblings, so a stack over {@code [start, end)} is enough. Lines the
     * tokenizer said nothing about — blank ones, gate failures, and logical lines it RETRACTED as NotATriple — simply
     * get an empty list and are rendered as escaped plain text.
     */
    private static List<List<Node>> byLine(List<Token> tokens, int lineCount) {
        List<List<Node>> roots = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) roots.add(new ArrayList<>());

        Deque<Node> open = new ArrayDeque<>();
        int currentLine = -1;
        for (Token t : tokens) {
            if (t.line() != currentLine) {
                open.clear();
                currentLine = t.line();
            }
            if (t.line() < 0 || t.line() >= lineCount) continue;
            while (!open.isEmpty() && t.start() >= open.peek().token().end()) open.pop();
            Node n = new Node(t);
            if (open.isEmpty()) roots.get(t.line()).add(n);
            else open.peek().children().add(n);
            open.push(n);
        }
        return roots;
    }

    private static String escape(String s, int from, int to) {
        StringBuilder b = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static void openSpan(StringBuilder out, TokenName token) {
        out.append("<span class=\"").append(CssNames.cssClassOf(token)).append("\">");
    }

    /**
     * The {@code command-param} that belongs INSIDE the command at {@code i}, or {@code null}.
     *
     * <p>The tokenizer emits a command's parameter as a sibling token, but a command's own
     * {@code ?query}/{@code #fragment} is rendered nested so it inherits the command's weight. The one parameter that
     * is NOT part of its command's name is {@code !!block?end=MARKER}: there {@code ?end=} is the syntax introducing a
     * separate {@code block-end} token, and it stays a sibling (case 21-block-end-marker).
     */
    private static @Nullable Node paramOf(List<Node> nodes, int i) {
        Node cmd = nodes.get(i);
        if (!cmd.token().tokenName().equals(TokenName.COMMAND) || i + 1 >= nodes.size()) return null;
        Node next = nodes.get(i + 1);
        if (!next.token().tokenName().equals(TokenName.COMMAND_PARAM)
                || next.token().start() != cmd.token().end()) return null;
        boolean opensBlock = i + 2 < nodes.size()
                && nodes.get(i + 2).token().tokenName().equals(TokenName.BLOCK_END)
                && nodes.get(i + 2).token().start() == next.token().end();
        return opensBlock ? null : next;
    }

    /**
     * Render {@code nodes} over {@code line[lo, hi)}, escaping every gap between them. The gaps are where all the
     * not-a-token text lives: whitespace between slots, the host-language decoration around an {@code !!off} directive,
     * and every character of a line the tokenizer produced nothing for.
     */
    private static void render(String line, List<Node> nodes, int lo, int hi, StringBuilder out) {
        int pos = lo;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            Token t = n.token();
            if (t.start() > pos) out.append(escape(line, pos, t.start()));

            Node param = paramOf(nodes, i);
            if (param != null) {
                // `?query` / `#fragment` renders INSIDE its command's span.
                openSpan(out, TokenName.COMMAND);
                out.append(escape(line, t.start(), t.end()));
                openSpan(out, TokenName.COMMAND_PARAM);
                out.append(escape(line, param.token().start(), param.token().end()));
                out.append("</span></span>");
                pos = param.token().end();
                i++;
            } else {
                openSpan(out, t.tokenName());
                if (n.children().isEmpty()) {
                    out.append(escape(line, t.start(), t.end()));
                } else {
                    render(line, n.children(), t.start(), t.end(), out);
                }
                out.append("</span>");
                pos = t.end();
            }
        }
        if (pos < hi) out.append(escape(line, pos, hi));
    }

}
