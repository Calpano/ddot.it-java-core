package com.calpano.ddot.it.core.token;

import it.ddot.core.DdotSlot;
import it.ddot.core.token.Token;
import it.ddot.core.token.TokenName;
import it.ddot.core.token.Tokens;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure positional tokenizer producing the <em>canonical highlighter token stream</em> defined by the
 * cross-implementation corpus at {@code ../ddot.it/test-data/} — a flat list of {@code {line, start, end, token, text}}
 * (0-based, end-exclusive; whitespace is not tokenized).
 *
 * <p>The token vocabulary and lexical rules are specified in
 * {@code test-data/tokens.md} + {@code test-data/ddot.ebnf}. The
 * <strong>canonical</strong> generator of {@code expected.tokens.json} is the
 * TextMate grammar in the {@code ddot.it-syntax-tools} repo (npm {@code @calpano/ddot-textmate-grammar}); this Java
 * class is a conforming
 * <em>mirror</em>, asserted byte-for-byte by
 * {@code CorpusConformanceTest} here and by {@code PreviewHtmlGoldenTest} in the IntelliJ plugin. Because TextMate is
 * canonical here, the golden test never regenerates {@code expected.tokens.json} from this class — it only asserts
 * equality. If the grammar changes, refresh the corpus with {@code npm run regenerate:tokens} in syntax-tools, then
 * update this mirror until the assertion passes again.
 *
 * <p>Pure functions, no IDE dependencies — usable from plain unit tests, which is
 * why this class lives in the zero-dependency core rather than in an editor plugin. It is deliberately independent of
 * any PSI-level annotator (whose role model is coarser: no fine-grained {@code meta-*} tokens, and separators are not
 * surfaced as a distinct role).
 * <p>
 * NOTE: lexical tokens (Parse Spec, "Token Sequences"): Runs of EXACTLY n, neither preceded nor followed by the same
 * character. The lookarounds carry the rule: `\\.{2}` alone matches the first two dots of `...`, which the tokenization
 * rule forbids.
 */
public final class DdotTokenizer {

    private static final class BlockState {

        boolean opensBlock, opensMetaBlock, notATriple;
        String blockEnd;

    }

    /**
     * WS per the Parse Spec: TAB plus any Unicode <em>space separator</em> (category {@code Zs}), i.e.
     * {@code [\t\p{Zs}]}. NBSP and friends are `Zs` and separate slots exactly like a space; U+200B and U+FEFF are
     * `Cf`, not `Zs`, so they stay inside the name that contains them. Java's own {@code \s}, {@code trim()} and
     * {@code Character.isWhitespace} all get this wrong — the first two are ASCII-only and the third excludes the
     * no-break spaces — so neither is used for slot boundaries.
     */
    private static final String WS = "[\\t\\p{Zs}]";

    private static final String DT2_SRC = "(?<!\\.)\\.{2}(?!\\.)";
    private static final String QUAD_SRC = "(?<!\\.)(?:\\.{4}|\\.{2}" + WS + "+\\.{2})(?!\\.)";
    private static final String CM2_SRC = "(?<!,),,(?!,)";
    private static final String SC2_SRC = "(?<!;);;(?!;)";

    // A command needs the slash and a non-empty name; bare `ddot.it` is not one.
    // `?` and `#` do not terminate a command; they introduce its query/fragment.
    // The `!!` spelling is an EM2 token: EXACTLY two `!`, neither preceded nor
    // followed by another `!` — a 3+ run of `!` is plain text (Parse Spec, Token
    // Sequences), so `!!!off` must not read as `!` + `!!off`.
    private static final String CMD_BEGIN_SRC =
            // http:// OR https://
            "(?:https?://)?" +
                    // www.
                    "(?:www?\\.)?" +
                    // ddot.it
                    "ddot\\.it/" +
                    // OR !!
                    "|(?<!!)!!(?!!)";
    private static final String CMD_PARAM_SRC = "(?:\\?[^\\s#]*)?(?:#\\S*)?";
    private static final String CMD_SRC = CMD_BEGIN_SRC + "[^\\s?#]+" + CMD_PARAM_SRC;
    private static final String BLOCK_SRC = CMD_BEGIN_SRC + "block";

