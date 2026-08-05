package it.ddot.core.source;

import java.util.Set;

/**
 * Kind of source document, e.g. "markdown", "yaml", "java". TODO would be null/'inferred' for inferred triples.
 * Human-readable kind, e.g. "file", "todoist-task", "google-contact".
 */
public interface ISourceFormat {

    static ISourceFormat of(String id, String mimeType, Set<String> fileExtensions) {
        return new ISourceFormat() {
            @Override
            public Set<String> fileExtensions() {
                return fileExtensions;
            }

            @Override
            public String mimeType() {
                return mimeType;
            }

            @Override
            public String sourceFormatId() {
                return id;
            }
        };
    }

    static ISourceFormat of(String id, String mimeType) {
        return of(id, mimeType, Set.of());
    }

    Set<String> fileExtensions();

    String mimeType();

    String sourceFormatId();

}
