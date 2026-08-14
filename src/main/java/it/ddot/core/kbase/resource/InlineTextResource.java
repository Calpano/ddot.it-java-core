package it.ddot.core.kbase.resource;

import it.ddot.core.kbase.resource.InlineResource;
import org.jspecify.annotations.Nullable;

public class InlineTextResource extends InlineResource {

    private final String content;

    public InlineTextResource(String mimeType, String content) {
        super(mimeType);
        this.content = content;
    }

    @Override
    public @Nullable String content() {
        return content;
    }

}
