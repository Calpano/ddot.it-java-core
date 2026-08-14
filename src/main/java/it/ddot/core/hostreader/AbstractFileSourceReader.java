package it.ddot.core.hostreader;

import it.ddot.core.source.ISourceFormat;
import it.ddot.core.source.ISourceResourceAddress;
import it.ddot.core.source.ISourceResourceData;
import it.ddot.core.source.ISourceScheme;
import it.ddot.core.source.LiveFileResourceData;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public abstract class AbstractFileSourceReader extends AbstractSourceReader {

    public AbstractFileSourceReader(ISourceFormat sourceKind) {
        super(sourceKind);
    }

    /**
     * Send all lines of the file and its resourceUri to consumer
     */
    public static void readLines(File f, Consumer<List<String>> resourceUri_lines) throws IOException {
        try {
            List<String> lines = Files.readAllLines(f.toPath());
            resourceUri_lines.accept(lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static File toFile(ISourceResourceAddress sourceResourceAddress) {
        assert sourceResourceAddress.isFile();
        return new File(URI.create(sourceResourceAddress.resourceUri()));
    }

    /** each including a leading dot */
    public Set<String> fileExtensions() {
        return sourceKind().fileExtensions();
    }

    public ISourceScheme getScheme() {
        return ISourceScheme.FILE;
    }

    public String mimeType() {
        return sourceKind().mimeType();
    }

    @Override
    public ISourceResourceData resourceData(ISourceResourceAddress sourceResourceAddress) throws IOException {
        File f = toFile(sourceResourceAddress);
        if (!f.isFile()) {
            throw new IllegalArgumentException("Source resource address does not point to a file");
        }
        return new LiveFileResourceData(f);
    }

}