    private static final Pattern CMD_ONLY = Pattern.compile("^" + CMD_SRC + "$");
    /** In-text commands: command shape is recognised wherever it occurs (Highlight Spec D6). */
    private static final Pattern CMD_ANYWHERE = Pattern.compile(CMD_SRC);
    // `!!off` / `!!on` are recognised ANYWHERE on a line, not only alone on one:
    // the common way to write them is inside a host-language comment
    // (`<!-- ddot.it/off -->`, `# !!off`). The directive line itself yields only
    // the command token; the decoration around it stays unscoped, and the region
    // starts on the NEXT line. `(?![^\s?#])` pins the command NAME, so
    // `!!office` is not `!!off`. Group 2 is the optional `?query#fragment` part,
    // a `command-param` token of its own.
    private static final Pattern OFF_LINE =
            Pattern.compile("(" + CMD_BEGIN_SRC + "off)(?![^\\s?#])(" + CMD_PARAM_SRC + ")");
    private static final Pattern ON_LINE =
            Pattern.compile("(" + CMD_BEGIN_SRC + "on)(?![^\\s?#])(" + CMD_PARAM_SRC + ")");
    /** A block opener must END its physical line (block-as-field). */
    private static final Pattern BLOCK_OPENER =
            Pattern.compile("(" + BLOCK_SRC + ")(?:(\\?end=)(\\S+))?" + WS + "*$");

    /**
     * A line-initial `,,` or `;;` followed by a meta pair: a metadata continuation of the previous logical line (Parse
     * Spec "block-continuation" — CM2 after a block object, SC2 after a block meta object). Group 1 is CM2, group 2
     * SC2; they carry different tokens.
     */
    private static final Pattern META_CONTINUATION = Pattern.compile(
            "^" + WS + "*(?:((?<!,),,(?!,))|((?<!;);;(?!;)))(?=" + WS + "+(?<!\\.)\\.{2}(?!\\.))");

    /** QUAD before DT2 so `.. ..` is ONE operator, not two. */
    private static final Pattern SIGNIFICANT =
            Pattern.compile(QUAD_SRC + "|" + DT2_SRC + "|" + CM2_SRC + "|" + SC2_SRC);

    /**
     * The line-shape gate: a line is ddot.it only when it carries a complete operator skeleton — two DT2, or one DT4 /
     * `.. ..`. A lone `..` in prose does not qualify, which is what keeps 23-not-a-triple entirely plain.
     */
    private static final Pattern GATE = Pattern.compile(
            "(?<!\\.)\\.{4}(?!\\.)" +
                    "|" +
                    "(?<!\\.)\\.{2}(?!\\.).*(?<!\\.)\\.{2}(?!\\.)");
    // Whole-string directive tests, shared so callers need not re-derive the
    // four spellings (× optional query/fragment) of each directive themselves.
    private static final Pattern OFF_ONLY =
            Pattern.compile(CMD_BEGIN_SRC + "off(?![^\\s?#])" + CMD_PARAM_SRC);
    private static final Pattern ON_ONLY =
            Pattern.compile(CMD_BEGIN_SRC + "on(?![^\\s?#])" + CMD_PARAM_SRC);

    private DdotTokenizer() {}

    private static void emitBlockOpener(List<Token> out, int lineNo, String line, Matcher bo) {
        out.add(Token.of(lineNo, bo.start(1), bo.end(1), TokenName.COMMAND, bo.group(1)));
        if (bo.group(2) != null) {
            out.add(Token.of(lineNo, bo.start(2), bo.end(2), TokenName.COMMAND_PARAM, bo.group(2)));
            out.add(Token.of(lineNo, bo.start(3), bo.end(3), TokenName.BLOCK_END, bo.group(3)));
        }
    }

