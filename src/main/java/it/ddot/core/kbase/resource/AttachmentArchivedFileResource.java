package it.ddot.core.kbase.resource;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A file within an archive. URL format: {file url} '#' {path within archive}.
 */
public class AttachmentArchivedFileResource extends AttachmentResource {

    private static class ArchivedFile {

        final byte[] contentBytes;

        private ArchivedFile(byte[] contentBytes) {this.contentBytes = contentBytes;}

    }
    private final File archiveFile;
    private final String pathInArchive;
    private final boolean isBinary;

    /**
     * @param archiveFile   the archive file on the local file system
     * @param pathInArchive syntax: {path within archive} (e.g. "META-INF/MANIFEST.MF")
     * @param mimeType      for the file within the archive
     * @param isBinary      for the file within the archive
     */
    public AttachmentArchivedFileResource(File archiveFile, String pathInArchive, String mimeType, boolean isBinary) {
        super(mimeType);
        this.archiveFile = archiveFile;
        this.pathInArchive = pathInArchive;
        this.isBinary = isBinary;
    }

    @Override
    public @Nullable String content() {
        try {
            ArchivedFile archivedFile = decompress();
            if (isBinary) {
                // base64 encode the bytes
                return java.util.Base64.getEncoder().encodeToString(archivedFile.contentBytes);
            } else {
                // IMPROVE: handle other encodings by config/guessing
                return new String(archivedFile.contentBytes, StandardCharsets.UTF_8);
            }
        } catch (RuntimeException e) {
            throw new RuntimeException("Could not decompress '" + archiveFile.getAbsolutePath() + "'", e);
        }
    }

    @Override
    public byte[] contentBytes() {
        return decompress().contentBytes;
    }

    @Override
    public String location() {
        try {
            return archiveFile.getCanonicalFile().toURI() + "#" + pathInArchive;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ArchivedFile decompress() {
        // assume its always zip compressed (like .zip and .jar)
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(archiveFile)) {
            java.util.zip.ZipEntry entry = zipFile.getEntry(pathInArchive);
            if (entry == null) {
                throw new IOException("Entry " + pathInArchive + " not found in " + archiveFile.getAbsolutePath());
            }
            try (java.io.InputStream is = zipFile.getInputStream(entry)) {
                return new ArchivedFile(is.readAllBytes());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
