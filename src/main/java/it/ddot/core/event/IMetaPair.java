package it.ddot.core.event;


/**
 * One metadata entry — a (relation, object) pair, which is why it uses the same two key names as the triple itself.
 * <ul>
 *   <li>{@code ,, ..since.. 2016} — a typed pair: {@code {"type":"since","to":"2016"}}</li>
 *   <li>{@code ,, .... 2025} — the untyped shortcut: {@code {"type":"links to", "to":"2025"}}</li>
 *   <li>{@code ,, a random note} — free meta text: {@code {"type":"text","to":"a random note"}}</li>
 * </ul>
 */
public interface IMetaPair {

    static IMetaPair of(String type, String to) {
        return new MetaPair(type, to);
    }

    /** the meta-object, verbatim and never empty */
    String to();

    /**
     * The meta-relation. For a typed link ({@code ,, ..since.. 2016}) this is the relation name ('since'). For the
     * untyped link shortcut ({@code ,, .... value}) this is {@link it.ddot.core.DdotVocabulary#LINKS_TO}. Free meta-text
     * ({@code ,, a note}) is <em>not</em> untyped: it is reported with relation {@link it.ddot.core.DdotVocabulary#TEXT}. The lines
     * of one multi-line {@code ,,} text block are a single node and so join into one {@code text} entry with embedded
     * newlines, not one entry per line.
     */
    String type();

}
