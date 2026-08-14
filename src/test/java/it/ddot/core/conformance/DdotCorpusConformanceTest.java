package it.ddot.core.conformance;


import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.DdotSourceFormats;
import it.ddot.core.event.IDdotEvent;
import it.ddot.core.event.IMeta;
import it.ddot.core.event.IMetaPair;
import it.ddot.core.source.ISnippet;
import it.ddot.core.io.ISourceHandler;
import it.ddot.core.source.ISourceLocation;
import it.ddot.core.source.ISourceResourceAddress;
import it.ddot.core.io.handler.Source2TripleEvents;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Conformance test against the shared, cross-implementation ddot.it golden corpus (<a
 * href="https://ddot.it/spec/ddot-it-parse.html#events">Triple Events</a>).
 *
 * <p>The corpus lives in the sibling {@code ddot.it} repository under {@code test-data/cases/}. Each case directory
 * holds an {@code input.ddot} plus several {@code expected.<format>} files; an implementation consumes only the ones
 * relevant to its concern. This library has no tokenizer and no character offsets, so only
 * {@code expected.events.jsonl} is asserted here — {@code expected.tokens.json} and {@code expected.body.html} are out
 * of scope (they are covered by the IntelliJ plugin and the syntax-tools repo).
 *
 * <p>The {@code expected.events.jsonl} files are hand-written contract, never regenerated from an implementation.
 * When a case fails, the corpus is right until proven otherwise — fix this library, or (after amending the spec) the
 * corpus in its own repository. Never adjust the serialisation here to make a diff go away.
 *
 * <p>Two model mismatches are bridged by {@link #toJsonl} rather than being reported as failures, because they are
 * representation choices of this library, not parse differences:
 * <ul>
 *   <li>the corpus wants an <em>ordered array</em> of {@code {type,to}} meta pairs, while {@link IMeta} is a
 *       {@code Map<String,String>} (this library never produces more than one pair, so ordering cannot be observed);</li>
 *   <li>free meta text is stored under the empty key and is serialised with the corpus' {@code text} relation.</li>
 * </ul>
 * The second bridge is lossy — see {@code 24-untyped-inline-meta} in the known-divergence list.
 *
 * <p>Drives {@link Source2TripleEvents} directly (string in, events out) instead of {@code DdotReader}, so no disk
 * I/O or reader configuration sits between the corpus input and the grammar under test.
 */
class DdotCorpusConformanceTest {

    /** Absolute path override for the corpus case directory. */
    private static final String CORPUS_DIR_PROPERTY = "ddot.corpus.dir";
    /**
     * Default corpus location: the sibling {@code ddot.it} checkout, relative to this module's directory. One
     * {@code ..}, not two — this module used to sit one level deeper, inside {@code ddot-graph/}, and the extra hop
     * left over from that layout silently skipped every case here.
     */
    private static final String DEFAULT_CORPUS_DIR = "../ddot.it/test-data/cases";

    /** Fixed event context for this corpus, per {@code test-data/README.md}. */
    private static final String KIND = "ddot";
    private static final String SOURCE = "input.ddot";

    /** The corpus' relation for free meta text (",, a random note"); this library stores it under the empty key. */
    private static final String FREE_TEXT_META_TYPE = "text";

    private static final String DIVERGENCES_RESOURCE = "/ddot-corpus-known-divergences.txt";

    /** Sentinel case name used when the corpus is absent, so the parameterized test still has one invocation. */
    private static final String CORPUS_MISSING = "<corpus-missing>";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> KNOWN_DIVERGENCES = loadKnownDivergences();
    private static final List<String> REPORT = Collections.synchronizedList(new ArrayList<>());

    /** Case directory names, sorted; a single {@link #CORPUS_MISSING} sentinel when the corpus is not on disk. */
    static Stream<Arguments> corpusCases() {
        Path dir = corpusDir();
        if (!Files.isDirectory(dir)) return Stream.of(Arguments.of(CORPUS_MISSING));
        List<String> names;
        try (Stream<Path> children = Files.list(dir)) {
            names = children.filter(Files::isDirectory).filter(p -> Files.isRegularFile(p.resolve("expected.events.jsonl"))).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (names.isEmpty()) return Stream.of(Arguments.of(CORPUS_MISSING));
        return names.stream().map(Arguments::of);
    }

    /**
     * The corpus case directory: {@code -Dddot.corpus.dir=…} if set, else the sibling checkout relative to the working
     * directory (which Surefire sets to this project's base directory).
     */
    private static Path corpusDir() {
        String override = System.getProperty(CORPUS_DIR_PROPERTY);
        Path path = Path.of(override != null ? override : DEFAULT_CORPUS_DIR);
        return path.toAbsolutePath().normalize();
    }

    private static Map<String, String> loadKnownDivergences() {
        Map<String, String> map = new LinkedHashMap<>();
        try (InputStream in = DdotCorpusConformanceTest.class.getResourceAsStream(DIVERGENCES_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing test resource " + DIVERGENCES_RESOURCE);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int colon = trimmed.indexOf(':');
                if (colon < 0)
                    throw new IllegalStateException(DIVERGENCES_RESOURCE + ": expected '<case>: <reason>' but got: " + trimmed);
                map.put(trimmed.substring(0, colon).strip(), trimmed.substring(colon + 1).strip());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return map;
    }

    /** Feeds one corpus input through the ddot grammar and collects the emitted triple events, in order. */
    private static List<IDdotEvent> parse(String input, ISnippet snippet) {
        List<IDdotEvent> events = new ArrayList<>();
        Kgraph kgraph = new Kgraph("conformance");
        ISourceHandler sink = new ISourceHandler() {
            @Override
            public void activeSubject(String s) {}

            @Override
            public @Nullable String activeSubject() {return null;}

            @Override
            public void onLine(String l, int n) {}

            @Override
            public void onTripleEvent(IDdotEvent t) {events.add(t);}

            @Override
            public void sourceEnd() {}

            @Override
            public void sourceResourceEnd() {}

            @Override
            public void sourceResourceStart(ISnippet snippet) {}

            @Override
            public void sourceStart() {}
        };
        Source2TripleEvents parser = new Source2TripleEvents(sink);
        parser.sourceStart();
        parser.sourceResourceStart(snippet);
        String[] lines = splitLines(input);
        for (int i = 0; i < lines.length; i++) {
            parser.onLine(lines[i], i + 1); // corpus "location" is 1-based
        }
        parser.sourceResourceEnd();
        parser.sourceEnd(); // flushes an unterminated block
        return events;
    }

    @AfterAll
    static void printDivergenceTable() {
        if (REPORT.isEmpty()) return;
        List<String> lines = new ArrayList<>(REPORT);
        Collections.sort(lines);
        long conforming = lines.stream().filter(l -> l.startsWith("PASS")).count();
        long diverging = lines.stream().filter(l -> l.startsWith("DIVERGES")).count();
        long unexpected = lines.stream().filter(l -> l.startsWith("FAIL")).count();
        long fixed = lines.stream().filter(l -> l.startsWith("FIXED")).count();
        System.out.println("\n[ddot-corpus] conformance vs " + corpusDir());
        lines.forEach(l -> System.out.println("[ddot-corpus]   " + l));
        System.out.println("[ddot-corpus] " + conforming + " of " + lines.size() + " cases conform; " + diverging + " known divergences" + (unexpected > 0 ? "; " + unexpected + " UNEXPECTED FAILURES" : "") + (fixed > 0 ? "; " + fixed + " newly FIXED (delete their divergence lines)" : "") + "\n");
    }

    /**
     * Aborts the test with a message that names the expected path AND the override property, and shouts the same on
     * stderr — a silently skipped corpus test is indistinguishable from a passing one in a build log, which is exactly
     * how a stale corpus copy elsewhere in this codebase went unnoticed.
     */
    private static void skipLoudly() {
        String message = "ddot.it golden corpus NOT FOUND at " + corpusDir() + " — conformance was SKIPPED, not passed. Check out the ddot.it repository as a sibling of this" + " one, or point -D" + CORPUS_DIR_PROPERTY + "=/abs/path/to/ddot.it/test-data/cases at it.";
        System.err.println("[ddot-corpus] !!! " + message);
        Assumptions.abort(message);
    }

    /** Splits into source lines, dropping only the newline that terminates the final line. */
    private static String[] splitLines(String input) {
        String body = input.replace("\r\n", "\n").replace('\r', '\n');
        if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
        if (body.isEmpty()) return new String[0];
        return body.split("\n", -1);
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') end--;
        return s.substring(0, end);
    }

    /**
     * Serialises events into the corpus' JSONL shape: key order {@code from, type, to, meta, kind, source, location},
     * {@code type} omitted when null, {@code meta} omitted when empty, no insignificant whitespace, no trailing
     * newline.
     */
    private static String toJsonl(List<IDdotEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (IDdotEvent event : events) {
            ObjectNode node = JSON.createObjectNode(); // insertion-ordered
            node.put("from", event.from());
            event.type();
            node.put("type", event.type());
            node.put("to", event.to());
            IMeta meta = event.meta();
            // pairs(), not keys()/get(): meta is an ordered LIST of entries and the same type may
            // repeat — `,, first note ;; second note` is two `text` entries. A map view silently
            // collapses them, which made a correct library look like it was dropping notes.
            if (!meta.pairs().isEmpty()) {
                ArrayNode pairs = node.putArray("meta");
                for (IMetaPair mp : meta.pairs()) {
                    ObjectNode pair = pairs.addObject();
                    pair.put("type", mp.type().isEmpty() ? FREE_TEXT_META_TYPE : mp.type());
                    pair.put("to", mp.to());
                }
            }
            node.put("kind", KIND);
            node.put("source", SOURCE);
            // The corpus's `location` is a line in the case file, so it is the resource line — same as core's
            // DdotEventExporter writes.
            node.put("location", event.startLineInResource());
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(JSON.writeValueAsString(node));
        }
        return sb.toString();
    }

    /**
     * Asserts one corpus case: the events this library emits must be byte-identical to {@code expected.events.jsonl} —
     * unless the case is a known divergence, in which case it must still differ.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusCases")
    void case_producesGoldenEvents(String caseName) throws IOException {
        if (CORPUS_MISSING.equals(caseName)) {
            skipLoudly();
            return;
        }
        Path caseDir = corpusDir().resolve(caseName);
        Path inputPath = caseDir.resolve("input.ddot");
        ISnippet snippet = ISnippet.of(DdotSourceFormats.DDOT.sourceFormatId(), ISourceLocation.of(ISourceResourceAddress.of(inputPath.toUri().toASCIIString()), 1));

        String input = Files.readString(inputPath, StandardCharsets.UTF_8);
        String expected = stripTrailingNewlines(Files.readString(caseDir.resolve("expected.events.jsonl"), StandardCharsets.UTF_8));
        String actual = toJsonl(parse(input, snippet));

        String knownReason = KNOWN_DIVERGENCES.get(caseName);
        // Label from the ACTUAL comparison, never from list membership: an
        // unlisted case that fails must not print as PASS, or the table claims
        // a conformance the assertions below are about to reject.
        boolean matches = actual.equals(expected);
        String label = matches ? (knownReason == null ? "PASS     " : "FIXED    ") : (knownReason == null ? "FAIL     " : "DIVERGES ");
        REPORT.add(label + caseName + (knownReason == null ? "" : " — " + knownReason));

        if (knownReason == null) {
            assertWithMessage("Case '%s' does not conform to the ddot.it golden corpus.\n" + "The corpus is hand-written contract — fix this library, do not edit %s.\n" + "If the divergence is intended for now, add a line to src/test/resources%s.", caseName, caseDir.resolve("expected.events.jsonl"), DIVERGENCES_RESOURCE).that(actual).isEqualTo(expected);
        } else {
            assertWithMessage("Case '%s' is listed as a KNOWN DIVERGENCE (%s) but now MATCHES the corpus.\n" + "Delete its line from src/test/resources%s so the list cannot rot into a silent" + " allow-list.", caseName, knownReason, DIVERGENCES_RESOURCE).that(actual).isNotEqualTo(expected);
        }
    }

    /** Guards against the corpus silently shrinking to nothing (a no-op suite that still reports green). */
    @Test
    void corpus_isPresentAndSubstantial() {
        Path dir = corpusDir();
        if (!Files.isDirectory(dir)) {
            skipLoudly();
            return;
        }
        long cases = corpusCases().count();
        assertWithMessage("corpus at %s looks truncated — expected the full case set", dir).that(cases).isAtLeast(30L);
    }

    /** Every listed divergence must name a case that actually exists, so renamed/removed cases cannot hide. */
    @Test
    void knownDivergences_allNameAnExistingCase() {
        Path dir = corpusDir();
        if (!Files.isDirectory(dir)) {
            skipLoudly();
            return;
        }
        for (String caseName : KNOWN_DIVERGENCES.keySet()) {
            assertWithMessage("src/test/resources%s lists '%s', which is not a case in %s", DIVERGENCES_RESOURCE, caseName, dir).that(Files.isDirectory(dir.resolve(caseName))).isTrue();
        }
    }

}
