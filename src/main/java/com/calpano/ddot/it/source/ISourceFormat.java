package com.calpano.ddot.it.source;

import java.util.Set;

/**
 * Kind of source document, e.g. "markdown", "yaml", "java". TODO would be null/'inferred' for inferred triples.
 * Human-readable kind, e.g. "file", "todoist-task", "google-contact".
 */
public interface ISourceFormat {

    ISourceFormat FILE = of("file");

    static ISourceFormat of(String s, Set<String> fileExtensions) {
        return new ISourceFormat() {
            @Override
            public Set<String> fileExtensions() {
                return fileExtensions;
            }

            @Override
            public String sourceFormatId() {
                return s;
            }
        };
    }

    static ISourceFormat of(String s) {
        return of(s, Set.of());
    }

    Set<String> fileExtensions();

    String sourceFormatId();

}
