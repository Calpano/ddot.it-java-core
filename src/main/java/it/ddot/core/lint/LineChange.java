package it.ddot.core.lint;

/**
 * A single line the linter reformats to canonical form: its 1-based {@code lineNumber}, the {@code original}
 * text, the {@code canonical} replacement, and the primary FIX rule ({@code DL0xx}) that best describes the
 * change. Feeds both {@code --explain} (list every reformatted line) and the {@code --fix} write-back
 * (one {@code ReplaceLine} edit per change).
 */
public record LineChange(int lineNumber, String original, String canonical, String ruleId) {}
