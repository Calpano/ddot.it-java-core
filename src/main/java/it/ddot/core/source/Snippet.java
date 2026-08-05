package it.ddot.core.source;

import java.util.List;

/** A multi-line block object; its ddot serialization (command + content + marker) is verbatim in the source. */
public record BlockSnippet(int startLine, int endLine) implements ISnippet {

    @Override
    public boolean writable() {return true;}

    @Override
    public List<String> decorate(List<String> ddotLines) {return ddotLines;}

}
