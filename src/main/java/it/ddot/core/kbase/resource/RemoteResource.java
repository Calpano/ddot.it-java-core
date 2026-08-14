package it.ddot.core.kbase.resource;

import it.ddot.core.kbase.IKnowledgeItemResource;
import org.jspecify.annotations.Nullable;

public class RemoteResource implements IKnowledgeItemResource {

    private final String location;
    private final @Nullable String mimeType;
    private final boolean isBinary;

    public RemoteResource(String location, @Nullable String mimeType, boolean isBinary) {
        assert location.startsWith("http");
        this.location = location;
        this.mimeType = mimeType;
        this.isBinary = isBinary;
        // IMPROVE: add another constructor which uses Tika to guess the mime type & binary flag
    }

    @Override
    public @Nullable String content() {
        byte[] contentBytes = contentBytes();
        if (isBinary) {
            // base64 encode the bytes
            return java.util.Base64.getEncoder().encodeToString(contentBytes);
        } else {
            // IMPROVE: handle other encodings by config/guessing
            return new String(contentBytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Override
    public byte @Nullable [] contentBytes() {
        // use HTTP client to fetch location
        try {
            java.net.URL url = new java.net.URI(location).toURL();
            try (java.io.InputStream is = url.openStream()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch remote resource from " + location, e);
        }
    }

    @Override
    public @Nullable String location() {
        return location;
    }

    @Override
    public @Nullable String mimeType() {
        if (mimeType == null) {
            // fetch remote and use response header to find mime type
            try {
                java.net.URL url = new java.net.URI(location).toURL();
                java.net.URLConnection connection = url.openConnection();
                return connection.getContentType();
            } catch (Exception e) {
                return null;
            }
        } else {
            return mimeType;
        }
    }

}
