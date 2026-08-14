package it.ddot.core.kbase;

import it.ddot.core.kbase.resource.AttachmentArchivedFileResource;
import it.ddot.core.kbase.resource.AttachmentFileResource;
import it.ddot.core.kbase.resource.InlineBinaryResource;
import it.ddot.core.kbase.resource.InlineTextResource;
import it.ddot.core.kbase.resource.RemoteResource;
import org.jspecify.annotations.Nullable;

import java.io.File;

/**
 * Content attached to an {@link IKnowledgeItem}. A resource is either inline (its bytes/text are held in
 * {@link #content()}) or referenced by {@link #location()} (a file, archive entry or remote URL). The static
 * factory methods construct the concrete variants in {@code it.ddot.core.kbase.resource}.
 */
public interface IKnowledgeItemResource {

    /** A resource backed by a local {@link File}. */
    static AttachmentFileResource attachedFile(String mimeType, File file, boolean isBinary) {
        return new AttachmentFileResource(file, mimeType, isBinary);
    }

    /** A resource backed by an entry ({@code pathInArchive}) inside an archive {@code File}. */
    static AttachmentArchivedFileResource attachedFileInArchive(String mimeType, File archiveFile, String pathInArchive, boolean isBinary) {
        return new AttachmentArchivedFileResource(archiveFile, pathInArchive, mimeType, isBinary);
    }

    /** An inline resource holding raw binary {@code content}. */
    static InlineBinaryResource inlineBinary(String mimeType, byte[] content) {
        return new InlineBinaryResource(mimeType, content);
    }

    /** An inline resource holding text {@code content} of the given mime type. */
    static InlineTextResource inlineText(String mimeType, String content) {
        return new InlineTextResource(mimeType, content);
    }

    /** Convenience for {@link #inlineText} with mime type {@code text/html}. */
    static InlineTextResource inlineText_Html(String htmlContent) {
        return new InlineTextResource("text/html", htmlContent);
    }

    /** A resource referenced by a remote {@code location} URL. */
    static RemoteResource remote(String location, String mimeType, boolean isBinary) {
        return new RemoteResource(location, mimeType, isBinary);
    }

    /** text or base64 encoded binary */
    @Nullable String content();

    default byte @Nullable [] contentBytes() {
        String content = content();
        if (content == null) {
            return null;
        }
        return content.getBytes();
    }

    /** null if content is given */
    @Nullable String location();

    /** null if implied from location url */
    @Nullable String mimeType();

}