    /**
     * Emit a command whose text spans {@code [start, start + cmd.length())}: the prefix + name as {@code command}, and
     * — when present — the `?query#fragment` part as a {@code command-param} of its own (Parse Spec: `?`/`#` do not
     * terminate a command, they end only its NAME).
     */
    private static void emitCommand(List<Token> out, int lineNo, int start, String cmd) {
        int q = cmd.indexOf('?');
        int h = cmd.indexOf('#');
        int p = q < 0 ? h : (h < 0 ? q : Math.min(q, h));
        if (p < 0) {
            out.add(Token.of(lineNo, start, start + cmd.length(), TokenName.COMMAND, cmd));
        } else {
            out.add(Token.of(lineNo, start, start + p, TokenName.COMMAND, cmd.substring(0, p)));
            out.add(Token.of(lineNo, start + p, start + cmd.length(), TokenName.COMMAND_PARAM, cmd.substring(p)));
        }
    }

    /**
     * Emit an off/on directive matched by {@link #OFF_LINE}/{@link #ON_LINE}: group 1 is the command, group 2 its
     * optional `?query#fragment` param.
     */
    private static void emitCommandMatch(List<Token> out, int lineNo, Matcher m) {
        out.add(Token.of(lineNo, m.start(1), m.end(1), TokenName.COMMAND, m.group(1)));
        if (!m.group(2).isEmpty()) {
            out.add(Token.of(lineNo, m.start(2), m.end(2), TokenName.COMMAND_PARAM, m.group(2)));
        }
    }

    private static void emitGroup(List<Token> out, int lineNo, String line,
                                  Matcher m, int group, TokenName token) {
        out.add(Token.of(lineNo, m.start(group), m.end(group), token, m.group(group)));
    }

    /**
     * Emit one line as a sequence of slots and operators, starting in {@code phase}. Mirrors the parse automaton: the
     * object may contain `..`, `;;` separates inline meta pairs only, and a `!!block` that ends the line opens a
     * verbatim region. {@code mark} is the token count before this logical line started — when the automaton lands in
     * NotATriple, every token of the line (including a leading continuation delimiter the caller already emitted) is
     * retracted and the line yields nothing.
     */
    private static BlockState emitLine(List<Token> out, int lineNo, String line,
                                       DdotSlot phase, boolean inBlock, int mark) {
        BlockState st = new BlockState();
        Matcher m = SIGNIFICANT.matcher(line);
        int last = 0;

        while (m.find()) {
            String sep = m.group();
            boolean isQuad = sep.length() == 4 || sep.indexOf(' ') >= 0 || sep.indexOf('\t') >= 0;
            boolean isComma = sep.equals(",,");
            boolean isSemi = sep.equals(";;");
            int sepStart = m.start(), sepEnd = m.end();

            // Once in an object-ish phase, `..` is content, not a separator.
            if (!isComma && !isSemi && (phase == DdotSlot.OBJECT || phase == DdotSlot.META_OBJECT)) continue;
            // `;;` separates inline meta pairs ONLY. Inside a `,,` block,
            // MetaObjectInBlock := TextExcept{NL}, so it is ordinary content
            // (28-semicolon-in-meta-block).
            if (isSemi && (inBlock || phase != DdotSlot.META_OBJECT)) continue;
            if (isComma && phase != DdotSlot.OBJECT) continue;

            // MetaNextInline: after `;;`, only whitespace may precede the next
            // `..`/`....`. Any other content sends the WHOLE line to NotATriple.
            if (phase == DdotSlot.META_NEXT && !trimWs(line.substring(last, sepStart)).isEmpty()) {
                return notATriple(out, mark, st);
            }

            // The relation-closing operator is always a plain `..`, never the
            // untyped `.. ..` form. Without this, an object that begins with
            // `..` (a relative path) merges with the closing marker and the
            // pair is read as one QuadDot — `a ..links to.. ../../b.adoc`
            // would lose the object's first two dots (20-object-dotdot).
            if (isQuad && sep.length() != 4
                    && (phase == DdotSlot.RELATION || phase == DdotSlot.META_RELATION)) {
                isQuad = false;
                sep = line.substring(sepStart, sepStart + 2);
                sepEnd = sepStart + 2;
            }

            // A `!!block` ending the line makes the pending slot a block opener —
            // but only when that slot is a value position (block-as-field).
            if (opensBlockIn(phase)) {
                Matcher bo = BLOCK_OPENER.matcher(line);
                if (bo.find(last) && trimWs(line.substring(last, bo.start())).isEmpty()
                        && bo.start() < m.start()) {
                    emitBlockOpener(out, lineNo, line, bo);
                    st.opensBlock = true;
                    st.blockEnd = bo.group(3);
                    return st;
                }
            }

            emitSlot(out, lineNo, line, last, sepStart, phase);
            out.add(Token.of(lineNo, sepStart, sepEnd,
                    isComma ? TokenName.META_DELIM : isSemi ? TokenName.META_SEPARATOR
                            : (phase == DdotSlot.META_START || phase == DdotSlot.META_NEXT
                            || phase == DdotSlot.META_RELATION || phase == DdotSlot.META_OBJECT || inBlock)
                            ? TokenName.META_DOUBLEDOT : TokenName.DOUBLEDOT,
                    sep));
            last = sepEnd;

            phase = switch (phase) {
                case SUBJECT -> isQuad ? DdotSlot.OBJECT : DdotSlot.RELATION;
                case RELATION -> DdotSlot.OBJECT;
                case OBJECT -> isComma ? DdotSlot.META_START : DdotSlot.OBJECT;
                // The untyped meta form `,, .... obj` skips the meta relation.
                case META_START, META_NEXT -> isQuad ? DdotSlot.META_OBJECT : DdotSlot.META_RELATION;
                case META_RELATION -> DdotSlot.META_OBJECT;
                case META_OBJECT -> isSemi ? DdotSlot.META_NEXT : DdotSlot.META_OBJECT;
            };
        }

        // Trailing slot, or a block opener with no separator after it.
        if (opensBlockIn(phase)) {
            Matcher bo = BLOCK_OPENER.matcher(line);
            if (bo.find(last) && trimWs(line.substring(last, bo.start())).isEmpty()) {
                emitBlockOpener(out, lineNo, line, bo);
                st.opensBlock = true;
                st.blockEnd = bo.group(3);
                return st;
            }
        }
        // MetaNextInline never accepts a NL: a line that ends right after `;;`
        // (or with stray content after it) is NotATriple in its entirety.
        if (phase == DdotSlot.META_NEXT) {
            return notATriple(out, mark, st);
        }
        if (trimWs(line.substring(last)).isEmpty() && phase == DdotSlot.META_START) {
            st.opensMetaBlock = true;   // trailing `,,` opened a block
        } else {
            emitSlot(out, lineNo, line, last, line.length(), phase);
        }
        return st;
    }

