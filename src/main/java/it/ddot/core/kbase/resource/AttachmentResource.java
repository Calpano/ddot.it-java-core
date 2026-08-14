package it.ddot.core.kbase.resource;

import it.ddot.core.kbase.IKnowledgeItemResource;
import org.jspecify.annotations.Nullable;

/**
 * A local file on the file system or within an archive file.
 */
public abstract class AttachmentResource implements IKnowledgeItemResource {

    private final String mimeType;

    protected AttachmentResource(String mimeType) {this.mimeType = mimeType;}

    @Override
    public @Nullable String mimeType() {
        return mimeType;
    }

    @Override
    public @Nullable String location() {
        return null;
    }

}
