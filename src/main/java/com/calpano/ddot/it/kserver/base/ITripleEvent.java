package com.calpano.ddot.it.kserver.base;

import com.calpano.ddot.it.kserver.triple.TripleEventInferred;
import com.calpano.ddot.it.source.ISourceLocation;
import org.jspecify.annotations.Nullable;

import java.util.stream.Stream;

/**
 * A triple event fired by a ddot.it reader for each recognized triple.
 *
 * @see <a href="https://github.com/ddot-it/ddot.it/blob/main/developer-guide.md#events">developer-guide.md#events</a>
 */
public interface ITripleEvent {

    static ITripleEvent inferred(String s, String p, String o) {
        return new TripleEventInferred(s, p, o);
    }

    /** Subject of the triple. */
    String from();

    /** Optional metadata attached to this triple, or {@code null} if none. */
    @Nullable IMeta meta();

    default Stream<String> parts() {
        if (type() == null)
            return Stream.of(from(), to());
        else
            return Stream.of(from(), type(), to());
    }

    ISourceLocation sourceLocation();


    /** Object of the triple. */
    String to();

    /** Type (property) of the triple. Null means the default type "links to". */
    @Nullable String type();

}