    private static void emitSlot(List<Token> out, int lineNo, String line,
                                 int lo, int hi, DdotSlot phase) {
        int[] r = trimRange(line, lo, hi);
        if (r == null) return;
        String text = line.substring(r[0], r[1]);
        if (isCommand(text)) {
            // A slot whose whole text is a command IS a command (tokens.md,
            // "Commands inside slots"); its `?query`/`#fragment` part is a
            // separate command-param token.
            emitCommand(out, lineNo, r[0], text);
            return;
        }
        TokenName tok = switch (phase) {
            case SUBJECT -> TokenName.SUBJECT;
            case RELATION -> TokenName.RELATION;
            case OBJECT -> TokenName.OBJECT;
            case META_RELATION -> TokenName.META_RELATION;
            case META_OBJECT -> TokenName.META_OBJECT;
            // Text after `,,` (or, unreachably, after `;;`) with no `..`.
            case META_START, META_NEXT -> TokenName.META_TEXT;
        };
        out.add(Token.of(lineNo, r[0], r[1], tok, text));
        // In-text commands (Highlight Spec D6): a command INSIDE the slot's text
        // is part of the field's value AND lights up — a nested `command` token
        // over just the command span, in addition to the slot token above.
        Matcher cm = CMD_ANYWHERE.matcher(text);
        while (cm.find()) {
            if (cm.start() == 0 && cm.end() == text.length()) continue; // whole slot, handled above
            emitCommand(out, lineNo, r[0] + cm.start(), cm.group());
        }
    }

    private static void emitTrimmed(List<Token> out, int lineNo, String line, TokenName token) {
        int[] r = trimRange(line, 0, line.length());
        if (r == null) return;
        out.add(Token.of(lineNo, r[0], r[1], token, line.substring(r[0], r[1])));
    }

