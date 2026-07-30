package com.calpano.ddot.it.source;

import java.util.Set;

public class SourceKinds {

    public static final ISourceFormat ASCIIDOC = ISourceFormat.of("asciidoc", Set.of("adoc", "asciidoc"));

    public static final ISourceFormat DDOT = ISourceFormat.of("ddot", Set.of("ddot"));

    public static final ISourceFormat DOCX = ISourceFormat.of("docx", Set.of("docx"));

    public static final ISourceFormat FILE = ISourceFormat.of("file");

    public static final ISourceFormat HTML = ISourceFormat.of("html", Set.of("html", "htm"));

    public static final ISourceFormat JAVA = ISourceFormat.of("Java source", Set.of("java"));

    public static final ISourceFormat MARKDOWN = ISourceFormat.of("markdown", Set.of("md", "markdown"));

    public static final ISourceFormat PPTX = ISourceFormat.of("pptx", Set.of("pptx"));

    public static final ISourceFormat XML = ISourceFormat.of("xml", Set.of("xml"));

    public static final ISourceFormat YAML = ISourceFormat.of("yaml", Set.of("yaml", "yml"));

}
