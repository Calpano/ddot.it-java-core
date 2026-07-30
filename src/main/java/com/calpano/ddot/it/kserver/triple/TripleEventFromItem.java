package com.calpano.ddot.it.kserver.triple;

import com.calpano.ddot.it.kserver.base.IMeta;
import com.calpano.ddot.it.kserver.base.ITripleEvent;
import com.calpano.ddot.it.source.ISourceLocation;
import com.calpano.ddot.it.source.ISourceResourceAddress;
import org.jspecify.annotations.Nullable;

/**
 * Immutable value object representing a triple event fired by a ddot.it reader.
 */
public record TripleEventFromItem(
        String from,
        @Nullable String type,
        String to,
        @Nullable IMeta meta,
        ISourceResourceAddress sourceResourceAddress
) implements ITripleEvent {

    @Override
    public ISourceLocation sourceLocation() {
        return ISourceLocation.of(sourceResourceAddress, -1);
    }

}
