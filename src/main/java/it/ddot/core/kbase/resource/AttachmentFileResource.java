package it.ddot.core.kbase.resource;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A local file on the file system
 */
public class AttachmentFileResource extends AttachmentResource {

    private final File file;
    private final boolean isBinary;

    public AttachmentFileResource(File file, String mimeType, boolean isBinary) {
        super(mimeType);
        this.file = file;
        this.isBinary = isBinary;
        // IMPROVE: add another constructor which uses Tika to guess the mime type & binary flag
    }

    @Override
    public @Nullable String content() {
        if (isBinary) {
            // base64 encode the bytes
            return java.util.Base64.getEncoder().encodeToString(contentBytes());
        } else {
            // IMPROVE: handle other encodings by config/guessing
            return new String(contentBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public byte[] contentBytes() {
        // in all cases: get the raw bytes of the file
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public @Nullable String location() {
        return null;
    }

}
