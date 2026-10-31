package it.ddot.core.lint;

import it.ddot.core.cds.Cds;
import it.ddot.core.cds.CdsReasoner;
import it.ddot.core.cds.Triple;
import it.ddot.core.store.IFact;
import it.ddot.core.embed.DdotOccurrences;
import it.ddot.core.token.TokenName;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@value DdotAnalysis#REPLACED_NAME}: a name the project says is replaced by another, such as {@code a} with
 * {@code a ..is replaced by.. has type}.
 *
 * <p>{@code is replaced by} is an editor instruction (ddot-it-vocabulary.adoc, "Usage in Interactive Tools"): a
 * user who types {@code aaa} where {@code aaa ..is replaced by.. bbb} holds should get {@code bbb}. It does not
 * make the two one thing -- that is {@code is alias of} -- so a reasoner does not read {@code a} as
 * {@code has type}. Every occurrence of a replaced name is therefore marked, naming its replacement, and an
 * editor can offer to substitute it.
 *
 * <p>What the reasoner entails counts: {@code bbb ..replaces.. aaa} says the same, alias spellings of the two
 * relations work, and chains are followed to their end ({@code a} replaced by {@code b}, {@code b} by {@code c}
 * gives {@code c}). Statements that name things on purpose -- {@code is replaced by}, {@code replaces},
 * {@code is alias of}, {@code has alias}, {@code same as} -- are not marked: they are where the names are
 * declared.
 */
final class Replacements {

    /** Relations whose statements declare names; their lines are never marked. */
    private static final Set<String> NAMING = Set.of(Cds.IS_REPLACED_BY, Cds.REPLACES, Cds.IS_ALIAS_OF,
            Cds.HAS_ALIAS, Cds.SAME_AS);

    /** A replaced name: what replaces it (chain ends, sorted) and where that is stated. */
    record Replacement(List<String> by, List<Location> statedAt) {}

    private Replacements() {}

    /** Replaced name → its replacement, for every name the project says is replaced. */
    static Map<String, Replacement> of(CdsReasoner cds) {
        Map<String, Set<String>> direct = new TreeMap<>();
        for (Triple t : cds.match(null, Cds.IS_REPLACED_BY, null)) {
            if (!t.subject().equals(t.object())) direct.computeIfAbsent(t.subject(), k -> new LinkedHashSet<>()).add(t.object());
        }
        Map<String, Replacement> out = new TreeMap<>();
        direct.forEach((name, by) -> {
            // Chain ends: replacements that are not replaced themselves. A cycle has none; then keep them all.
            Set<String> ends = new TreeSet<>();
            for (String b : by) if (!direct.containsKey(b)) ends.add(b);
            if (ends.isEmpty()) ends.addAll(by);
            Set<Location> at = new LinkedHashSet<>();
            for (String end : ends) at.addAll(stated(cds, name, end));
            out.put(name, new Replacement(List.copyOf(ends), List.copyOf(at)));
        });
        return out;
    }

    /** A finding on every occurrence, in one file's text, of a replaced name. */
    static List<Finding> check(String url, @Nullable String extension, String text, CdsReasoner cds,
                               Map<String, Replacement> replacements) {
        if (replacements.isEmpty()) return List.of();
        List<DdotOccurrences.Occurrence> occurrences = DdotOccurrences.inText(extension, text);
        Set<Integer> namingLines = new HashSet<>();
        for (DdotOccurrences.Occurrence o : occurrences) {
            if (o.role() == TokenName.RELATION && isNaming(cds, o.name())) namingLines.add(o.line());
        }
        int[] lineStarts = lineStarts(text);
        List<Finding> out = new ArrayList<>();
        for (DdotOccurrences.Occurrence o : occurrences) {
            if (namingLines.contains(o.line())) continue;
            Replacement r = replacements.get(o.name());
            if (r == null) continue;
            StringBuilder message = new StringBuilder("'" + o.name() + "' is replaced by ");
            for (int i = 0; i < r.by().size(); i++) {
                if (i > 0) message.append(i == r.by().size() - 1 ? " or " : ", ");
                message.append('\'').append(r.by().get(i)).append('\'');
            }
            message.append('.');
            int lineStart = lineStarts[o.line()];
            out.add(new Finding(DdotAnalysis.REPLACED_NAME, Severity.WARN, url, o.line() + 1, o.start() - lineStart,
                    o.end() - lineStart, message.toString(), r.statedAt()));
        }
        return out;
    }

    /** Whether {@code relation} is one of {@link #NAMING}: by name, through an alias, or as a sub-relation. */
    private static boolean isNaming(CdsReasoner cds, String relation) {
        for (String key : NAMING) {
            if (relation.equals(key) || cds.canonical(relation).equals(cds.canonical(key))) return true;
            if (cds.holds(relation, Cds.IS_ALIAS_OF, key)) return true;
            if (cds.isRelation(relation) && cds.isRelation(key) && cds.isSubRelation(relation, key)) return true;
        }
        return false;
    }

    /** Where {@code name ..is replaced by.. by} is stated: the read facts the reasoner derives it from. */
    private static List<Location> stated(CdsReasoner cds, String name, String by) {
        Optional<Set<IFact>> why = cds.why(name, Cds.IS_REPLACED_BY, by);
        if (why.isEmpty()) return List.of();
        Set<Location> out = new LinkedHashSet<>();
        for (IFact f : why.get()) if (f.isRead()) out.add(DdotAnalysis.locationOf(f));
        return List.copyOf(out);
    }

    private static int[] lineStarts(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        int[] starts = new int[lines];
        int line = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts[line++] = i + 1;
        return starts;
    }
}
