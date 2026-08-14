package it.ddot.core.source;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;

public class LiveFileResourceData implements ISourceResourceData {

    private final File file;

    public LiveFileResourceData(File file) {this.file = file;}

    @Override
    public String contentHash() {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            long mtime = attrs.lastModifiedTime().toMillis();
            // IMPROVE can a file content change if the lastModified date remains the same?
            return mtime + ":" + attrs.size() + file.length();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String resourceUri() {
        return file.toURI().toASCIIString();
    }

    @Override
    public long sourceLastModified() {
        return file.lastModified();
    }

}
