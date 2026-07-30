package com.calpano.ddot.it.source;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;

public class FileResourceAddress implements ISourceResourceAddress {

    private final File file;

    public FileResourceAddress(File file) {
        this.file = file;
    }

    public static FileResourceAddress of(URI uri) {
        return new FileResourceAddress(new File(uri));
    }

    public static ISourceResourceAddress of(Path path) {
        return new FileResourceAddress(path.toFile());
    }

    public String resourceUri() {
        return file.toURI().toASCIIString();
    }

    @Override
    public String toString() {
        return file.getPath();
    }

}