    private static boolean isCommand(String s) {return CMD_ONLY.matcher(s).matches();}

    /**
     * True when {@code text} (surrounding whitespace ignored) is exactly an `off` directive in any of its four command
     * spellings — `!!off`, `ddot.it/off`, `http://ddot.it/off`, `https://ddot.it/off` — with an optional
     * `?query`/`#fragment`. The name is matched exactly (`!!office` is no directive) and the `!!` spelling is a strict
     * EM2, so `!!!off` is not one either.
     */
    public static boolean isOffCommand(String text) {
        return OFF_ONLY.matcher(trimWs(text)).matches();
    }

    /** True when {@code text} is exactly an `on` directive; see {@link #isOffCommand}. */
    public static boolean isOnCommand(String text) {
        return ON_ONLY.matcher(trimWs(text)).matches();
    }

    /** Retract every token of the current logical line; the line yields nothing. */
    private static BlockState notATriple(List<Token> out, int mark, BlockState st) {
        while (out.size() > mark) out.remove(out.size() - 1);
        st.opensBlock = false;
        st.opensMetaBlock = false;
        st.blockEnd = null;
        st.notATriple = true;
        return st;
    }

    /**
     * A `!!block` opener is valid ONLY in a value position. The spec names four: Subject, Object, MetaObject, and the
     * free meta text after `,,` (META_START). Relation and MetaRelation hold names, so a block there stays in-text
     * text.
     */
    private static boolean opensBlockIn(DdotSlot phase) {
        return phase == DdotSlot.SUBJECT || phase == DdotSlot.OBJECT
                || phase == DdotSlot.META_OBJECT || phase == DdotSlot.META_START;
    }

    private static boolean startsWithOperator(String line) {
        Matcher m = SIGNIFICANT.matcher(line);
        return m.find() && trimWs(line.substring(0, m.start())).isEmpty();
    }

    /** Tokenize ddot source into the canonical highlighter token stream. */
    public static List<Token> tokenize(String raw) {
        return tokenize(raw, false);
    }

