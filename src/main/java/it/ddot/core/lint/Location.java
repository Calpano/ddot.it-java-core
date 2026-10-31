package it.ddot.core.lint;

/** Where something is stated: the file's URL (or path, as the caller names files) and a 1-based line. */
public record Location(String url, int line) {

    @Override
    public String toString() {
        return url + ":" + line;
    }
}
