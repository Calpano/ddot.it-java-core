package it.ddot.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to embed ddot.it triples into Java bytecode.
 * <p>
 * Completely optional. This shows how versatile ddot.it is and provides a standard annotation for this use-case.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.PACKAGE})
public @interface DdotIt {

    /** Each string is parsed as a triple line. It may or may not contain a valid triple. */
    String[] value();

}