    /**
     * Tokenize with an explicit initial excluded state. {@code startExcluded} is {@code true} for a chunk that begins
     * inside a host-level `!!off` … `!!on` region (Parse Spec, pre-parse recognition: off/on directives written in the
     * HOST text — e.g. AsciiDoc comments — exclude the chunks that follow them).
     */
    public static List<Token> tokenize(String raw, boolean startExcluded) {
        List<Token> out = new ArrayList<>();
        String[] lines = raw.split("\n", -1);

        boolean off = startExcluded;  // inside !!off … !!on
        boolean verbatim = false;     // inside a !!block body
        String blockEnd = null;       // custom ?end= marker, null = blank-line form
        boolean inMetaBlock = false;  // inside a ,, … ,, block
        // A standalone `,,` attaches ONLY to the IMMEDIATELY preceding triple
        // line (MetaBlockOpen's second alternative is `NL WS* CM2` — WS never
        // matches a newline, so a blank line in between breaks the attachment).
        boolean prevLineTriple = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = trimWs(line);

            // --- excluded span -------------------------------------------
            if (off) {
                Matcher on = ON_LINE.matcher(line);
                if (on.find()) {
                    emitCommandMatch(out, i, on);
                    off = false;
                } else if (!trimmed.isEmpty()) emitTrimmed(out, i, line, TokenName.EXCLUDED);
                prevLineTriple = false;
                continue;
            }
            // --- verbatim (!!block body) ---------------------------------
            if (verbatim) {
                if (blockEnd != null) {
                    if (trimmed.equals(blockEnd)) {
                        emitTrimmed(out, i, line, TokenName.BLOCK_END);
                        verbatim = false;
                        blockEnd = null;
                        prevLineTriple = true;   // the block completed its triple
                    } else if (!trimmed.isEmpty()) {
                        emitTrimmed(out, i, line, TokenName.VERBATIM);
                    }
                } else if (trimmed.isEmpty()) {
                    verbatim = false;      // blank line ends the unmarked form
                    prevLineTriple = true; // …and completes the block's triple
                } else {
                    emitTrimmed(out, i, line, TokenName.VERBATIM);
                }
                continue;
            }
            Matcher offM = OFF_LINE.matcher(line);
            if (offM.find()) {
                emitCommandMatch(out, i, offM);
                off = true;
                inMetaBlock = false;
                prevLineTriple = false;
                continue;
            }

            // --- inside a ,, block ---------------------------------------
            if (inMetaBlock) {
                if (trimmed.isEmpty()) {
                    // A blank line ABORTS an unmarked `,,` block (automaton:
                    // BlockFirstLine/BlockTripleLine --NL--> StartOfLine).
                    inMetaBlock = false;
                    prevLineTriple = false;
                } else if (trimmed.equals(",,")) {
                    emitTrimmed(out, i, line, TokenName.META_DELIM);
                    inMetaBlock = false;
                    prevLineTriple = false;
                } else {
                    if (SIGNIFICANT.matcher(line).find() && startsWithOperator(line)) {
                        // `..mr.. mo` — MetaObjectInBlock runs to end of line, so `;;` is content.
                        emitLine(out, i, line, DdotSlot.META_START, true, out.size());
                    } else {
                        emitTrimmed(out, i, line, TokenName.META_TEXT);
                    }
                }
                continue;
            }
            if (trimmed.isEmpty()) {
                prevLineTriple = false;
                continue;
            }

            // --- a standalone `,,` opens a block for the previous triple ---
            // Only when that triple sits on the line immediately above: a `,,`
            // that follows no triple (or a blank line) is NotATriple — no tokens.
            if (trimmed.equals(",,")) {
                if (prevLineTriple) {
                    emitTrimmed(out, i, line, TokenName.META_DELIM);
                    inMetaBlock = true;
                }
                prevLineTriple = false;
                continue;
            }

            // --- `,, ..mr.. mo` continues the previous logical line ----------
            // Parse Spec "block-continuation": after a block that filled the
            // OBJECT, a leading CM2 continues the line as metadata. Without
            // this the line reads as a fresh triple whose subject is `,,`.
            // Keyed on shape: a per-line pass cannot see the preceding block.
            Matcher mc = META_CONTINUATION.matcher(line);
            if (mc.find()) {
                int mark = out.size();
                int g = mc.group(1) != null ? 1 : 2;
                emitGroup(out, i, line, mc, g, g == 1 ? TokenName.META_DELIM : TokenName.META_SEPARATOR);
                emitLine(out, i, line.substring(0, mc.end(g)).replaceAll("[^ \\t]", " ")
                        + line.substring(mc.end(g)), DdotSlot.META_START, false, mark);
                continue;
            }

            // --- `!!block` alone on a line = a SUBJECT opener --------------
            Matcher bo = BLOCK_OPENER.matcher(line);
            if (bo.find() && trimWs(line.substring(0, bo.start())).isEmpty()) {
                emitBlockOpener(out, i, line, bo);
                verbatim = true;
                blockEnd = bo.group(3);
                prevLineTriple = false;
                continue;
            }

            // --- the gate -------------------------------------------------
            if (!GATE.matcher(line).find()) {
                prevLineTriple = false;
                continue;
            }   // NotATriple: no tokens

            BlockState st = emitLine(out, i, line, DdotSlot.SUBJECT, false, out.size());
            if (st.opensBlock) {
                verbatim = true;
                blockEnd = st.blockEnd;
                prevLineTriple = false;
            } else if (st.opensMetaBlock) {
                inMetaBlock = true;
                prevLineTriple = false;
            } else prevLineTriple = !st.notATriple;
        }
        return out;
    }

    /** Range of the non-whitespace span within [lo, hi), or {@code null} if blank. */
    private static int @Nullable [] trimRange(String line, int lo, int hi) {
        int s = lo, e = hi;
        while (s < e && Tokens.isWs(line.charAt(s))) s++;
        while (e > s && Tokens.isWs(line.charAt(e - 1))) e--;
        return s < e ? new int[]{s, e} : null;
    }

    /** {@link String#trim()} over the spec's WS class. */
    private static String trimWs(String s) {
        int[] r = trimRange(s, 0, s.length());
        return r == null ? "" : s.substring(r[0], r[1]);
    }

}
