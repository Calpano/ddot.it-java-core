package it.ddot.core;

import it.ddot.core.source.ISourceFormat;

import java.util.Set;

/**
 * The host format the reader read, as a short lowercase name. It is fixed per reader and hence identical on every event
 * that the reader emits. This class contains some constants for source formats. It is not a complete list.
 */
public class DdotSourceFormats {

    /** {@code text/asciidoc} is provisionally registered (Eclipse Foundation, 2025-07-10); full registration pends. */
    public static final ISourceFormat ASCIIDOC = ISourceFormat.of("asciidoc",
            "text/asciidoc",
            Set.of("adoc", "asciidoc"));

    /** Stand-alone {@code .ddot}: extension and media type are fixed by the Parse Spec, Guest and Host Language. */
    public static final ISourceFormat DDOT = ISourceFormat.of("ddot", "text/vnd.ddot.it", Set.of("ddot"));

    public static final ISourceFormat DOCX = ISourceFormat.of("docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Set.of("docx"));

    public static final ISourceFormat FILE = ISourceFormat.of("file", "application/octet-stream", Set.of());

    public static final ISourceFormat HTML = ISourceFormat.of("html", "text/html", Set.of("html", "htm"));

    /** No registered media type for Java sources; {@code text/x-java-source} is the conventional one. */
    public static final ISourceFormat JAVA = ISourceFormat.of("Java source", "text/x-java-source", Set.of("java"));

    public static final ISourceFormat MARKDOWN = ISourceFormat.of("markdown", "text/markdown",
            Set.of("md", "markdown"));

    public static final ISourceFormat PPTX = ISourceFormat.of("pptx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            Set.of("pptx"));

    public static final ISourceFormat XML = ISourceFormat.of("xml", "application/xml", Set.of("xml"));

    public static final ISourceFormat YAML = ISourceFormat.of("yaml", "application/yaml", Set.of("yaml", "yml"));

}
