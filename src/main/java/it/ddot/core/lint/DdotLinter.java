package it.ddot.core.lint;

import it.ddot.core.token.Commands;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure, graph-free linter/formatter for plain {@code .ddot} line content — the engine behind {@code ddot lint}
 * / {@code ddot fmt}, specified in {@code linting.adoc}.
 *
 * <p>Given source text it produces (a) the canonical-form text and (b) a list of {@link Diagnostic}s. It
 * implements the whitespace-normalisation FIX rules DL001–DL009 (re-spelling every recognised triple/command
 * line to the one canonical form) and the raw-line pitfall diagnostics DL010–DL012 (report-only). The FIX
 * transform is exactly the mapping specified there: <em>parse the recognised triple on a line, re-emit in the
 * canonical {@code S ..P.. O} / {@code S .... O} / {@code ,, ..k.. v} spelling, splice back</em> — reusing the
 * same canonical spelling as
 * {@link it.ddot.core.io.DdotWriter}.
 *
 * <h2>Hard invariants (see the unit tests)</h2>
 * <ul>
 *   <li><b>Meaning-preserving:</b> {@code parse(lint(x)) == parse(x)} — the multiset of triples is unchanged.</li>
 *   <li><b>Idempotent:</b> {@code lint(lint(x)) == lint(x)}.</li>
 *   <li><b>Prose-safe:</b> a line not recognised as a triple/command is byte-for-byte untouched.</li>
 *   <li><b>Object verbatim:</b> everything after the second {@code ..} separator (URLs, {@code ..} in objects,
 *       relative paths) is preserved verbatim, edge-trimmed only.</li>
 *   <li><b>Verbatim/block sacred:</b> the interior of {@code !!block} regions and any line touching
 *       {@code !!verbatim} is never reformatted.</li>
 * </ul>
 *
 * <h2>Line grammar</h2>
 * The recogniser mirrors {@code it.ddot.core.source.handler.Source2TripleEvents} (artifact {@code ddotit-javalib}) exactly — the
 * {@link #TRIPLE_LINE} regex, the "exactly two {@code ..} separators / object-may-contain-{@code ..}" rule, the
 * first-{@code ,,} metadata split, the {@code !!off}/{@code !!on} and {@code !!block} handling — so a line the
 * reader ingests is exactly a line the linter reformats, and a line the reader drops is left as prose.
 *
 * <h2>Deferred</h2>
 * Host-document reformatting (Markdown/AsciiDoc/HTML via the reader snippet mapping) is <em>not</em> handled
 * here; this engine formats plain {@code .ddot} line content. The DL013–DL016 diagnostics are also not yet
 * implemented (they need dictionary/multi-line context); DL010–DL012 are.
 */
public final class DdotLinter {

    /**
     * A legal ddot.it triple line — <b>copied verbatim from
     * {@code it.ddot.core.source.handler.Source2TripleEvents} (artifact {@code ddotit-javalib})</b> and MUST stay in sync with it, so the
     * linter recognises exactly the lines the reader ingests. Exactly two structural {@code ..} separators;
     * subject and relation contain no {@code ..}; the object is the remainder and must start with a non-dot.
     */
    private static final Pattern TRIPLE_LINE = Pattern.compile(
            "((?:(?!\\.\\.).)*)\\.\\.((?:(?!\\.\\.).)*)\\.\\.((?!\\.).+)");

    /** Standalone commands that the reader honours only when they are the entire trimmed line. */
    private static final Set<String> ON_COMMANDS = Set.of("ddot.it/on", "!!on");
    private static final Set<String> OFF_COMMANDS = Set.of("ddot.it/off", "!!off");

    /** Configuration for a lint run. */
    public record Options(boolean includeOff, @Nullable Set<String> select, @Nullable Set<String> ignore) {

        public static Options defaults() {
            return new Options(false, null, null);
        }

        /** True when rule {@code id} is enabled under the current {@code select}/{@code ignore} scoping. */
        boolean enabled(String id) {
            if (ignore != null && ignore.contains(id)) return false;
            return select == null || select.contains(id);
        }
    }

    private final Options options;

    public DdotLinter() {
        this(Options.defaults());
    }

    public DdotLinter(Options options) {
        this.options = options;
    }

    /** Lints {@code text} split on {@code \n}. A trailing newline yields a trailing empty line, preserved. */
    public LintResult lint(String text) {
        return lint(List.of(text.split("\n", -1)));
    }

    /** Lints {@code lines} (1-based line numbers), the raw content of a plain {@code .ddot} source. */
    public LintResult lint(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        List<LineChange> changes = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();

        boolean enabled = true;
        boolean inBlock = false;
        @Nullable String blockEndMarker = null;

        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String line = lines.get(i);

            // --- inside a !!block object literal: every line is verbatim until the end marker (sacred) ---
            if (inBlock) {
                out.add(line);
                if (isBlockEnd(line, blockEndMarker)) {
                    inBlock = false;
                    blockEndMarker = null;
                }
                continue;
            }

            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                out.add(line);
                continue;
            }

            // --- on/off region commands: honoured only when alone on the trimmed line (tolerating a full-URL
            // form like https://ddot.it/off via the reader's own normaliser, so lint stays in sync) ---
            String command = Commands.normalizeCommandToken(trimmed);
            if (OFF_COMMANDS.contains(command)) {
                enabled = false;
                emit(out, changes, diagnostics, lineNo, line, trimmed, "DL009");
                continue;
            }
            if (ON_COMMANDS.contains(command)) {
                enabled = true;
                emit(out, changes, diagnostics, lineNo, line, trimmed, "DL009");
                continue;
            }
            if (!enabled && !options.includeOff()) {
                // Inside an !!off region → treated as prose, left byte-for-byte (unless --include-off).
                out.add(line);
                continue;
            }

            // --- verbatim is sacred: never reformat a line that carries an inline/opening !!verbatim marker ---
            if (trimmed.contains("!!verbatim")) {
                out.add(line);
                continue;
            }

            // --- split metadata on the FIRST ",," (mirrors the reader) ---
            int commaIdx = trimmed.indexOf(",,");
            String triplePart = commaIdx >= 0 ? trimmed.substring(0, commaIdx).strip() : trimmed;
            String metaText = commaIdx >= 0 ? trimmed.substring(commaIdx + 2).strip() : null;

            String[] parts = splitTripleLine(triplePart);
            if (parts == null) {
                // Not a recognised triple → prose-safe (untouched), but scan for dead-dot-run pitfalls.
                out.add(line);
                addRawDiagnostics(diagnostics, lineNo, triplePart);
                continue;
            }

            String rawFrom = parts[0].strip();
            String rawType = parts[1].strip();
            String object = parts[2].strip();
            if (object.isEmpty()) {
                // Reader drops an empty-object line → leave as prose (meaning preserved: nothing before/after).
                out.add(line);
                continue;
            }

            // DL012: ".." in the subject/relation slot (an ambiguous, likely-unintended separator). This is a
            // report-only pitfall: the reader's greedy split is not what the author meant, so the linter must NOT
            // reformat (never guess a fix) — it warns and leaves the line byte-for-byte.
            if (addSubjectRelationDotDiagnostic(diagnostics, lineNo, triplePart)) {
                out.add(line);
                continue;
            }

            // --- !!block start: canonicalise only the command line; the interior is entered verbatim ---
            String blockMarker = blockEndMarkerOf(object);
            boolean isBlock = isBlockStart(object);
            if (isBlock) {
                String canonical = buildCanonical(rawFrom, rawType, object, metaText);
                emit(out, changes, diagnostics, lineNo, line, canonical, classify(rawType, triplePart, metaText != null));
                inBlock = true;
                blockEndMarker = blockMarker;
                continue;
            }

            String canonical = buildCanonical(rawFrom, rawType, object, metaText);
            emit(out, changes, diagnostics, lineNo, line, canonical, classify(rawType, triplePart, metaText != null));
        }

        return new LintResult(out, changes, diagnostics);
    }

    /** Adds the canonical line to {@code out}; if it differs from {@code original}, records a FIX change+diag. */
    private void emit(List<String> out, List<LineChange> changes, List<Diagnostic> diagnostics,
                      int lineNo, String original, String canonical, String ruleId) {
        if (!options.enabled(ruleId)) {
            // Rule scoped out → keep the original line, no change recorded.
            out.add(original);
            return;
        }
        out.add(canonical);
        if (!canonical.equals(original)) {
            changes.add(new LineChange(lineNo, original, canonical, ruleId));
            diagnostics.add(Diagnostic.fix(ruleId, lineNo, "reformatted to canonical form: " + canonical));
        }
    }

    // ---- canonical construction -------------------------------------------------------------------------

    /**
     * Builds the one canonical spelling for a parsed triple. Subject/relation/object are already edge-trimmed;
     * the object is kept verbatim (only its edges were trimmed). An empty subject yields the continuation form
     * {@code ..P.. O}; an empty relation yields the untyped {@code ....} operator.
     */
    private static String buildCanonical(String rawFrom, String rawType, String object, @Nullable String metaText) {
        String relClause = rawType.isEmpty() ? "...." : ".." + rawType + "..";
        StringBuilder sb = new StringBuilder();
        if (rawFrom.isEmpty()) {
            sb.append(relClause).append(' ').append(object); // continuation: flush-left, no leading space
        } else {
            sb.append(rawFrom).append(' ').append(relClause).append(' ').append(object);
        }
        appendMeta(sb, metaText);
        return sb.toString();
    }

    /**
     * Appends the canonicalised metadata suffix. Mirrors the reader's single-pair view (it splits a meta part on
     * its first two {@code ..} only, so the value may itself contain {@code ..}):
     * <ul>
     *   <li>a {@code ..k.. v} pair → {@code ␣,,␣..k..␣v} (or {@code ␣,,␣....␣v} for an empty key), value verbatim;</li>
     *   <li>anything else (a free note, or an empty value) → {@code ␣,,␣<text>} verbatim.</li>
     * </ul>
     * This is the spec's "meta caveat": multiple {@code ,,} pairs fall back to edge-trim spacing (the trailing
     * pairs ride inside the verbatim value) rather than being dropped — meaning-preservation outranks full
     * canonicalisation.
     */
    private static void appendMeta(StringBuilder sb, @Nullable String metaText) {
        if (metaText == null) return;
        String[] pair = splitMetaValue(metaText);
        if (pair != null) {
            String key = pair[1].strip();
            String value = pair[2].strip();
            if (!value.isEmpty()) {
                sb.append(" ,, ").append(key.isEmpty() ? "...." : ".." + key + "..").append(' ').append(value);
                return;
            }
        }
        // An empty meta part is a multi-line metadata block OPENER: the canonical
        // form is a triple line ending in `␣,,` with nothing after it, so appending
        // the usual separating space would leave end-of-line whitespace and violate
        // DL009 (which this same pass is supposed to enforce).
        if (metaText.isEmpty()) {
            sb.append(" ,,");
            return;
        }
        // free note or empty-value: preserve verbatim (edge-trimmed), matching the reader's IMeta {"": text}.
        sb.append(" ,, ").append(metaText);
    }

    // ---- diagnostics (DL010–DL012) ----------------------------------------------------------------------

    /** DL010 (dead dot-run) / DL011 (separator butts dots) on a line that does <em>not</em> parse as a triple. */
    private void addRawDiagnostics(List<Diagnostic> diagnostics, int lineNo, String triplePart) {
        int maxRun = longestSeparatorDotRun(triplePart);
        if (maxRun < 3 || maxRun == 4) return; // 2 = operator, 4 = untyped operator; neither is dead
        if (maxRun >= 6) {
            if (options.enabled("DL011")) {
                diagnostics.add(Diagnostic.warn("DL011", lineNo,
                        "separator butts dots (" + maxRun + " dots in a row): the line is ignored — likely a mistyped link"));
            }
        } else if (options.enabled("DL010")) {
            diagnostics.add(Diagnostic.warn("DL010", lineNo,
                    "dead dot-run (" + maxRun + " dots in a row): not an operator, so the line is ignored — likely a typo for '..' or '....'"));
        }
    }

    /**
     * DL012: a {@code ..} sits in the subject or relation slot — the line parses, but its first separator is
     * fully glued to word characters ({@code a..b}) while another separator is spaced, so the reader's greedy
     * split takes a subject/relation the author almost certainly did not intend (e.g. {@code a..b ..reads.. o}
     * is read as {@code a / b / reads.. o}). Deliberately conservative: a uniformly glued line like
     * {@code a..knows..b} (a valid intentional triple) and an object's {@code ..} do not fire it.
     */
    private boolean addSubjectRelationDotDiagnostic(List<Diagnostic> diagnostics, int lineNo, String triplePart) {
        if (!options.enabled("DL012")) return false;
        int i1 = triplePart.indexOf("..");
        int i2 = i1 < 0 ? -1 : triplePart.indexOf("..", i1 + 2);
        if (i1 < 0 || i2 < 0) return false;
        if (!isGluedSeparator(triplePart, i1)) return false;   // first separator not word-glued → not the footgun
        if (!hasSpacedSeparator(triplePart, i2)) return false; // no mixed spaced separator → likely intentional
        diagnostics.add(Diagnostic.warn("DL012", lineNo,
                "'..' in the subject or relation: the reader's greedy split may not match what you meant — "
                        + "subject/relation must not contain '..'"));
        return true;
    }

    /** The longest run of consecutive dots that is flanked by whitespace or a line boundary on both sides. */
    private static int longestSeparatorDotRun(String s) {
        int best = 0;
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '.') {
                int j = i;
                while (j < s.length() && s.charAt(j) == '.') j++;
                boolean leftFree = i == 0 || Character.isWhitespace(s.charAt(i - 1));
                boolean rightFree = j == s.length() || Character.isWhitespace(s.charAt(j));
                if (leftFree && rightFree) best = Math.max(best, j - i);
                i = j;
            } else {
                i++;
            }
        }
        return best;
    }

    /** True when the {@code ..} at {@code idx} is flanked by non-space, non-dot chars on both sides (word-glued). */
    private static boolean isGluedSeparator(String s, int idx) {
        boolean leftGlued = idx > 0 && isNeitherSpaceNorDot(s.charAt(idx - 1));
        int after = idx + 2;
        boolean rightGlued = after < s.length() && isNeitherSpaceNorDot(s.charAt(after));
        return leftGlued && rightGlued;
    }

    /** True when the {@code ..} at {@code idx} has whitespace on at least one side (a spaced separator). */
    private static boolean hasSpacedSeparator(String s, int idx) {
        boolean leftSpace = idx == 0 || Character.isWhitespace(s.charAt(idx - 1));
        int after = idx + 2;
        boolean rightSpace = after >= s.length() || Character.isWhitespace(s.charAt(after));
        return leftSpace || rightSpace;
    }

    private static boolean isNeitherSpaceNorDot(char c) {
        return c != '.' && !Character.isWhitespace(c);
    }

    // ---- grammar helpers (mirror Source2TripleEvents) --------------------------------------------------

    private static String @Nullable [] splitTripleLine(String s) {
        java.util.regex.Matcher m = TRIPLE_LINE.matcher(s);
        if (!m.matches()) return null;
        return new String[]{m.group(1), m.group(2), m.group(3)};
    }

    private static String @Nullable [] splitMetaValue(String s) {
        int first = s.indexOf("..");
        if (first < 0) return null;
        int second = s.indexOf("..", first + 2);
        if (second < 0) return null;
        return new String[]{s.substring(0, first), s.substring(first + 2, second), s.substring(second + 2)};
    }

    private static boolean isBlockStart(String object) {
        return object.equals("ddot.it/block") || object.equals("!!block")
                || object.startsWith("ddot.it/block?") || object.startsWith("!!block?");
    }

    /** The {@code end=} marker of a block-start object, or {@code null} for the default blank-line terminator. */
    private static @Nullable String blockEndMarkerOf(String object) {
        String params;
        if (object.startsWith("ddot.it/block?")) params = object.substring("ddot.it/block?".length());
        else if (object.startsWith("!!block?")) params = object.substring("!!block?".length());
        else return null;
        String marker = null;
        for (String kv : params.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).strip().equals("end")) {
                String v = kv.substring(eq + 1).strip();
                if (!v.isEmpty()) marker = v;
            }
        }
        return marker;
    }

    private static boolean isBlockEnd(String line, @Nullable String marker) {
        if (marker == null) return line.strip().isEmpty();
        return line.strip().equals(marker);
    }

    /** Picks the primary FIX rule id that best describes a reformat (presentation only). */
    private static String classify(String rawType, String triplePart, boolean hasMeta) {
        if (rawType.isEmpty()) {
            // untyped link: DL003 if the source spelled it as the spaced ".. .." variant, else DL002.
            return triplePart.contains(".. ..") ? "DL003" : "DL002";
        }
        if (hasMeta) return "DL004";
        return "DL001";
    }
}
