package it.ddot.core.cds;

import it.ddot.core.cds.CdsQuery.Alt;
import it.ddot.core.cds.CdsQuery.Const;
import it.ddot.core.cds.CdsQuery.Inverse;
import it.ddot.core.cds.CdsQuery.OneOrMore;
import it.ddot.core.cds.CdsQuery.Path;
import it.ddot.core.cds.CdsQuery.Pattern;
import it.ddot.core.cds.CdsQuery.Query;
import it.ddot.core.cds.CdsQuery.Rel;
import it.ddot.core.cds.CdsQuery.Seq;
import it.ddot.core.cds.CdsQuery.Template;
import it.ddot.core.cds.CdsQuery.Term;
import it.ddot.core.cds.CdsQuery.Var;
import it.ddot.core.cds.CdsQuery.VarRel;
import it.ddot.core.cds.CdsQuery.ZeroOrMore;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates a {@link CdsQuery} over any {@link CdsReasoner}.
 *
 * <p>Every triple pattern goes to {@link CdsReasoner#match}, so the answers are entailed ones, in
 * canonical names. Patterns are joined one at a time, each asked with the variables the earlier
 * ones bound, which turns a scan into a lookup; the next is the one that makes the fewest
 * solutions, by the reasoner's {@link CdsReasoner#estimate} on a sample of the solutions so far.
 * {@code doc/cds-inf-perf.adoc}, "Join Order", has why, and what it was measured to gain.
 *
 * <p>Paths are evaluated here, not by the reasoner, from whichever end is bound. A path with both
 * ends free starts from everything its first step matches.
 */
public final class CdsQueryEngine {

    /** A subject and an object a path connects. */
    private record Pair(String subject, String object) {}

    private final CdsReasoner reasoner;

    public CdsQueryEngine(CdsReasoner reasoner) {
        this.reasoner = reasoner;
    }

    /** Every solution of the WHERE part, as variable name (without {@code ?}) to canonical name. */
    public List<Map<String, String>> select(Query query) {
        return select(query.where(), query.limit());
    }

    public List<Map<String, String>> select(List<Pattern> where) {
        return select(where, Integer.MAX_VALUE);
    }

    /**
     * The first {@code limit} solutions. Only the last join stops early: an earlier one cannot
     * know which of its solutions the later patterns will throw away.
     */
    private List<Map<String, String>> select(List<Pattern> where, int limit) {
        List<Map<String, String>> solutions = List.of(Map.of());
        List<Pattern> remaining = new ArrayList<>(where);
        Map<Pattern, List<Map<String, String>>> tables = new HashMap<>();
        while (!remaining.isEmpty() && !solutions.isEmpty()) {
            Pattern next = cheapest(remaining, solutions, tables);
            remaining.remove(next);
            boolean last = remaining.isEmpty();
            Set<Map<String, String>> extended = new LinkedHashSet<>();
            List<Map<String, String>> table = tables.get(next);
            Map<List<String>, List<Map<String, String>>> index = table == null ? null : index(next, table, solutions.getFirst().keySet());
            for (Map<String, String> solution : solutions) {
                if (index != null) join(solution, next, index, extended);
                else extend(solution, next, extended);
                if (last && extended.size() >= limit) break;
            }
            solutions = List.copyOf(extended);
        }
        return solutions.size() > limit ? solutions.subList(0, limit) : solutions;
    }

    /** The template instantiated with every solution; a triple with an unbound variable is left out. */
    public Set<Triple> construct(Query query) {
        Set<Triple> out = new LinkedHashSet<>();
        for (Map<String, String> solution : select(query)) {
            for (Template template : query.template()) {
                String subject = value(template.subject(), solution);
                String relation = value(template.relation(), solution);
                String object = value(template.object(), solution);
                if (subject != null && relation != null && object != null) out.add(new Triple(subject, relation, object));
            }
        }
        return out;
    }

    // ---- joins ---------------------------------------------------------------------------------

    /** How many of the solutions so far a candidate pattern is estimated on. */
    private static final int SAMPLES = 8;

    /**
     * Solutions few enough that a pattern making no more is joined next without trying the
     * patterns the reasoner cannot estimate: trying one can cost more than the join it saves.
     */
    private static final double FEW = 256;

    /**
     * The pattern that makes the fewest solutions.
     *
     * <p>Each pattern with a bound end is estimated on up to {@value #SAMPLES} of the solutions so
     * far, spread over them: its mean fan-out times their number. The reasoner estimates a single
     * relation without listing its answers. A path, or a relation the reasoner cannot estimate, is
     * tried instead -- evaluated -- but only while there is one solution, or when no estimated
     * pattern makes fewer than {@value #FEW}. A pattern tried on the only solution is kept whole,
     * as a table: every later solution extends that one, so joining the pattern later is a lookup
     * in the table rather than a question per solution. A pattern with both ends free would be a
     * scan; it comes after every pattern with a bound end. Ties go to the most bound, then the
     * first.
     */
    private Pattern cheapest(List<Pattern> patterns, List<Map<String, String>> solutions, Map<Pattern, List<Map<String, String>>> tables) {
        Set<String> bound = solutions.getFirst().keySet();
        if (patterns.size() == 1) return patterns.getFirst();
        int step = Math.max(1, solutions.size() / SAMPLES);
        List<Integer> samples = new ArrayList<>();
        for (int i = 0; i < solutions.size() && samples.size() < SAMPLES; i += step) samples.add(i);
        Pattern best = null;
        double bestEstimate = Double.MAX_VALUE;
        int bestScore = -1;
        Pattern scan = null;
        int scanScore = -1;
        List<Pattern> untried = new ArrayList<>();
        for (Pattern pattern : patterns) {
            int score = score(pattern, bound);
            double estimate;
            List<Map<String, String>> table = tables.get(pattern);
            if (table != null) {
                Map<List<String>, List<Map<String, String>>> index = index(pattern, table, bound);
                long made = 0;
                for (int i : samples) made += index.getOrDefault(key(pattern, solutions.get(i), bound), List.of()).size();
                estimate = (double) made / samples.size() * solutions.size();
            } else if (!isBound(pattern.subject(), bound) && !isBound(pattern.object(), bound)) {
                if (score > scanScore) {
                    scan = pattern;
                    scanScore = score;
                }
                continue;
            } else {
                estimate = estimate(pattern, solutions, samples);
            }
            if (estimate < 0) {
                untried.add(pattern);
            } else if (estimate < bestEstimate || estimate == bestEstimate && score > bestScore) {
                best = pattern;
                bestEstimate = estimate;
                bestScore = score;
            }
        }
        boolean once = solutions.size() == 1;
        boolean nothingCheap = best == null || bestEstimate > FEW;
        if (nothingCheap || once) {
            for (Pattern pattern : untried) {
                if (!nothingCheap && !isPath(pattern.path())) continue;
                long made = 0;
                List<Map<String, String>> whole = once ? new ArrayList<>() : null;
                for (int i : samples) {
                    Set<Map<String, String>> out = new LinkedHashSet<>();
                    extend(solutions.get(i), pattern, out);
                    made += out.size();
                    if (once) whole.addAll(out);
                }
                if (once) tables.put(pattern, whole);
                double estimate = (double) made / samples.size() * solutions.size();
                int score = score(pattern, bound);
                if (estimate < bestEstimate || estimate == bestEstimate && score > bestScore) {
                    best = pattern;
                    bestEstimate = estimate;
                    bestScore = score;
                }
            }
        }
        return best != null ? best : scan;
    }

    /** A property path, which a question per solution would walk: worth evaluating once, as a table. */
    private static boolean isPath(Path path) {
        return !(path instanceof Rel || path instanceof VarRel || path instanceof Inverse(Rel ignored));
    }

    /** The variables of a pattern that the solutions so far bind, in a fixed order. */
    private static List<String> shared(Pattern pattern, Set<String> bound) {
        List<String> out = new ArrayList<>(3);
        if (pattern.subject() instanceof Var var && bound.contains(var.name())) out.add(var.name());
        if (pattern.path() instanceof VarRel var && bound.contains(var.name())) out.add(var.name());
        if (pattern.object() instanceof Var var && bound.contains(var.name())) out.add(var.name());
        return out;
    }

    private static List<String> key(Pattern pattern, Map<String, String> solution, Set<String> bound) {
        List<String> out = new ArrayList<>(3);
        for (String name : shared(pattern, bound)) out.add(solution.get(name));
        return out;
    }

    /** A table's rows by the values they give the variables the solutions so far bind. */
    private static Map<List<String>, List<Map<String, String>>> index(Pattern pattern, List<Map<String, String>> table, Set<String> bound) {
        Map<List<String>, List<Map<String, String>>> out = new HashMap<>();
        for (Map<String, String> row : table) out.computeIfAbsent(key(pattern, row, bound), k -> new ArrayList<>()).add(row);
        return out;
    }

    /** A solution extended by the rows of a table that agree with it. */
    private static void join(Map<String, String> solution, Pattern pattern, Map<List<String>, List<Map<String, String>>> index,
            Set<Map<String, String>> out) {
        for (Map<String, String> row : index.getOrDefault(key(pattern, solution, solution.keySet()), List.of())) {
            Map<String, String> next = new LinkedHashMap<>(solution);
            next.putAll(row);
            out.add(next);
        }
    }

    /** The reasoner's estimate of what a pattern makes from the sampled solutions, scaled to all; -1 when it has none. */
    private double estimate(Pattern pattern, List<Map<String, String>> solutions, List<Integer> samples) {
        long made = 0;
        for (int i : samples) {
            Map<String, String> solution = solutions.get(i);
            String subject = value(pattern.subject(), solution);
            String object = value(pattern.object(), solution);
            long one = switch (pattern.path()) {
                case Rel rel -> reasoner.estimate(subject, rel.relation(), object);
                case VarRel var -> reasoner.estimate(subject, solution.get(var.name()), object);
                case Inverse(Rel rel) -> reasoner.estimate(object, rel.relation(), subject);
                default -> -1;
            };
            if (one < 0) return -1;
            made += one;
        }
        return (double) made / samples.size() * solutions.size();
    }

    /** How bound a pattern is: an end counts twice what its relation does. */
    private static int score(Pattern pattern, Set<String> bound) {
        return (isBound(pattern.subject(), bound) ? 2 : 0) + (isBound(pattern.object(), bound) ? 2 : 0)
                + (pattern.path() instanceof VarRel v && !bound.contains(v.name()) ? 0 : 1);
    }

    private static boolean isBound(Term term, Set<String> bound) {
        return term instanceof Const || term instanceof Var var && bound.contains(var.name());
    }

    private static @Nullable String value(Term term, Map<String, String> solution) {
        return switch (term) {
            case Const constant -> constant.value();
            case Var var -> solution.get(var.name());
        };
    }

    private void extend(Map<String, String> solution, Pattern pattern, Set<Map<String, String>> out) {
        String subject = value(pattern.subject(), solution);
        String object = value(pattern.object(), solution);
        if (pattern.path() instanceof VarRel var && !solution.containsKey(var.name())) {
            for (Triple triple : reasoner.match(subject, null, object)) {
                Map<String, String> next = bind(solution, pattern.subject(), triple.subject());
                if (next != null) next = bind(next, new Var(var.name()), triple.relation());
                if (next != null) next = bind(next, pattern.object(), triple.object());
                if (next != null) out.add(next);
            }
            return;
        }
        Path path = pattern.path() instanceof VarRel var ? new Rel(solution.get(var.name())) : pattern.path();
        for (Pair pair : pairs(path, subject, object)) {
            Map<String, String> next = bind(solution, pattern.subject(), pair.subject());
            if (next != null) next = bind(next, pattern.object(), pair.object());
            if (next != null) out.add(next);
        }
    }

    /** The solution with one more binding, or null when the variable is bound to something else. */
    private static @Nullable Map<String, String> bind(Map<String, String> solution, Term term, String value) {
        if (!(term instanceof Var var)) return solution;
        String already = solution.get(var.name());
        if (already != null) return already.equals(value) ? solution : null;
        Map<String, String> out = new LinkedHashMap<>(solution);
        out.put(var.name(), value);
        return out;
    }

    // ---- paths ---------------------------------------------------------------------------------

    private Set<Pair> pairs(Path path, @Nullable String subject, @Nullable String object) {
        return switch (path) {
            case Rel rel -> {
                Set<Pair> out = new LinkedHashSet<>();
                for (Triple triple : reasoner.match(subject, rel.relation(), object)) {
                    out.add(new Pair(triple.subject(), triple.object()));
                }
                yield out;
            }
            case VarRel var -> throw new IllegalArgumentException("?" + var.name() + " can only be a whole pattern's relation");
            case Inverse inverse -> {
                Set<Pair> out = new LinkedHashSet<>();
                for (Pair pair : pairs(inverse.path(), object, subject)) out.add(new Pair(pair.object(), pair.subject()));
                yield out;
            }
            case Seq seq -> sequence(seq.steps(), subject, object);
            case Alt alt -> {
                Set<Pair> out = new LinkedHashSet<>();
                for (Path choice : alt.choices()) out.addAll(pairs(choice, subject, object));
                yield out;
            }
            case OneOrMore more -> chained(more.path()) ? pairs(more.path(), subject, object) : closure(more.path(), subject, object, false);
            case ZeroOrMore any -> chained(any.path()) ? withItself(any.path(), subject, object) : closure(any.path(), subject, object, true);
        };
    }

    /**
     * Whether a step of {@code path} is a relation the reasoner already follows chains of -- read
     * either way round -- so that {@code p+} is {@code p}. A walk over a 2 000-step chain otherwise
     * asks the reasoner once per step for what its first answer already held.
     */
    private boolean chained(Path path) {
        return switch (path) {
            case Rel rel -> reasoner.chains(rel.relation());
            case Inverse inverse -> chained(inverse.path());
            default -> false;
        };
    }

    /** {@code p*} for a chained {@code p}: {@code p}, and every node with itself. */
    private Set<Pair> withItself(Path step, @Nullable String subject, @Nullable String object) {
        Set<Pair> out = new LinkedHashSet<>(pairs(step, subject, object));
        if (subject != null && object != null) {
            if (reasoner.canonical(subject).equals(reasoner.canonical(object))) out.add(new Pair(reasoner.canonical(subject), reasoner.canonical(object)));
            return out;
        }
        if (subject != null || object != null) {
            String node = reasoner.canonical(subject != null ? subject : object);
            out.add(new Pair(node, node));
            return out;
        }
        for (Pair pair : pairs(step, null, null)) {
            out.add(new Pair(pair.subject(), pair.subject()));
            out.add(new Pair(pair.object(), pair.object()));
        }
        return out;
    }

    /** A chain, evaluated from whichever end is bound so that each step is a lookup. */
    private Set<Pair> sequence(List<Path> steps, @Nullable String subject, @Nullable String object) {
        if (steps.size() == 1) return pairs(steps.getFirst(), subject, object);
        Set<Pair> out = new LinkedHashSet<>();
        if (subject == null && object != null) {
            Path last = steps.getLast();
            List<Path> before = steps.subList(0, steps.size() - 1);
            for (Pair end : pairs(last, null, object)) {
                for (Pair start : sequence(before, null, end.subject())) out.add(new Pair(start.subject(), end.object()));
            }
            return out;
        }
        Path first = steps.getFirst();
        List<Path> after = steps.subList(1, steps.size());
        Map<String, Set<Pair>> fromMiddle = new HashMap<>();
        for (Pair start : pairs(first, subject, null)) {
            Set<Pair> ends = fromMiddle.computeIfAbsent(start.object(), middle -> sequence(after, middle, object));
            for (Pair end : ends) out.add(new Pair(start.subject(), end.object()));
        }
        return out;
    }

    /**
     * {@code p+} and {@code p*}: a walk from the bound end. With neither end bound, from every
     * subject the path matches -- and for {@code p*}, every such node reaches itself.
     */
    private Set<Pair> closure(Path step, @Nullable String subject, @Nullable String object, boolean zero) {
        Set<Pair> out = new LinkedHashSet<>();
        if (subject != null) {
            String start = reasoner.canonical(subject);
            for (String end : walk(step, start, true, zero)) {
                if (object == null || end.equals(reasoner.canonical(object))) out.add(new Pair(start, end));
            }
            return out;
        }
        if (object != null) {
            String start = reasoner.canonical(object);
            for (String end : walk(step, start, false, zero)) out.add(new Pair(end, start));
            return out;
        }
        Set<String> starts = new LinkedHashSet<>();
        for (Pair pair : pairs(step, null, null)) {
            starts.add(pair.subject());
            if (zero) starts.add(pair.object());
        }
        for (String start : starts) {
            for (String end : walk(step, start, true, zero)) out.add(new Pair(start, end));
        }
        return out;
    }

    private Set<String> walk(Path step, String start, boolean forwards, boolean zero) {
        Set<String> reached = new LinkedHashSet<>();
        if (zero) reached.add(start);
        Deque<String> queue = new ArrayDeque<>(List.of(start));
        Set<String> expanded = new LinkedHashSet<>(List.of(start));
        while (!queue.isEmpty()) {
            String at = queue.poll();
            Set<Pair> next = forwards ? pairs(step, at, null) : pairs(step, null, at);
            for (Pair pair : next) {
                String to = forwards ? pair.object() : pair.subject();
                reached.add(to);
                if (expanded.add(to)) queue.add(to);
            }
        }
        return reached;
    }
}
