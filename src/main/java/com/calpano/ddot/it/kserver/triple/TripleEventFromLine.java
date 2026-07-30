package com.calpano.ddot.it.kserver.triple;

import com.calpano.ddot.it.kserver.base.IMeta;
import com.calpano.ddot.it.kserver.base.ITripleEvent;
import com.calpano.ddot.it.source.ISourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * Immutable value object representing a triple event fired by a ddot.it reader.
 */
public record TripleEventFromLine(
        String from,
        @Nullable String type,
        String to,
        @Nullable IMeta meta,
        ISourceLocation sourceLocation
) implements ITripleEvent {}
