package it.ddot.core.source;

import it.ddot.core.io.ISourceReader;
import org.jspecify.annotations.Nullable;

/**
 * I/O result of a single {@link ISourceResourceAddress} when read by an {@link ISourceReader}
 */
public interface ISourceResourceData {

    ISourceResourceData UNKNOWN = ISourceResourceData.of(null, -1L);

    static ISourceResourceData of(@Nullable String contentHash, long sourceLastModified) {
        return new ISourceResourceData() {
            @Override
            public @Nullable String contentHash() {
                return contentHash;
            }

            @Override
            public long sourceLastModified() {
                return sourceLastModified;
            }
        };
    }

    /**
     * Opaque change-signal computed by the source (e.g. mtime+size, ETag, SHA-256). The engine compares this against
     * the stored value before reading snippets; unchanged blocks are skipped entirely. Null if no contentHash could be
     * provided.
     */
    @Nullable String contentHash();

    /**
     * Epoch-millisecond timestamp of the last known modification at the source. {@code -1} if the source cannot provide
     * this information (e.g. remote APIs with no timestamp).
     */
    long sourceLastModified();


}
