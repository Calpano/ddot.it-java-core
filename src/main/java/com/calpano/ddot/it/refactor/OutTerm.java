package com.calpano.ddot.it.refactor;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * One position of an added {@link TripleTemplate}: either a literal id or a reference to a variable bound by
 * the rule's condition.
 */
public sealed interface OutTerm {

    /** Resolve to a concrete id given the condition's variable bindings, or {@code null} if unresolved. */
    @Nullable String resolve(Map<String, String> bindings);

    static OutTerm constant(String value) {return new Const(value);}

    static OutTerm varRef(String name) {return new VarRef(name);}

    /** A string with {@code ${var}} placeholders substituted from the bindings — e.g. {@code "app:${rest}"}. */
    static OutTerm template(String template) {return new Template(template);}

    record Const(String value) implements OutTerm {
        @Override public String resolve(Map<String, String> bindings) {return value;}
    }

    record VarRef(String name) implements OutTerm {
        @Override public @Nullable String resolve(Map<String, String> bindings) {return bindings.get(name);}
    }

    /** {@code ${name}} placeholders are replaced by bound values; unresolved if any referenced var is unbound. */
    record Template(String template) implements OutTerm {

        private static final java.util.regex.Pattern PLACEHOLDER =
                java.util.regex.Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9]*)}");

        @Override public @Nullable String resolve(Map<String, String> bindings) {
            java.util.regex.Matcher m = PLACEHOLDER.matcher(template);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String value = bindings.get(m.group(1));
                if (value == null) return null; // referenced variable not bound → unresolved
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

}
