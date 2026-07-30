package com.calpano.ddot.it.kserver.triple;

import com.calpano.ddot.it.kserver.base.IMeta;
import com.calpano.ddot.it.kserver.base.ITripleEvent;
import com.calpano.ddot.it.source.ISourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * A synthetic triple event that was inferred rather than read from a source document.
 */
public record TripleEventInferred(String from, @Nullable String type, String to) implements ITripleEvent {

    public static final String INFERRED = "inferred";

    private static final ISourceLocation INFERRED_LOCATION = null;

    @Override
    public @Nullable IMeta meta() {
        return null;
    }

    @Override
    public ISourceLocation sourceLocation() {
        return INFERRED_LOCATION;
    }

}
