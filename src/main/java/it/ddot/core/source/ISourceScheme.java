package com.calpano.ddot.it.core.source;

/** The scheme of source */
public interface ISourceScheme {

    ISourceScheme FILE = of("file");

    static ISourceScheme of(String sourceScheme) {
        return new ISourceScheme() {

            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                ISourceScheme that = (ISourceScheme) o;
                return sourceScheme.equals(that.sourceScheme());
            }

            public int hashCode() {
                return sourceScheme.hashCode();
            }

            @Override
            public String sourceScheme() {return sourceScheme;}

            @Override
            public String toString() {
                return "ISourceScheme{" + sourceScheme + "}";
            }
        };
    }

    String sourceScheme();

}
