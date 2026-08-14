/**
 * Core abstractions of the ddot.it knowledge server ("kserver").
 *
 * <p>This package defines the public, implementation-agnostic API of the knowledge model. The in-memory
 * realization lives in {@code it.ddot.core.kbase.inmem} ({@code Kbase}, {@code Kgraph},
 * {@code Knode}, {@code Kedge}, {@code Klabel}); the concrete value types {@link Kendpoint}
 * and the {@code Kbases} conversion helpers ship in this package directly.
 *
 * <h2>The data model</h2>
 * <ul>
 *   <li>{@link IKnowledgeBase} — a knowledge base: a named collection of graphs plus a
 *       document-level {@code @context} namespace map.</li>
 *   <li>{@link IKnowledgeGraph} — a single graph holding nodes and (hyper-)edges, with
 *       query, pattern-matching, type ({@code "has type"}), merge and inference operations.</li>
 *   <li>{@link IKnowledgeItem} — the shared supertype of nodes and edges: anything carrying
 *       a label, JSON {@code data}, an optional {@link IKnowledgeItemResource} and source
 *       locations.</li>
 *   <li>{@link IKnowledgeNode} / {@link IKnowledgeEdge} — the two
 *       concrete item kinds. An edge connects nodes via a list of {@link Kendpoint}s,
 *       so edges are n-ary (hyperedges) by construction; ordinary binary edges are just the 2-endpoint case.</li>
 *   <li>{@link IKnowledgeLabel} — a multi-lingual label (a list of value/language
 *       entries).</li>
 *   <li>{@link IKnowledgeItemResource} — attached or inline content (file, archive entry,
 *       inline text/binary, or a remote URL) for an item.</li>
 *   <li>{@link IMeta} — optional key/value metadata attached to a triple or item.</li>
 * </ul>
 *
 * <h2>Ingestion and the triple view</h2>
 * <ul>
 *   <li>{@link IDdotEvent} — the event a ddot.it reader emits for each recognized
 *       triple; the streaming entry point into the model.</li>
 *   <li>{@link IDdotItKnowledgeBase} — a flat, triple-centric (subject/relation/object)
 *       facade intended for CLI tools, layered on top of a {@code Kbase}.</li>
 * </ul>
 *
 * <p>See {@code migrate.adoc} in this package for a gap analysis against the {@code cc-kg} API.
 */
package it.ddot.core.kbase;

import it.ddot.core.event.IDdotEvent;
import it.ddot.core.event.IMeta;
