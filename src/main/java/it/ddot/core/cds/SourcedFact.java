package it.ddot.core.cds;

import it.ddot.core.store.IFact;
import it.ddot.core.store.ILineRange;
import org.jspecify.annotations.Nullable;

/**
 * A statement and where it was read: the file and the lines. The same statement read from two places is
 * two facts, so that {@link CdsReasoner#why} can name every place a conclusion rests on.
 */
public record SourcedFact(String subject, String relation, String object, @Nullable String file,
                          @Nullable Lines range, @Nullable String reader) implements IFact {

    /** 1-based, inclusive. */
    public record Lines(int startLine, int endLine) implements ILineRange {}

    /** A statement read from one line of a file. */
    public static SourcedFact at(String subject, String relation, String object, String file, int line) {
        return new SourcedFact(subject, relation, object, file, new Lines(line, line), null);
    }

    /** A statement with no provenance. */
    public static SourcedFact of(String subject, String relation, String object) {
        return new SourcedFact(subject, relation, object, null, null, null);
    }

    @Override
    public String toString() {
        return subject + " .." + relation + ".. " + object + (file == null ? "" : " @" + file + ":" + range);
    }
}
