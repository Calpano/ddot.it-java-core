package com.calpano.ddot.it.event;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Triple event matching the schema documented at
 * <a href="https://ddot.it/developer-guide.html#events">ddot.it events</a>.
 */
public final class DdotEvent {
    public final String from;
    public final @Nullable String type;
    public final String to;
    public final List<MetaPair> meta = new ArrayList<>();
    public final String kind;
    public final String source;
    public final int location;

    public DdotEvent(String from, @Nullable String type, String to,
                     String kind, String source, int location) {
        this.from = from;
        this.type = type;
        this.to = to;
        this.kind = kind;
        this.source = source;
        this.location = location;
    }

    /**
     * One metadata entry. {@code type} is null for the untyped meta shortcut
     * ({@code ,, .... value}) and is then omitted from the JSON, exactly as an
     * untyped triple omits its own {@code type} — consumers default it to
     * {@code links to}. Free meta text ({@code ,, a note}) is not untyped: it
     * carries the built-in relation {@code text}.
     */
    public record MetaPair(@Nullable String type, String to) {}
}
