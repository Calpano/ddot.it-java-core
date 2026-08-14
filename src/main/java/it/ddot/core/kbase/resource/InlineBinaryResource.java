package it.ddot.core.kbase.resource;

import it.ddot.core.kbase.resource.InlineResource;
import org.jspecify.annotations.Nullable;

public class InlineBinaryResource extends InlineResource {

    private final byte[] contentBytes;

    public InlineBinaryResource(String mimeType, byte[] contentBytes) {
        super(mimeType);
        this.contentBytes = contentBytes;
    }

    @Override
    public @Nullable String content() {
        // base64 encode
        return java.util.Base64.getEncoder().encodeToString(contentBytes);
    }

    public byte @Nullable [] contentBytes() {
        return contentBytes;
    }

}
