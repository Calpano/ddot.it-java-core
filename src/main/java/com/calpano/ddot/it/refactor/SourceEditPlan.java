package com.calpano.ddot.it.refactor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of {@link SourceEdit}s a rewrite would make to source files, plus a {@code skipped} report of
 * matches that could not be written back (e.g. non-{@code file:} provenance). Compute with
 * {@link GraphRewriter#plan}, inspect with {@link LineBasedSourceWriter#preview}, apply with
 * {@link LineBasedSourceWriter#apply}.
 */
public record SourceEditPlan(List<SourceEdit> edits, List<String> skipped) {

    public boolean isEmpty() {
        return edits.isEmpty();
    }

    /** Edits grouped by source file, preserving insertion order. */
    public Map<String, List<SourceEdit>> byFile() {
        Map<String, List<SourceEdit>> byFile = new LinkedHashMap<>();
        for (SourceEdit e : edits) byFile.computeIfAbsent(e.resourceUri(), k -> new java.util.ArrayList<>()).add(e);
        return byFile;
    }

}
