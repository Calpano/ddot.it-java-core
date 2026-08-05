package com.calpano.ddot.it.core.source;

import java.net.URI;

/**
 * A resource knows the format it represents, but not the exact source from which it was collected/can be collected.
 */
public interface ISourceResourceAddress {

    static ISourceResourceAddress of(String resourceUri) {
        return new ISourceResourceAddress() {

            @Override
            public String resourceUri() {
                return resourceUri;
            }

            @Override
            public String toString() {
                return "SourceResource(" + resourceUri + ")";
            }
        };
    }

    default boolean isFile() {
        return resourceUri().startsWith("file:");
    }

    /**
     * Stable URL uniquely identifying this source file path, e.g. "/README.md". TODO would be null/'inferred' for
     * inferred triples
     */
    String resourceUri();

    default ISourceScheme scheme() {
        String scheme = URI.create(resourceUri()).getScheme();
        return ISourceScheme.of(scheme);
    }

}
