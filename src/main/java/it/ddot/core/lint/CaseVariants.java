package it.ddot.core.lint;

import it.ddot.core.embed.DdotOccurrences;
import it.ddot.core.embed.LocatedTriple;
import it.ddot.core.event.DdotTriple;
import it.ddot.core.token.TokenName;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@value DdotAnalysis#CASE_VARIANT}: names that occur in the project in more than one letter case, such as
 * {@code Project Eagle} and {@code project eagle}. To ddot.it these are two different entities, which is
 * rarely what was meant, so every spelling is marked. Alias statements ({@code Foo ..has alias.. foo},
 * {@code foo ..is alias of.. Foo}) name a case variant on purpose: they are neither marked nor counted.
 */
final class CaseVariants {

    /** Relations whose statements declare alternative names; exempt from the letter-case check. */
    static final Set<String> ALIAS_RELATIONS = Set.of("has alias", "is alias of");

    private CaseVariants() {}

    /**
     * Lower-cased name → each spelling → the first place it is stated. Covers subjects, relations, objects
     * and metadata pairs. Only names with two spellings or more are kept.
     */
    static Map<String, Map<String, Location>> groups(Map<String, List<LocatedTriple>> byUrl) {
        Map<String, Map<String, Location>> all = new HashMap<>();
        for (String url : new TreeMap<>(byUrl).keySet()) { // a stable "first place", whatever the map's order
            for (LocatedTriple l : byUrl.get(url)) {
                DdotTriple t = l.triple();
                if (ALIAS_RELATIONS.contains(t.type())) continue;
                Location at = new Location(url, l.line());
                List<String> names = new ArrayList<>(List.of(t.from(), t.type(), t.to()));
                for (LocatedTriple.Meta m : l.meta()) {
                    names.add(m.relation());
                    names.add(m.value());
                }
                for (String name : names) {
                    if (name.isEmpty() || name.contains("\n")) continue;
                    all.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new TreeMap<>()).putIfAbsent(name, at);
                }
            }
        }
        all.values().removeIf(spellings -> spellings.size() < 2);
        return all;
    }

    /** A finding on every occurrence, in one file's text, of a name another spelling of which is in use. */
    static List<Finding> check(String url, @Nullable String extension, String text,
                               Map<String, Map<String, Location>> groups) {
        if (groups.isEmpty()) return List.of();
        List<DdotOccurrences.Occurrence> occurrences = DdotOccurrences.inText(extension, text);
        Set<Integer> aliasLines = new HashSet<>();
        for (DdotOccurrences.Occurrence o : occurrences) {
            if (o.role() == TokenName.RELATION && ALIAS_RELATIONS.contains(o.name())) aliasLines.add(o.line());
        }
        int[] lineStarts = lineStarts(text);
        List<Finding> out = new ArrayList<>();
        for (DdotOccurrences.Occurrence o : occurrences) {
            if (aliasLines.contains(o.line())) continue;
            Map<String, Location> spellings = groups.get(o.name().toLowerCase(Locale.ROOT));
            if (spellings == null || !spellings.containsKey(o.name())) continue;
            StringBuilder message = new StringBuilder("'" + o.name() + "' differs only in letter case from ");
            List<Location> related = new ArrayList<>();
            for (Map.Entry<String, Location> e : spellings.entrySet()) {
                if (e.getKey().equals(o.name())) continue;
                if (!related.isEmpty()) message.append(", ");
                message.append('\'').append(e.getKey()).append('\'');
                related.add(e.getValue()); // in the order of the spellings in the message
            }
            message.append(". ddot.it treats them as different entities.");
            int lineStart = lineStarts[o.line()];
            out.add(new Finding(DdotAnalysis.CASE_VARIANT, Severity.WARN, url, o.line() + 1, o.start() - lineStart,
                    o.end() - lineStart, message.toString(), related));
        }
        return out;
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
