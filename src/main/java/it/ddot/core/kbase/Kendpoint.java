package it.ddot.core.kbase;

import it.ddot.core.kbase.inmem.Knode;
import com.graphinout.base.cj.document.CjDirection;
import org.jspecify.annotations.Nullable;

/** Immutable. Endpoint in a {@link IKnowledgeEdge} */
public class Kendpoint {

    public enum Kdirection {
        in, out, undir;

        public CjDirection toCj() {
            return switch (this) {
                case in -> CjDirection.IN;
                case out -> CjDirection.OUT;
                case undir -> CjDirection.UNDIR;
            };
        }
    }

    public final Kdirection kdirection;
    public final Knode node;
    public final @Nullable Knode type;

    public Kendpoint(Kdirection kdirection, Knode node, @Nullable Knode type) {
        this.kdirection = kdirection;
        this.node = node;
        this.type = type;
    }

}
