package it.ddot.core.io.handler;

import it.ddot.core.kbase.inmem.Kedge;
import it.ddot.core.kbase.inmem.Kgraph;
import it.ddot.core.kbase.inmem.Kitem;
import it.ddot.core.kbase.inmem.Knode;
import com.graphinout.foundation.pure.json.document.IJsonFactory;
import com.graphinout.foundation.pure.json.document.IJsonObject;
import com.graphinout.foundation.pure.json.document.IJsonObjectMutable;
import com.graphinout.foundation.pure.json.document.IJsonValue;
import it.ddot.core.event.IDdotEvent;
import it.ddot.core.event.IMeta;
import it.ddot.core.token.Commands;
import it.ddot.core.token.PreCommand;
import it.ddot.core.source.ISourceLocation;

import java.util.function.Consumer;

/**
 * Adds each {@link IDdotEvent} to a {@link Kgraph} as the real subject→predicate→object edge it represents.
 * <p>
 * Node identity is by id == text: the subject, predicate and object nodes are created (or reused) with their text as
 * id, so {@code Constant} pattern queries hit the SPO index. The label defaults to the id but may be set separately in
 * advanced ddot.it; labels are allowed to collide.
 * <p>
 * The triple's source location (resource + line) is recorded on the edge for provenance, and the triple's own metadata
 * ({@link IDdotEvent#meta()}, e.g. {@code ,, ..since.. 2025}) is folded into the edge's {@code data} JSON under a
 * {@code meta} key, so it is queryable and readable via {@link Kedge#meta()}.
 */
public class TripleEvent2Kgraph implements Consumer<IDdotEvent> {

    /**
     * {@code !!this} stands for "the chunk this text was written in", so it resolves to the event's own
     * source URI. Any other value passes through untouched.
     */
    private static String resolveThis(String field, IDdotEvent event) {
        return PreCommand.THIS_CMD.matcher(Commands.normalizeCommandToken(field)).matches()
                ? event.sourceUri() : field;
    }

    private final Kgraph kgraph;

    public TripleEvent2Kgraph(Kgraph kgraph) {this.kgraph = kgraph;}

    @Override
    public void accept(IDdotEvent tripleEvent) {
        // Resolve `!!this` HERE, because this is the collector: "A collector merges the events from many
        // readers into one knowledge base and does the resolution a single reader cannot -- substituting
        // `source` for `!!this`" (Parse Spec). Readers emit the command verbatim, so without this every
        // document's self-references would collapse onto one node literally called "ddot.it/this".
        Knode from = kgraph.getOrCreateNodeWithIdAndLabel(resolveThis(tripleEvent.from(), tripleEvent));
        Knode to = kgraph.getOrCreateNodeWithIdAndLabel(resolveThis(tripleEvent.to(), tripleEvent));
        // An untyped link already carries LINKS_TO in the event; the relation is never null here.
        Kedge edge = kgraph.biEdge(from, tripleEvent.type(), to);
        // Provenance: record where this triple came from. This is the EVENT's location — resource plus the line the
        // triple stands on — not `snippet().sourceLocation()`, which is where the snippet starts and is therefore
        // the same for every triple in it (Kitem.addSourceLocation dedups by (uri, line), so using the snippet's
        // location would collapse a whole file into one provenance entry pointing at its first line).
        // The location is recorded on the edge AND on both endpoint nodes, so a later removeBySource can drop
        // every node/edge whose provenance came solely from one source. The predicate node is intentionally
        // left without provenance, so shared vocabulary survives source removal.
        ISourceLocation location = tripleEvent.sourceLocation();
        edge.addSourceLocation(location);
        from.addSourceLocation(location);
        to.addSourceLocation(location);
        // Fold the triple's own metadata (e.g. ",, ..since.. 2025") into the edge's data under "meta",
        // so it becomes queryable and is read back by Kitem.meta().
        IMeta meta = tripleEvent.meta();
        if (meta != null && !meta.isEmpty()) {
            foldMetaIntoEdgeData(edge, meta);
        }
    }

    /**
     * Merges {@code meta} into the edge's {@code data} JSON under the {@code meta} key, preserving any existing
     * top-level data and any existing {@code meta} entries.
     * <p>
     * The event's metadata is an ordered LIST of pairs and a relation may repeat on one link
     * ({@code ,, ..author.. Ann ;; ..author.. Bob}). A JSON object cannot hold {@code author} twice, so a repeated
     * relation is stored as an ARRAY of its values — which is what {@link Kedge#meta()} expands back into two pairs.
     * Writing them one by one with {@code setString}, as this did, kept only the last.
     */
    private void foldMetaIntoEdgeData(Kedge edge, IMeta meta) {
        IJsonFactory jf = kgraph.jsonFactory();
        IJsonValue existing = edge.data();
        IJsonObject existingObj = (existing != null && existing.isObject()) ? existing.asObject() : null;

        IJsonObjectMutable root = jf.createObjectMutable();
        if (existingObj != null) existingObj.forEach(root::addProperty);

        IJsonObjectMutable metaObj = jf.createObjectMutable();
        IJsonValue existingMeta = existingObj != null ? existingObj.get("meta") : null;
        if (existingMeta != null && existingMeta.isObject()) existingMeta.asObject().forEach(metaObj::addProperty);
        // This event's own pairs first, so a relation IT repeats collects into an array; the result then
        // overrides the same key on the edge, keeping a re-read of the same source idempotent (which appending
        // straight onto the existing entries would not be).
        IJsonObjectMutable eventMeta = jf.createObjectMutable();
        meta.forEach((key, value) -> Kitem.appendMetaValue(eventMeta, key, value));
        eventMeta.forEach(metaObj::setProperty);

        root.setProperty("meta", metaObj);
        edge.data(root);
    }

}
