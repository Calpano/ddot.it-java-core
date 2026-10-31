package it.ddot.core.lint;

import it.ddot.core.cds.CdsEngine;
import it.ddot.core.cds.CdsReasoner;
import it.ddot.core.cds.MemoryFactStore;
import it.ddot.core.cds.RuleSet;
import it.ddot.core.cds.SourcedFact;
import it.ddot.core.store.IFact;
import it.ddot.core.store.ILineRange;
import it.ddot.core.embed.LocatedTriple;
import it.ddot.core.event.DdotTriple;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The warnings about ddot.it a project gets, wherever it is shown: the IntelliJ plugin marks them in the
 * editor, {@code ddot lint} prints them. Both get them from here, so they agree.
 *
 * <h2>Rules</h2>
 * <table>
 *   <caption>What is checked</caption>
 *   <tr><th>Rule</th><th>Scope</th><th>What</th></tr>
 *   <tr><td>DL010–DL012</td><td>file</td><td>{@link DdotLinter}'s report-only diagnostics: lines that look
 *       like triples but are dropped</td></tr>
 *   <tr><td>{@value #INCOMPLETE_TRIPLE}</td><td>file</td><td>a triple line with no object:
 *       {@code s ..p..}</td></tr>
 *   <tr><td>{@value #CARDINALITY}</td><td>project</td><td>a cardinality bound violated</td></tr>
 *   <tr><td>{@value #CARDINALITY_VALUE}</td><td>project</td><td>a cardinality that is no whole number</td></tr>
 *   <tr><td>{@value #CASE_VARIANT}</td><td>project</td><td>a name also spelled in another letter case</td></tr>
 *   <tr><td>{@value #RELATION_ON_BOTH_SIDES}</td><td>project</td><td>a relation under both {@code links to}
 *       and {@code is linked from}</td></tr>
 *   <tr><td>{@value #REPLACED_NAME}</td><td>project</td><td>a name the project says is replaced by another:
 *       {@code a} with {@code a ..is replaced by.. has type}</td></tr>
 * </table>
 *
 * <h2>Use</h2>
 * The project-wide checks read every file's triples, which callers usually have already -- the plugin keeps
 * them in an index -- so they come in as {@link LocatedTriple}s per file. An analysis is made once per state
 * of the project ({@link #of}); then {@link #findings(String, String, String)} gives one file's warnings,
 * which also reads the file's text, for the per-file rules and to find each occurrence of a name.
 *
 * <p>Files are named by whatever the caller uses -- a URL, a path -- consistently: findings carry that name.
 * Messages name no file; where something is stated is in {@link Finding#related()}, for the caller to show.
 */
public final class DdotAnalysis {

    public static final String INCOMPLETE_TRIPLE = "DL017";
    public static final String CARDINALITY = "DL020";
    public static final String CARDINALITY_VALUE = "DL021";
    public static final String CASE_VARIANT = "DL022";
    public static final String RELATION_ON_BOTH_SIDES = "DL023";
    public static final String REPLACED_NAME = "DL024";

    private final Map<String, List<Finding>> projectFindings;
    private final Map<String, Map<String, Location>> caseVariantGroups;
    private final CdsReasoner reasoner;
    private final Map<String, Replacements.Replacement> replacements;

    private DdotAnalysis(Map<String, List<Finding>> projectFindings,
                         Map<String, Map<String, Location>> caseVariantGroups,
                         CdsReasoner reasoner, Map<String, Replacements.Replacement> replacements) {
        this.projectFindings = projectFindings;
        this.caseVariantGroups = caseVariantGroups;
        this.reasoner = reasoner;
        this.replacements = replacements;
    }

    /** Runs the project-wide checks over every file's triples. */
    public static DdotAnalysis of(Map<String, List<LocatedTriple>> triplesByFile) {
        Map<String, List<LocatedTriple>> byFile = new TreeMap<>(triplesByFile);
        MemoryFactStore<SourcedFact> store = new MemoryFactStore<>(SourcedFact::of);
        byFile.forEach((file, triples) -> {
            for (LocatedTriple l : triples) {
                DdotTriple t = l.triple();
                store.add(SourcedFact.at(t.from(), t.type(), t.to(), file, l.line()));
            }
        });
        List<Finding> all = new ArrayList<>();
        // Values are counted without chains: `max cardinality` on `has part` means direct parts.
        all.addAll(Cardinality.check(new CdsEngine(store, RuleSet.ALL_EXCEPT_TRANSITIVE), byFile));
        CdsEngine full = new CdsEngine(store, RuleSet.ALL);
        all.addAll(RelationSides.check(full));
        Map<String, List<Finding>> byUrl = new HashMap<>();
        for (Finding f : all) byUrl.computeIfAbsent(f.url(), k -> new ArrayList<>()).add(f);
        return new DdotAnalysis(byUrl, CaseVariants.groups(byFile), full, Replacements.of(full));
    }

    /** Only the checks that need nothing but one file: DL010–DL012 and {@value #INCOMPLETE_TRIPLE}. */
    public static List<Finding> fileFindings(String file, @Nullable String extension, CharSequence text) {
        List<Finding> out = new ArrayList<>(FileChecks.check(file, extension, text));
        out.sort(Finding.ORDER);
        return out;
    }

    /**
     * Every warning for one file: its own checks, the project-wide findings located in it, and each
     * occurrence of a name with another spelling.
     *
     * @param extension the file's extension, which says where in it the ddot.it is
     * @param text      the file's text as the line numbers of its triples count it
     */
    public List<Finding> findings(String file, @Nullable String extension, String text) {
        List<Finding> out = new ArrayList<>(FileChecks.check(file, extension, text));
        out.addAll(projectFindings.getOrDefault(file, List.of()));
        out.addAll(CaseVariants.check(file, extension, text, caseVariantGroups));
        out.addAll(Replacements.check(file, extension, text, reasoner, replacements));
        out.sort(Finding.ORDER);
        return out;
    }

    /** The project-wide findings, in every file. */
    public List<Finding> projectFindings() {
        List<Finding> out = new ArrayList<>();
        projectFindings.values().forEach(out::addAll);
        out.sort(Finding.ORDER);
        return out;
    }

    /**
     * Names in use in more than one letter case: lower-cased name → each spelling → the first place it is
     * stated. What {@value #CASE_VARIANT} marks; also what a fix that unifies the spelling offers.
     */
    public Map<String, Map<String, Location>> caseVariantGroups() {
        return caseVariantGroups;
    }

    /**
     * Names the project says are replaced, with what replaces them: {@code a} → {@code [has type]}. What
     * {@value #REPLACED_NAME} marks; also what a fix that substitutes the name offers. Usually one
     * replacement; several when the project names more than one.
     */
    public Map<String, List<String>> replacements() {
        Map<String, List<String>> out = new TreeMap<>();
        replacements.forEach((name, r) -> out.put(name, r.by()));
        return out;
    }

    static Location locationOf(IFact fact) {
        ILineRange range = fact.range();
        return new Location(String.valueOf(fact.file()), range == null ? 1 : range.startLine());
    }
}
