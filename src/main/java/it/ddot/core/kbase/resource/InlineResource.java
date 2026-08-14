package it.ddot.core.kbase.resource;

import it.ddot.core.kbase.IKnowledgeItemResource;
import org.jspecify.annotations.Nullable;

public abstract class InlineResource implements IKnowledgeItemResource {

    private final String mimeType;

    protected InlineResource(String mimeType) {this.mimeType = mimeType;}

    @Override
    public @Nullable String mimeType() {
        return mimeType;
    }

    @Override
    public @Nullable String location() {
        return null;
    }

}
