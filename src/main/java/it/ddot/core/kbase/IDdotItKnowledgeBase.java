package it.ddot.core.kbase;

import com.graphinout.foundation.pure.annotations.ModificationOperation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A flat, triple-centric (subject / relation / object) facade over a knowledge base, intended for CLI tools.
 * It hides the node/edge graph model behind plain {@link KTriple} values and adds load/save in the JSONL
 * triple-event format. The default implementation, {@code it.ddot.core.kbase.inmem.DdotItBaseOnKbase},
 * is backed by a {@code Kbase}.
 */
public interface IDdotItKnowledgeBase {

    /** Immutable value triple. */
    record KTriple(String subject, String relation, String object) {
        @Override public @NonNull String toString() {
            return subject + "  " + relation + "  " + object;
        }
    }

    /** Exact-match query: a {@code null} component acts as a wildcard for that position. */
    List<KTriple> query(@Nullable String subject, @Nullable String relation, @Nullable String object);

    /** Prefix-match query: null / empty / {@code "*"} params act as wildcards.
     *  Results are sorted alphabetically (subject → relation → object). */
    List<KTriple> queryByPrefix(@Nullable String subjectPrefix,
                                @Nullable String relationPrefix,
                                @Nullable String objectPrefix);

    /** Distinct subjects, optionally restricted to those starting with {@code prefix}, sorted alphabetically. */
    List<String> subjects(@Nullable String prefix);

    /** Distinct relations, optionally restricted to those starting with {@code prefix}, sorted alphabetically. */
    List<String> relations(@Nullable String prefix);

    /** Distinct objects, optionally restricted to those starting with {@code prefix}, sorted alphabetically. */
    List<String> objects(@Nullable String prefix);

    /** Adds the triple {@code (subject, relation, object)} to the store. */
    @ModificationOperation
    void add(String subject, String relation, String object);

    /** Removes the given triple from the store; a no-op if it is not present. */
    @ModificationOperation
    void remove(KTriple triple);

    /** The total number of triples currently in the store. */
    int tripleCount();

    /**
     * Load contents of a file additionally to store
     * @param file in JSONL format with Triple-Events
     */
    @ModificationOperation
    void loadTripleEvents(Path file) throws IOException;

    /** Writes the entire store to {@code file} in JSONL triple-event format. */
    void saveAsTripleEvents(Path file) throws IOException;

    /**
     * Writes the entire store to {@code file} in ddot triple-text format — one
     * {@code subject ..relation.. object} line per triple.
     * <p>
     * This default implementation emits the bare triples only. Implementations whose backing model
     * retains per-triple {@code ,,} metadata should override this to re-emit it (see
     * {@code DdotItBaseOnKbase}).
     */
    default void saveAsDdot(Path file) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (KTriple t : query(null, null, null)) {
                writer.write(t.subject() + " .." + t.relation() + ".. " + t.object());
                writer.newLine();
            }
        }
    }

}
