package it.ddot.core.kbase.inmem;

import it.ddot.core.kbase.IKnowledgeLabel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class Klabel implements IKnowledgeLabel {

    public static class Entry {

        public String value;
        /** ISO code */
        public @Nullable String language;

        public Entry(String value, @Nullable String language) {
            this.value = value;
            this.language = language;
        }

    }

    private final List<Entry> entries = new ArrayList<>();

    public void add(String value) {
        entries.add(new Entry(value, null));
    }

    public void add(String value, @Nullable String language) {
        entries.add(new Entry(value, language));
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean hasLabelEntry(String label) {
        return entries.stream().anyMatch(e -> e.value.equals(label));
    }

}
