package it.ddot.core.kbase;

import it.ddot.core.kbase.inmem.Klabel;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A multi-lingual label: an ordered list of value/language {@link Klabel.Entry entries}. The first entry is
 * conventionally treated as the primary display label.
 */
public interface IKnowledgeLabel {

    /** Adds a label value with no language tag. */
    void add(String value);

    /** Adds a label value with an optional BCP-47 {@code language} tag ({@code null} = no language). */
    void add(String value, @Nullable String language);

    /** The label entries, in insertion order. */
    List<Klabel.Entry> entries();

    /** Returns {@code true} if any entry's value equals {@code label}. */
    boolean hasLabelEntry(String label);

}
