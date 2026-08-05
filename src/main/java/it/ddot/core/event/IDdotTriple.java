package it.ddot.core.event;

import it.ddot.core.DdotVocabulary;

/**
 * The 'naked' triple, just the SPO, here (from,type,to).
 * @see IDdotEvent
 */
public interface IDdotTriple {

    /**
     * ⓢ The subject, never empty. When the line omitted its subject, the one inherited from the preceding line is
     * substituted here.
     */
    String from();

    /**
     * ⓞ The object, never empty — an empty object is not a Triple by the grammar, so no event is emitted for one.
     */
    String to();

    /**
     * Ⓟ The user-typed relation ({@link DdotVocabulary#LINKS_TO} for an untyped link ({@code ....} / {@code .. ..}).
     */
    String type();


}
