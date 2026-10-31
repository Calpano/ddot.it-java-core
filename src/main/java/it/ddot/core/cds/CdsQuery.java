package it.ddot.core.cds;

import java.util.List;

/**
 * A query over a {@link CdsReasoner}: triple patterns with variables, property paths, and a
 * CONSTRUCT template -- the expressiveness of a SPARQL CONSTRUCT query, without its syntax.
 *
 * <p>A name starting with {@code ?} is a variable. A pattern's middle is a {@link Path}: a relation,
 * a variable relation, or a path built from relations with {@link #inv}, {@link #seq}, {@link #alt},
 * {@link #plus} and {@link #star}.
 *
 * <pre>{@code
 * where(t("?x", HAS_TYPE, "?t"),
 *       t("?x", seq(rel(HAS_PART), rel(HAS_TAG)), "?tag"))
 *     .construct(tpl("?x", "has part tagged", "?tag"))
 * }</pre>
 *
 * Everything a pattern matches is entailed: a pattern for {@code related} matches a stated
 * {@code has subtype}. {@link CdsQueryEngine} evaluates it.
 */
public final class CdsQuery {

    private CdsQuery() {}

    public sealed interface Term permits Var, Const {}

    public record Var(String name) implements Term {}

    public record Const(String value) implements Term {}

    public static Term term(String text) {
        return text.startsWith("?") ? new Var(text.substring(1)) : new Const(text);
    }

    /** What connects a pattern's subject to its object. */
    public sealed interface Path permits Rel, VarRel, Inverse, Seq, Alt, OneOrMore, ZeroOrMore {}

    /** One relation, entailed. */
    public record Rel(String relation) implements Path {}

    /** Any relation, bound to a variable. Only as the whole of a pattern's path, as in SPARQL. */
    public record VarRel(String name) implements Path {}

    /** The path read backwards: SPARQL {@code ^p}. */
    public record Inverse(Path path) implements Path {}

    /** One path after another: SPARQL {@code p/q}, a property chain. */
    public record Seq(List<Path> steps) implements Path {}

    /** Either path: SPARQL {@code p|q}. */
    public record Alt(List<Path> choices) implements Path {}

    /** One or more steps: SPARQL {@code p+}, for relations the vocabulary does not make transitive. */
    public record OneOrMore(Path path) implements Path {}

    /** Zero or more steps: SPARQL {@code p*}. */
    public record ZeroOrMore(Path path) implements Path {}

    /** A relation, or a variable relation when the name starts with {@code ?}. */
    public static Path rel(String relation) {
        return relation.startsWith("?") ? new VarRel(relation.substring(1)) : new Rel(relation);
    }

    public static Path inv(Path path) {
        return new Inverse(path);
    }

    public static Path seq(Path... steps) {
        return new Seq(List.of(steps));
    }

    public static Path alt(Path... choices) {
        return new Alt(List.of(choices));
    }

    public static Path plus(Path path) {
        return new OneOrMore(path);
    }

    public static Path star(Path path) {
        return new ZeroOrMore(path);
    }

    /** One triple pattern of a query's WHERE part. */
    public record Pattern(Term subject, Path path, Term object) {}

    public static Pattern t(String subject, String relation, String object) {
        return new Pattern(term(subject), rel(relation), term(object));
    }

    public static Pattern t(String subject, Path path, String object) {
        return new Pattern(term(subject), path, term(object));
    }

    /** One triple of a CONSTRUCT template. */
    public record Template(Term subject, Term relation, Term object) {}

    public static Template tpl(String subject, String relation, String object) {
        return new Template(term(subject), term(relation), term(object));
    }

    /**
     * The WHERE part, for a CONSTRUCT query the template, and at most how many solutions to find:
     * SPARQL {@code LIMIT}. Which solutions a limit keeps depends on the order they are found in.
     */
    public record Query(List<Pattern> where, List<Template> template, int limit) {

        public Query construct(Template... triples) {
            return new Query(where, List.of(triples), limit);
        }

        public Query limit(int solutions) {
            return new Query(where, template, solutions);
        }
    }

    public static Query where(Pattern... patterns) {
        return new Query(List.of(patterns), List.of(), Integer.MAX_VALUE);
    }
}
