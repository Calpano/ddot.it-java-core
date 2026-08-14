package it.ddot.core.io.handler;

import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.event.IDdotEvent;
import it.ddot.core.source.ISnippet;
import org.jspecify.annotations.Nullable;

public class Source2Kgraph implements IKgraphSourceHandler {

    private final Kgraph kgraph;
    private final TripleEvent2Kgraph tripleEvent2Kgraph;
    private final Source2TripleEvents source2TripleEvents;

    public Source2Kgraph(Kgraph kgraph) {
        this.kgraph = kgraph;
        this.tripleEvent2Kgraph = new TripleEvent2Kgraph(kgraph);
        this.source2TripleEvents = new Source2TripleEvents(this);
    }


    @Override
    public void activeSubject(String subject) {
        source2TripleEvents.activeSubject(subject);
    }

    @Override
    public @Nullable String activeSubject() {
        return source2TripleEvents.activeSubject();
    }

    @Override
    public Kgraph kgraph() {
        return kgraph;
    }

    @Override
    public void onLine(String line, int lineNumber) {
        source2TripleEvents.onLine(line, lineNumber);
    }

    @Override
    public void onTripleEvent(IDdotEvent tripleEvent) {
        tripleEvent2Kgraph.accept(tripleEvent);
    }

    @Override
    public void sourceEnd() {
        source2TripleEvents.sourceEnd();
    }

    @Override
    public void sourceResourceEnd() {
        source2TripleEvents.sourceResourceEnd();
    }

    @Override
    public void sourceResourceStart(ISnippet snippet) {
        source2TripleEvents.sourceResourceStart(snippet);
    }

    @Override
    public void sourceStart() {
        source2TripleEvents.sourceStart();
    }


}
