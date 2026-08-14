package it.ddot.core.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.MODULE;
import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.ElementType.TYPE_PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;

/**
 * Annotation to embed ddot.it triples into Java bytecode.
 * <p>
 * Completely optional. This shows how versatile ddot.it is and provides a standard annotation for this use-case.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
        // Class, interface (including annotation interface), enum, or record; declaration */
        TYPE,

        // Field declaration (includes enum constants) */
        FIELD,

        // Method declaration */
        METHOD,

        // Formal parameter declaration */
        PARAMETER,

        // Constructor declaration */
        CONSTRUCTOR,

        // Local variable declaration */
        LOCAL_VARIABLE,

        // Annotation interface declaration (Formerly known as an annotation type.) */
        ANNOTATION_TYPE,

        // Package declaration */
        PACKAGE,

        // Type parameter declaration; @since 1.8
        TYPE_PARAMETER,

        // Use of a type; @since 1.8
        TYPE_USE,

        // Module declaration.; @since 9
        MODULE,

        // Record component; @jls 8.10.3 Record Members; @jls 9.7.4 Where Annotations May Appear; @since 16
        RECORD_COMPONENT
})
public @interface DdotIt {

    /** Each string is parsed as a triple line. It may or may not contain a valid triple. */
    String[] value();

}

